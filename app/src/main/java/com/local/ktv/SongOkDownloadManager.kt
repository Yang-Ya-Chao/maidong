package com.local.ktv

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 新实现：有界分段、长度验证、取消及断点续传；不使用 OkDownload 或 TS 解密。 */
object SongOkDownloadManager {
    const val MIN_VALID_FILE_SIZE = 64L
    private const val CHUNK = 2 * 1024 * 1024
    interface DownloadCallback {
        fun onDownloadStart(song: Song)
        fun onDownloadProgress(song: Song, progress: Int)
        fun onDownloadReadyToPlay(song: Song)
        fun onDownloadComplete(song: Song, localPath: String)
        fun onDownloadFailed(song: Song, error: String)
    }
    private class Job(val filename: String, var destination: SongStorage.Choice = SongStorage.choice()) {
        val cancelled = AtomicBoolean(false)
        val callbacks = CopyOnWriteArrayList<DownloadCallback>()
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var progress = 0
    }
    private data class Block(val bytes: ByteArray, val end: Long, val total: Long)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val spaceBudget = DownloadSpaceBudget()
    private val io = Executors.newFixedThreadPool(3)
    @Volatile private var resetting = false
    @Volatile internal var reclaimCache: ((SongStorage.Choice, Long) -> Unit)? = null

    /** Cancel and drain writers before clearing local files; never delete USB files here. */
    fun resetLocalCache(clear: () -> Unit) {
        val active = synchronized(this) {
            check(!resetting) { "曲库正在重置" }
            resetting = true
            jobs.toMap().also { snapshot ->
                snapshot.values.forEach { job ->
                    job.cancelled.set(true)
                    job.connection?.disconnect()
                }
            }
        }
        try {
            active.forEach { (id, _) -> synchronized(locks.getOrPut(id) { Any() }) { } }
            clear()
        } finally { synchronized(this) { resetting = false } }
    }
    private fun key(song: Song) = KtvStore.stableId(song)
    @JvmStatic fun getLocalFile(song: Song): File {
        val name = song.filename?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+\\.(mkv|mp4|mpg|avi)", RegexOption.IGNORE_CASE)) }
            ?: "${song.sourceSongNumber ?: key(song).replace(Regex("[^A-Za-z0-9_-]"), "_")}.mkv"
        return File(AppPaths.cloudSongsDir, name)
    }
    private fun cacheDirectories(requireUsb: Boolean = false): List<SongStorage.Directory> {
        val choices = listOf(SongStorage.Choice(), SongStorage.choice().copy(usb = true))
        return choices.mapNotNull { choice ->
            if (requireUsb && choice.usb && choice.tree.isNotBlank()) SongStorage.directory(choice)
            else runCatching { SongStorage.directory(choice) }.getOrNull()
        }
    }
    @JvmStatic fun isDownloaded(song: Song): Boolean {
        val name = getLocalFile(song).name
        for (directory in cacheDirectories()) {
            val target = directory.child(name)
            val valid = runCatching {
                val info = JSONObject(directory.child(name + ".complete.json").readText())
                info.optString("provider") == "igeba" &&
                    info.optString("SongNumber") == song.sourceSongNumber &&
                    target.info()?.bytes == info.getLong("bytes") && SongStorage.valid(target)
            }.getOrDefault(false)
            if (valid) { song.path = target.path; return true }
        }
        return false
    }
    @JvmStatic fun isDownloadingFilename(filename: String): Boolean =
        jobs.values.any { it.filename == filename && !it.cancelled.get() }
    @JvmStatic fun isDownloading(song: Song): Boolean = jobs[key(song)]?.cancelled?.get() == false
    @JvmStatic fun getDownloadProgress(song: Song): Int = jobs[key(song)]?.progress ?: if(isDownloaded(song)) 100 else 0
    @JvmStatic fun cancelDownload(song: Song) {
        jobs[key(song)]?.let { it.cancelled.set(true); it.connection?.disconnect() }
    }
    @JvmStatic fun deleteCache(song: Song): Boolean {
        val destination = jobs[key(song)]?.destination
        cancelDownload(song)
        return runCatching { synchronized(locks.getOrPut(key(song)) { Any() }) {
            val directories = cacheDirectories(requireUsb = true).toMutableList()
            destination?.let { choice ->
                runCatching { SongStorage.directory(choice) }.getOrNull()?.let(directories::add)
            }
            val name = getLocalFile(song).name
            val deleted = directories.distinctBy { it.file?.absolutePath ?: it.document.toString() }
                .flatMap { directory ->
                    listOf("", ".download", ".resume.json", ".complete.json").map { suffix ->
                        runCatching { directory.child(name + suffix).delete() }.getOrDefault(false)
                    }
                }.all { it }
            if (deleted) song.path = null
            deleted
        } }.getOrDefault(false)
    }
    @JvmStatic @Synchronized fun download(song: Song, callback: DownloadCallback?) {
        if (resetting) {
            callback?.onDownloadFailed(song, "曲库正在重置，请稍后重试")
            return
        }
        if (isDownloaded(song)) {
            callback?.onDownloadComplete(song, checkNotNull(song.path)); return
        }
        val id=key(song)
        val job=Job(getLocalFile(song).name)
        callback?.let(job.callbacks::add)
        var existing: Job? = null
        jobs.compute(id) { _, old ->
            if(old != null && !old.cancelled.get()) { existing=old; old } else job
        }
        if(existing != null) { callback?.let(existing!!.callbacks::add); callback?.onDownloadStart(song); return }
        io.execute {
            synchronized(locks.getOrPut(id) { Any() }) {
                try {
                    if(job.cancelled.get()) return@synchronized
                    job.callbacks.forEach { it.onDownloadStart(song) }
                    job.destination = SongStorage.prepareDownloadChoice()
                    try {
                        fetch(song, job)
                    } catch (error: Exception) {
                        if (!job.destination.usb || job.cancelled.get() || !isUsbStorageFailure(error)) throw error
                        spaceBudget.release(id)
                        job.destination = job.destination.copy(usb = false, tree = "")
                        job.progress = 0
                        android.util.Log.w("SongOkDownload", "U盘不可用或空间不足，回退本地，歌曲=${song.sourceSongNumber}")
                        fetch(song, job)
                    }
                } catch(error: Exception) {
                    if(!job.cancelled.get()) job.callbacks.forEach {
                        it.onDownloadFailed(song, error.message?.take(180) ?: "下载失败")
                    }
                } finally {
                    job.connection?.disconnect()
                    spaceBudget.release(id)
                    jobs.remove(id,job)
                }
            }
        }
    }

    private fun block(url: String, start: Long, wireEnd: Long, job: Job): Block {
        if(job.cancelled.get()) throw IOException("下载已暂停")
        val connection=URL(url).openConnection() as HttpURLConnection
        job.connection=connection
        try {
            connection.connectTimeout=15_000; connection.readTimeout=30_000
            connection.setRequestProperty("Range","bytes=$start-$wireEnd")
            connection.setRequestProperty("Accept-Encoding","identity")
            val code=connection.responseCode
            if(code != 206) throw MediaHttpException(code, connection.responseMessage.orEmpty())
            val range=MediaRangeProtocol.parse(connection.getHeaderField("Content-Range"),start)
            val end=range.end
            val total=range.total
            val expected=range.byteCount
            val data=ByteArray(expected)
            connection.inputStream.use { input ->
                var at=0
                while(at < expected) {
                    if(job.cancelled.get()) throw IOException("下载已暂停")
                    val n=input.read(data,at,expected-at)
                    if(n < 0) throw IOException("媒体分段不完整")
                    at+=n
                }
                if(input.read() != -1) throw IOException("分段长度超出声明范围")
            }
            return Block(data,end,total)
        } finally { connection.disconnect(); if(job.connection === connection) job.connection=null }
    }

    private fun isUsbStorageFailure(error: Exception): Boolean {
        if (error is MediaHttpException) return false
        if (error is DownloadSpaceBudget.InsufficientSpace) return true
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        return causes.any { cause ->
            cause is android.system.ErrnoException && cause.errno in setOf(
                android.system.OsConstants.ENOSPC, android.system.OsConstants.EROFS,
                android.system.OsConstants.ENODEV, android.system.OsConstants.EIO,
                android.system.OsConstants.EACCES, android.system.OsConstants.ENOENT)
        } || error.message.orEmpty().let { message ->
            message.contains("U盘") || message.contains("歌曲目录") ||
                message.contains("No space left", true) || message.contains("Read-only file system", true)
        }
    }

    private class MediaHttpException(val code: Int, val detail: String): IOException("媒体服务器 HTTP $code: $detail")
    private fun fetch(song: Song, job: Job) {
        val number=song.sourceSongNumber ?: throw IOException("该条目没有新歌源编号，无法下载")
        var address=IgebaApiClient.address(number)
        var addressRefreshes=0
        var addressNeedsProbe=false
        var exclusive=false
        var mediaTotal: Long? = null
        fun probe(): Block {
            val result=block(address.url,0,63,job)
            val range=MediaRangeProtocol.ResponseRange(0,result.end,result.total)
            exclusive=MediaRangeProtocol.exclusiveNode(range)
            if(mediaTotal != null && mediaTotal != result.total) throw IOException("更新地址后媒体长度发生变化")
            if(!result.bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))) {
                throw IOException("歌源返回的不是已验证的 MKV 媒体")
            }
            mediaTotal=result.total
            addressNeedsProbe=false
            return result
        }
        fun refresh(error: MediaHttpException) {
            if(error.code != 403 || !error.detail.contains("expired",true) || addressRefreshes >= 2) throw error
            if(job.cancelled.get()) throw IOException("下载已暂停")
            addressRefreshes++
            addressNeedsProbe=true
            android.util.Log.i("SongOkDownload", "过期地址刷新，歌曲=$number 次数=$addressRefreshes/2")
            address=IgebaApiClient.address(number)
        }
        fun retryExpired(request: () -> Block): Block {
            while (true) {
                try { return request() }
                catch (error: MediaHttpException) { refresh(error) }
            }
        }
        val initialProbe=retryExpired { probe() }
        fun read(start: Long, stopExclusive: Long): Block {
            return retryExpired {
                if (addressNeedsProbe) probe()
                block(address.url,start,MediaRangeProtocol.wireEnd(stopExclusive,exclusive),job)
            }
        }
        song.sourceVoiceChannel=address.voiceChannel
        val directory=checkNotNull(SongStorage.directory(job.destination, true)) { "保存位置不可用" }
        val capacity = SongStorage.capacity(job.destination)
        android.util.Log.i("SongOkDownload", "保存位置 usb=${job.destination.usb} path=${directory.file?.absolutePath ?: directory.document} available=${capacity.available} reserve=${job.destination.reserveBytes} songBytes=${initialProbe.total}")
        val target=directory.child(job.filename)
        val partial=directory.child(job.filename+".download")
        val resume=directory.child(job.filename+".resume.json")
        val metadata=runCatching { JSONObject(resume.readText()) }.getOrNull()
        var offset=partial.info()?.bytes?.coerceAtLeast(0) ?: 0L
        if(metadata?.optLong("bytes") != initialProbe.total || metadata?.optString("SongNumber") != number || offset > initialProbe.total) {
            offset=0
        }
        if(offset > 0) {
            val start=(offset-65_536).coerceAtLeast(0)
            val tail=read(start,offset)
            val previous=partial.readAt(start,(offset-start).toInt())
            if(tail.total != initialProbe.total || !tail.bytes.contentEquals(previous)) offset=0
        }
        resume.writeText(JSONObject().put("SongNumber",number).put("bytes",initialProbe.total).toString())
        partial.writer().use { output ->
            fun checkSpace() {
                try {
                    spaceBudget.reserve(key(song), SongStorage.volumeKey(job.destination),
                        initialProbe.total-offset, output.available(), job.destination.reserveBytes)
                } catch (shortage: DownloadSpaceBudget.InsufficientSpace) {
                    if (!SongStorage.automaticCleanup || resetting || job.cancelled.get()) throw shortage
                    reclaimCache?.invoke(job.destination, shortage.requiredAvailable)
                    if (job.cancelled.get()) throw IOException("下载已暂停")
                    spaceBudget.reserve(key(song), SongStorage.volumeKey(job.destination),
                        initialProbe.total-offset, output.available(), job.destination.reserveBytes)
                }
            }
            if(offset==0L) output.reset()
            checkSpace()
            if(offset==0L) { output.write(initialProbe.bytes); offset=initialProbe.bytes.size.toLong(); checkSpace() }
            output.position(offset)
            while(offset < initialProbe.total) {
                val stop=(offset+CHUNK).coerceAtMost(initialProbe.total)
                val data=read(offset,stop)
                if(data.total != initialProbe.total || data.end != stop-1) throw IOException("媒体长度或分段发生变化")
                checkSpace()
                output.write(data.bytes); offset+=data.bytes.size
                checkSpace()
                job.progress=(offset*100/initialProbe.total).toInt().coerceAtMost(99)
                job.callbacks.forEach { it.onDownloadProgress(song,job.progress) }
            }
            output.sync()
            check(output.size()==initialProbe.total) { "完整文件长度校验失败" }
        }
        if(job.cancelled.get()) throw IOException("下载已暂停")
        check(target.delete()) { "无法替换旧歌曲缓存" }
        val marker=directory.child(job.filename+".complete.json")
        check(marker.delete()) { "无法清理旧歌曲校验文件" }
        check(partial.renameTo(target)) { "无法保存下载文件" }
        if(target is SongStorage.FileMedia) SongFileValidator.forget(target.file)
        if(!SongStorage.valid(target)) { target.delete(); throw IOException("下载媒体容器校验失败") }
        song.path=checkNotNull(target.path); song.downloadUrl=null; song.videoUrl=null
        marker.writeText(JSONObject().put("provider","igeba").put("SongNumber",number)
            .put("bytes",initialProbe.total).put("song",song.toJson()).toString())
        resume.delete()
        PersonalMediaStore.remember(song)
        job.progress=100
        job.callbacks.forEach { it.onDownloadComplete(song,checkNotNull(song.path)) }
    }
}
