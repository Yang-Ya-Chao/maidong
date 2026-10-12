package com.local.ktv

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** File and SAF access share one direct-write path; no internal-disk staging for USB. */
internal object SongStorage {
    data class Choice(val usb: Boolean = false, val tree: String = "", val reserveBytes: Long = 1_073_741_824L)
    data class Info(val bytes: Long, val modified: Long, val directory: Boolean, val flags: Int = 0)
    @Volatile private var selected = Choice()
    @Volatile var includeUsb = true
    @Volatile var automaticCleanup = true
    @Volatile var usbScanError: String? = null
    private val resolver get() = KtvApplication.getInstance().contentResolver
    private const val USB_CACHE = "MaidongKTV-downloads"
    private const val EXTERNAL_PROVIDER = "com.android.externalstorage.documents"
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
    )

    fun configure(usb: Boolean, tree: String, reserveGb: Double, showUsbSongs: Boolean = true,
                  autoDelete: Boolean = true) {
        automaticCleanup = autoDelete
        includeUsb = showUsbSongs
        selected = Choice(usb, tree, (reserveGb.coerceIn(0.5, 64.0) * 1_073_741_824.0).toLong())
    }
    /** 本地默认；U盘设置只表示偏好，拔出后实际保存位置回退本地。 */
    fun choice(): Choice {
        val preferred = selected
        if (!preferred.usb) return preferred
        val uri = Uri.parse(preferred.tree)
        val roots = usbRoots()
        if (uri.scheme == "content") {
            val root = roots.firstOrNull() ?: return preferred.copy(usb = false, tree = "")
            return preferred.copy(tree = Uri.fromFile(root).toString())
        }
        if (roots.isEmpty()) return preferred.copy(usb = false, tree = "")
        val saved = uri.path?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        val root = roots.firstOrNull { it == saved } ?: return preferred.copy(usb = false, tree = "")
        return preferred.copy(tree = Uri.fromFile(root).toString())
    }

    /** 下载开始前验证目录及实际写入权限；不修改用户保存的偏好。 */
    fun prepareDownloadChoice(): Choice {
        val effective = choice()
        if (!effective.usb) {
            directory(effective, true)
            return effective
        }
        return runCatching {
            if (Uri.parse(effective.tree).scheme == "file") {
                val cache = checkNotNull(directory(effective, true)?.file)
                val probe = File.createTempFile(".maidong-write-", ".tmp", cache)
                try { RandomAccessFile(probe, "rw").use { it.write(1); it.fd.sync() } }
                finally { check(probe.delete()) { "U盘写入检查文件清理失败" } }
            } else {
                validateUsbTree(Uri.parse(effective.tree), true)
                directory(effective, true)
            }
            effective
        }.getOrElse {
            android.util.Log.w("SongStorage", "U盘不可写，回退本地：${it.message}")
            effective.copy(usb = false, tree = "").also { local -> directory(local, true) }
        }
    }

    /** 自动发现系统挂载的可移动卷；文件夹授权路径不依赖该列表。 */
    fun usbRoots(): List<File> {
        val app = KtvApplication.getInstance()
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        val roots = linkedSetOf<File>()
        fun add(candidate: File) {
            runCatching {
                val folder = candidate.canonicalFile
                if (primary != null && (folder == primary ||
                        folder.path.startsWith(primary.path + File.separator))) return@runCatching
                if (folder.isDirectory && folder.canRead() && folder.listFiles() != null) roots += folder
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val manager = app.getSystemService(android.os.storage.StorageManager::class.java)
                manager?.storageVolumes?.filter { it.isRemovable &&
                    it.state in setOf(android.os.Environment.MEDIA_MOUNTED, android.os.Environment.MEDIA_MOUNTED_READ_ONLY) }
                    ?.mapNotNull { it.directory }?.forEach(::add)
            }
        }
        runCatching {
            app.getExternalFilesDirs(null).filterNotNull().forEach { folder ->
                if (runCatching { android.os.Environment.isExternalStorageRemovable(folder) }.getOrDefault(false)) {
                    val path = folder.absolutePath.substringBefore("/Android/")
                    if (path != folder.absolutePath) add(File(path))
                }
            }
        }
        // 较旧系统或厂家卷未出现在应用专属目录列表时，补充进程可见挂载点。
        runCatching { File("/proc/self/mountinfo").readText() }.getOrNull()?.let { mounts ->
            UsbMountDiscovery.candidates(mounts).forEach { add(File(it)) }
        }
        return roots.filter { folder ->
            !folder.path.startsWith("/mnt/media_rw/") ||
                roots.none { it.path.startsWith("/storage/") && it.name == folder.name }
        }.sortedBy { it.absolutePath }
    }

    private fun directUsbRoot(uri: Uri): File {
        check(uri.scheme == "file" && !uri.path.isNullOrBlank()) { "U盘存储位置无效" }
        val root = File(checkNotNull(uri.path)).canonicalFile
        check(usbRoots().any { it == root }) { "U盘未连接，请插入后重试" }
        return root
    }

    private fun usbCacheFolder(root: File): File {
        val cache = UsbMountDiscovery.songDirectory(root)
        check(cache.path.startsWith(root.canonicalPath + File.separator)) { "U盘歌曲目录无效" }
        return cache
    }

    fun detectUsbRoot(writable: Boolean = true, preferredRoot: String? = null): String {
        val roots = usbRoots()
        android.util.Log.i("SongStorage", "USB detection sdk=${android.os.Build.VERSION.SDK_INT} roots=${roots.map { it.absolutePath }}")
        check(roots.isNotEmpty()) { "未检测到U盘，请插入后重试" }
        val current = runCatching { directUsbRoot(Uri.parse(selected.tree)) }.getOrNull()
        val candidates = if (preferredRoot != null) roots.filter { it.absolutePath == preferredRoot }
            else if (current != null) listOf(current) + roots.filter { it != current } else roots
        check(candidates.isNotEmpty()) { "所选U盘已拔出，请重新选择" }
        var lastError: Throwable? = null
        candidates.forEach { root ->
            try {
                check(root.canRead() && root.listFiles() != null) { "无法读取U盘，请检查存储权限" }
                if (writable) {
                    val cache = usbCacheFolder(root)
                    check(cache.isDirectory || cache.mkdirs()) { "无法创建U盘 maidongktv 目录，请检查写入权限或只读状态" }
                    val probe = File.createTempFile(".maidong-write-", ".tmp", cache)
                    try {
                        RandomAccessFile(probe, "rw").use { file -> file.write(1); file.fd.sync() }
                    } finally { check(probe.delete()) { "无法清理U盘写入检查文件" } }
                }
                return Uri.fromFile(root).toString()
            } catch (error: Exception) { lastError = error }
        }
        throw java.io.IOException(lastError?.message ?: "U盘不可用", lastError)
    }

    fun validateUsbTree(uri: Uri, writable: Boolean = true) {
        if (uri.scheme == "file") {
            val root = directUsbRoot(uri)
            check(root.canRead()) { "U盘不可读，请检查存储权限" }
            if (writable) check(usbCacheFolder(root).canWrite()) { "U盘歌曲目录不可写，请重新选择U盘存储" }
            return
        }
        check(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择U盘中的歌曲文件夹" }
        check(UsbDocumentPolicy.acceptsFolder(uri.authority, DocumentsContract.getTreeDocumentId(uri))) {
            "请选择U盘文件夹，不能选择本机存储"
        }
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        check(permission?.isReadPermission == true && (!writable || permission.isWritePermission)) { "U盘目录授权已失效，请重新选择文件夹" }
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val info = checkNotNull(DocumentMedia(root, "", null).info()) { "U盘未连接或目录不可用" }
        check(info.directory) { "U盘歌曲路径不是文件夹" }
        if (writable) check(info.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "U盘目录不可写，请检查只读状态" }
    }

    fun persistUsbTree(uri: Uri, flags: Int, writable: Boolean = true) {
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(granted and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "没有获得U盘读取权限" }
        resolver.takePersistableUriPermission(uri, granted)
        validateUsbTree(uri, writable)
    }

    fun directory(choice: Choice = choice(), create: Boolean = false): Directory? {
        if (!choice.usb) {
            if (create) check(AppPaths.cloudSongsDir.exists() || AppPaths.cloudSongsDir.mkdirs()) { "无法创建本地歌曲缓存目录" }
            return Directory(file = AppPaths.cloudSongsDir)
        }
        check(choice.tree.isNotBlank()) { "请先在设置中选择U盘歌曲文件夹" }
        val tree = Uri.parse(choice.tree)
        if (tree.scheme == "file") {
            val root = directUsbRoot(tree)
            val cache = usbCacheFolder(root)
            if (create) check(cache.isDirectory || cache.mkdirs()) { "无法创建U盘 maidongktv 歌曲目录" }
            return Directory(file = cache)
        }
        validateUsbTree(tree, create)
        val root = Directory(document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        var folder = root.entries().firstOrNull { it.name == USB_CACHE }
        if (folder == null && create) {
            val uri = checkNotNull(DocumentsContract.createDocument(resolver, root.document!!,
                DocumentsContract.Document.MIME_TYPE_DIR, USB_CACHE)) { "无法创建U盘歌曲缓存目录" }
            folder = DocumentMedia(uri, USB_CACHE, root.document)
        }
        if (folder == null) return null
        check(folder.info()?.directory == true) { "U盘歌曲缓存路径不是文件夹" }
        return Directory(document = Uri.parse(checkNotNull(folder.path)))
    }

    data class Capacity(val available: Long, val total: Long)
    fun capacity(choice: Choice = choice()): Capacity {
        val root = if (choice.usb) {
            val tree = Uri.parse(choice.tree)
            if (tree.scheme == "file") directUsbRoot(tree) else {
                validateUsbTree(tree, false)
                // 厂家/插件提供器没有 /storage/卷标 映射，通过实际写入句柄检查容量。
                if (tree.authority != EXTERNAL_PROVIDER) return documentCapacity(choice)
                check(choice.tree.isNotBlank() && DocumentsContract.isTreeUri(tree)) { "U盘目录无效" }
                val volume = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
                check(volume.isNotBlank() && volume.lowercase() !in setOf("primary", "home", "downloads") &&
                    '/' !in volume && '\\' !in volume) { "U盘卷标无效" }
                File("/storage", volume)
            }
        } else AppPaths.cloudSongsDir
        val existing = generateSequence(root) { it.parentFile }.firstOrNull { it.exists() }
            ?: error("保存位置不可用")
        if (choice.usb && (existing != root || !root.isDirectory)) return documentCapacity(choice)
        val stats = Os.statvfs(existing.absolutePath)
        val unit = stats.f_frsize
        check(unit > 0 && stats.f_blocks <= Long.MAX_VALUE / unit &&
            stats.f_bavail <= Long.MAX_VALUE / unit) { "保存位置容量无效" }
        return Capacity(stats.f_bavail * unit, stats.f_blocks * unit)
    }

    private fun documentCapacity(choice: Choice): Capacity {
        val directory = checkNotNull(directory(choice, true)) { "U盘歌曲目录不可用" }
        val probe = directory.child(".maidong-capacity-${java.util.UUID.randomUUID()}.tmp")
        try {
            return probe.writer().use { it.capacity() }
        } finally {
            check(probe.delete()) { "U盘容量检查文件清理失败" }
        }
    }

    private fun descriptorCapacity(stats: android.system.StructStatVfs): Capacity {
        val unit = stats.f_frsize
        check(unit > 0 && stats.f_blocks > 0 && stats.f_blocks <= Long.MAX_VALUE / unit &&
            stats.f_bavail >= 0 && stats.f_bavail <= Long.MAX_VALUE / unit) { "U盘容量信息无效" }
        return Capacity(stats.f_bavail * unit, stats.f_blocks * unit)
    }

    fun volumeKey(choice: Choice): String = if (choice.usb) {
        val tree = Uri.parse(choice.tree)
        if (tree.scheme == "file") "usb:" + directUsbRoot(tree).absolutePath
        else "usb:${tree.authority}:" + if (tree.authority == EXTERNAL_PROVIDER)
            DocumentsContract.getTreeDocumentId(tree).substringBefore(':').lowercase()
        else "provider" // 无法推断物理卷时，同一提供器共享预算，避免重复预留空间。
    } else "local"

    class Directory(val file: File? = null, val document: Uri? = null) {
        fun child(name: String): Media {
            check(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "歌曲文件名无效" }
            return if (file != null) FileMedia(File(file, name)) else DocumentMedia(null, name, checkNotNull(document))
        }
        fun entries(): List<Media> {
            file?.let { root ->
                if (!root.exists()) return emptyList()
                return checkNotNull(root.listFiles()) { "无法读取歌曲文件夹" }.map(::FileMedia)
            }
            val parent = checkNotNull(document)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            val result = ArrayList<Media>()
            checkNotNull(resolver.query(children, columns, null, null, null)) { "无法读取U盘歌曲文件夹" }.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    result += DocumentMedia(DocumentsContract.buildDocumentUriUsingTree(parent, id), cursor.getString(1), parent)
                }
            }
            return result
        }
    }

    sealed class Media {
        abstract val name: String
        abstract val path: String?
        abstract fun info(): Info?
        abstract fun input(): InputStream
        abstract fun writer(): Output
        abstract fun delete(): Boolean
        abstract fun renameTo(target: Media): Boolean
        fun readText(): String = input().bufferedReader(Charsets.UTF_8).use { it.readText() }
        fun writeText(value: String) = writer().use { output ->
            output.reset()
            output.write(value.toByteArray(Charsets.UTF_8))
            output.sync()
        }
        fun readAt(offset: Long, count: Int): ByteArray = input().use { stream ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else { check(stream.read() >= 0) { "本地媒体长度不足" }; remaining-- }
            }
            val bytes = ByteArray(count)
            var at = 0
            while (at < count) {
                val n = stream.read(bytes, at, count - at)
                check(n > 0) { "本地媒体读取不完整" }
                at += n
            }
            bytes
        }
    }

    class FileMedia(val file: File) : Media() {
        override val name get() = file.name
        override val path get() = file.absolutePath
        override fun info(): Info? = if (file.exists()) Info(file.length(), file.lastModified(), file.isDirectory) else null
        override fun input(): InputStream = file.inputStream()
        override fun writer(): Output {
            check(file.parentFile?.let { it.exists() || it.mkdirs() } == true) { "无法创建歌曲目录" }
            val handle = RandomAccessFile(file, "rw")
            return Output(handle.channel, handle) {
                descriptorCapacity(Os.fstatvfs(handle.fd))
            }
        }
        override fun delete(): Boolean = !file.exists() || file.delete()
        override fun renameTo(target: Media): Boolean = target is FileMedia && file.renameTo(target.file)
    }

    class DocumentMedia(private var uri: Uri?, override val name: String, private val parent: Uri?) : Media() {
        private fun resolve(create: Boolean = false): Uri? {
            if (uri != null) return uri
            val folder = parent ?: return null
            val match = Directory(document = folder).entries().filterIsInstance<DocumentMedia>().firstOrNull { it.name == name }
            uri = match?.uri
            if (uri == null && create) uri = DocumentsContract.createDocument(resolver, folder,
                if (name.endsWith(".mkv", true)) "video/x-matroska" else "application/octet-stream", name)
            return uri
        }
        override val path get() = resolve()?.toString()
        override fun info(): Info? {
            val document = resolve() ?: return null
            return resolver.query(document, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else Info(
                    if (cursor.isNull(2)) -1L else cursor.getLong(2),
                    if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    cursor.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR,
                    cursor.getInt(5),
                )
            }
        }
        override fun input(): InputStream = checkNotNull(resolver.openInputStream(checkNotNull(resolve()))) { "U盘歌曲不可读" }
        override fun writer(): Output {
            val descriptor = checkNotNull(resolver.openFileDescriptor(checkNotNull(resolve(true)) { "无法创建U盘歌曲文件" }, "rw")) { "U盘歌曲不可写" }
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                stream.channel.position(0) // Reject a pipe-backed provider: resume must be seekable.
                val fd = descriptor.fileDescriptor
                return Output(stream.channel, stream) {
                    descriptorCapacity(Os.fstatvfs(fd))
                }
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        }
        override fun delete(): Boolean {
            val document = resolve() ?: return true
            val deleted = DocumentsContract.deleteDocument(resolver, document)
            if (deleted) uri = null
            return deleted
        }
        override fun renameTo(target: Media): Boolean {
            if (target !is DocumentMedia || parent != target.parent) return false
            val renamed = DocumentsContract.renameDocument(resolver, checkNotNull(resolve()), target.name) ?: return false
            target.uri = renamed
            uri = null
            return true
        }
    }

    class Output(private val channel: FileChannel, private val owner: Closeable,
                 private val capacityReader: () -> Capacity) : Closeable {
        fun capacity(): Capacity = capacityReader()
        fun available(): Long = capacity().available
        fun position(offset: Long) { channel.position(offset) }
        fun reset() { channel.truncate(0); channel.position(0) }
        fun size(): Long = channel.size()
        fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "歌曲文件写入中断" }
        }
        fun sync() { channel.force(true) }
        override fun close() { owner.close() }
    }

    fun valid(media: Media): Boolean = runCatching {
        if (media is FileMedia) return@runCatching SongFileValidator.inspect(media.file, SongFileValidator.requiresTransportStream(media.file)).valid
        val info = media.info() ?: return@runCatching false
        if (info.directory) return@runCatching false
        val header = media.readAt(0, SongFileValidator.MIN_VALID_FILE_SIZE.toInt())
        UsbDocumentPolicy.validHeader(header, info.bytes)
    }.getOrDefault(false)

    fun fromPath(path: String): Media = if (path.startsWith("content://")) {
        val uri = Uri.parse(path)
        val name = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else ""
        }.orEmpty()
        DocumentMedia(uri, name, null)
    } else FileMedia(File(path))

    fun usbMedia(): List<Media> {
        val result = ArrayList<Media>()
        val failures = ArrayList<String>()
        usbRoots().forEach { root ->
            runCatching { UsbMountDiscovery.songs(root) }.onSuccess { files ->
                result += files.map(::FileMedia)
            }.onFailure {
                failures += it.message ?: "U盘歌曲扫描失败"
                android.util.Log.w("SongStorage", "U盘保存目录扫描失败", it)
            }
        }
        if (failures.isNotEmpty()) usbScanError = failures.distinct().joinToString("；")
        return result.distinctBy { it.path }
    }
}
