package com.local.ktv

/** 文件提供器的文档编号是 opaque ID，不当作卷标或本地路径解析。 */
internal object UsbDocumentPolicy {
    private const val EXTERNAL_PROVIDER = "com.android.externalstorage.documents"

    fun acceptsFolder(authority: String?, documentId: String): Boolean {
        if (authority.isNullOrBlank() || documentId.isBlank()) return false
        return authority != EXTERNAL_PROVIDER ||
            documentId.substringBefore(':').lowercase() !in setOf("primary", "home", "downloads")
    }

    fun validHeader(header: ByteArray, declaredSize: Long): Boolean {
        val minimum = SongFileValidator.MIN_VALID_FILE_SIZE
        if (declaredSize >= 0 && declaredSize < minimum || header.size < minimum) return false
        return header.take(4).toByteArray().contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())) ||
            String(header, 4, 4, Charsets.US_ASCII) == "ftyp" ||
            String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
            String(header, 0, 4, Charsets.US_ASCII) in listOf("fLaC", "RIFF", "OggS") ||
            (header[0].toInt() and 0xff == 0xff && header[1].toInt() and 0xe0 == 0xe0)
    }
}
