package com.local.ktv

import android.util.Log
import com.liulishuo.okdownload.DownloadTask as OkDownloadTask
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener2
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object SongOkDownloadManager {
    private const val TAG = "SongDownload"
    const val MIN_VALID_FILE_SIZE = 6L * 1024L * 1024L
    private const val MAX_RETRY_COUNT = 2

    interface DownloadCallback {
        fun onDownloadStart(song: Song)
        fun onDownloadProgress(song: Song, progress: Int)
        fun onDownloadReadyToPlay(song: Song)
        fun onDownloadComplete(song: Song, localPath: String)
        fun onDownloadFailed(song: Song, error: String)
    }

    private val tasks = ConcurrentHashMap<String, OkDownloadTask>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val callbacks = ConcurrentHashMap<String, DownloadCallback>()
    private val progress = ConcurrentHashMap<String, Int>()
    private val totalLengths = ConcurrentHashMap<String, Long>()
    private val io = Executors.newFixedThreadPool(4)

    @JvmStatic
    fun getLocalFile(song: Song): File {
        val fileName = song.filename?.takeIf(String::isNotEmpty) ?: "${song.id}.ts"
        return File("${MuseDatabase.VIDEO_ROOT}/${MuseDatabase.CLOUD_SONG_DIR}", fileName)
    }

    @JvmStatic
    fun isDownloaded(song: Song): Boolean {
        val file = getLocalFile(song)
        if (file.exists() && file.length() < MIN_VALID_FILE_SIZE) {
            Log.w(TAG, "Discarding incomplete song file: ${file.absolutePath}, ${file.length()} bytes")
            file.delete()
            song.path = null
        }
        return (file.exists() && file.length() >= MIN_VALID_FILE_SIZE).also { exists ->
            if (exists && song.path == null) song.path = file.absolutePath
        }
    }

    @JvmStatic
    fun isDownloading(song: Song): Boolean {
        val key = songKey(song)
        return tasks.containsKey(key) || pending.contains(key)
    }

    @JvmStatic
    fun download(song: Song, callback: DownloadCallback?) {
        if (isDownloaded(song)) {
            callback?.onDownloadComplete(song, song.path.orEmpty())
            return
        }
        val key = songKey(song)
        if (tasks.containsKey(key) || !pending.add(key)) {
            callback?.onDownloadStart(song)
            return
        }
        io.execute {
            try {
                val url = buildDownloadUrl(song)
                if (url.isEmpty()) {
                    pending.remove(key)
                    callback?.onDownloadFailed(song, "无法生成下载URL")
                } else {
                    startDownloadTask(song, url, callback, 0)
                }
            } catch (error: Exception) {
                pending.remove(key)
                callback?.onDownloadFailed(song, error.message ?: "下载初始化失败")
            }
        }
    }

    @JvmStatic
    fun cancelDownload(song: Song) {
        val key = songKey(song)
        pending.remove(key)
        tasks.remove(key)?.cancel()
        callbacks.remove(key)
        progress.remove(key)
        totalLengths.remove(key)
    }

    @JvmStatic
    fun getDownloadProgress(song: Song): Int = progress[songKey(song)] ?: 0

    private fun buildDownloadUrl(song: Song, forceRefresh: Boolean = false): String {
        if (!forceRefresh) song.downloadUrl?.takeIf(String::isNotBlank)?.let { return it }
        val musicNo = song.filename?.removeSuffix(".ts")?.removeSuffix(".ls") ?: song.id
        return SongApiClient.getSongDownloadUrl(musicNo).orEmpty().also { url ->
            if (url.isNotEmpty()) song.downloadUrl = url else Log.w(TAG, "无法获取下载URL: ${song.title}")
        }
    }

    private fun startDownloadTask(song: Song, url: String, callback: DownloadCallback?, attempt: Int) {
        val key = songKey(song)
        val target = getLocalFile(song)
        val partial = File(target.parentFile, "${target.name}.download")
        target.parentFile?.mkdirs()
        if (attempt > 0) partial.delete()
        val task = OkDownloadTask.Builder(url, partial.parentFile!!)
            .setFilename(partial.name)
            .setPassIfAlreadyCompleted(false)
            .build()
        tasks[key] = task
        pending.remove(key)
        callback?.let { callbacks[key] = it }
        progress[key] = 0
        totalLengths[key] = 0
        val downloaded = AtomicLong(0)
        var httpResponseCode = 0

        task.enqueue(object : DownloadListener2() {
            override fun taskStart(task: OkDownloadTask) {
                callbacks[key]?.onDownloadStart(song)
            }

            override fun connectEnd(
                task: OkDownloadTask,
                blockCount: Int,
                responseCode: Int,
                responseHeaderFields: MutableMap<String, MutableList<String>>,
            ) {
                httpResponseCode = responseCode
            }

            override fun downloadFromBeginning(task: OkDownloadTask, info: BreakpointInfo, cause: ResumeFailedCause) {
                totalLengths[key] = info.totalLength
            }

            override fun downloadFromBreakpoint(task: OkDownloadTask, info: BreakpointInfo) {
                totalLengths[key] = info.totalLength
                downloaded.set(info.totalOffset)
            }

            override fun fetchProgress(task: OkDownloadTask, blockIndex: Int, increaseBytes: Long) {
                val total = totalLengths[key] ?: return
                if (total <= 0) return
                val value = (downloaded.addAndGet(increaseBytes) * 100 / total).toInt().coerceIn(0, 100)
                progress[key] = value
                callbacks[key]?.let { cb ->
                    cb.onDownloadProgress(song, value)
                }
            }

            override fun taskEnd(task: OkDownloadTask, cause: EndCause, realCause: Exception?) {
                tasks.remove(key)
                progress.remove(key)
                totalLengths.remove(key)
                val cb = callbacks.remove(key)
                if (cause != EndCause.COMPLETED) {
                    val retryable = httpResponseCode == 403 || realCause?.message?.contains("403") == true
                    if (retryable && attempt < MAX_RETRY_COUNT) {
                        retry(song, cb, attempt + 1, "HTTP 403")
                        return
                    }
                    cb?.onDownloadFailed(song, cause.name + (realCause?.message?.let { ": $it" } ?: ""))
                    return
                }
                var downloadedFile = task.file ?: partial
                if (TsDecryptor.isEncrypted(downloadedFile)) {
                    val decrypted = File(downloadedFile.parentFile, "${downloadedFile.name}.decrypted")
                    if (TsDecryptor.decryptFile(downloadedFile, decrypted)) {
                        if (downloadedFile.delete() && decrypted.renameTo(downloadedFile)) {
                            downloadedFile = task.file ?: partial
                        }
                    }
                }
                if (!downloadedFile.exists() || downloadedFile.length() < MIN_VALID_FILE_SIZE) {
                    val size = downloadedFile.takeIf(File::exists)?.length() ?: 0L
                    downloadedFile.delete()
                    if (attempt < MAX_RETRY_COUNT) {
                        retry(song, cb, attempt + 1, "file too small: $size")
                    } else {
                        cb?.onDownloadFailed(song, "下载文件小于6MB，已丢弃")
                    }
                    return
                }
                if (target.exists() && !target.delete()) {
                    cb?.onDownloadFailed(song, "无法替换旧的歌曲文件")
                    return
                }
                if (!downloadedFile.renameTo(target)) {
                    cb?.onDownloadFailed(song, "下载文件落盘失败")
                    return
                }
                song.path = target.absolutePath
                cb?.onDownloadComplete(song, target.absolutePath)
            }
        })
    }

    private fun retry(song: Song, callback: DownloadCallback?, attempt: Int, reason: String) {
        Log.w(TAG, "Retrying ${song.title}, attempt=$attempt, reason=$reason")
        val key = songKey(song)
        pending.add(key)
        song.downloadUrl = null
        SongApiClient.clearTokenCache()
        io.execute {
            val url = runCatching { buildDownloadUrl(song, forceRefresh = true) }.getOrDefault("")
            if (url.isEmpty()) {
                pending.remove(key)
                callback?.onDownloadFailed(song, "刷新下载地址失败")
            } else {
                startDownloadTask(song, url, callback, attempt)
            }
        }
    }

    private fun songKey(song: Song): String = song.id?.takeIf(String::isNotEmpty)
        ?: song.filename?.takeIf(String::isNotEmpty)
        ?: song.title.orEmpty()
}
