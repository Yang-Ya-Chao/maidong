package com.local.ktv

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import musethunderdecrypt.ThunderDecrypt

object TsDecryptor {
    private const val TAG = "TsDecryptor"
    private const val HEADER_SIZE = 512
    private const val READ_SIZE = 1024 * 1024
    private val signatures = listOf("THUNDERCRYP3", "HHCMUSECRYP1", "HHCMUSECRYP2")
        .map { it.toByteArray(Charsets.US_ASCII) }

    @JvmStatic
    fun isEncrypted(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() < HEADER_SIZE) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                if (input.readByte() != 0x47.toByte()) return@use true
                val tail = ByteArray(12)
                input.seek(file.length() - tail.size)
                input.readFully(tail)
                signatures.any { tail.contentEquals(it) }
            }
        }.getOrDefault(false)
    }

    /** Uses the original app's segmented Thunder decryptor and offset semantics. */
    @JvmStatic
    fun decryptFile(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists()) return false
        return try {
            RandomAccessFile(inputFile, "r").use { input ->
                outputFile.outputStream().buffered().use { output ->
                    val header = ByteArray(HEADER_SIZE)
                    if (input.read(header) != HEADER_SIZE) error("Encrypted header is incomplete")
                    val decryptor = ThunderDecrypt().apply { setDecryptInfoBlock(header) }
                    val buffer = ByteArray(READ_SIZE)
                    var offset = HEADER_SIZE.toLong()
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val block = if (count == buffer.size) buffer else buffer.copyOf(count)
                        val decrypted = decryptor.decryptBufMultiple(block, count.toLong(), offset)
                        output.write(decrypted, 0, minOf(count, decrypted.size))
                        offset += count
                    }
                }
            }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "TS decrypt failed: ${inputFile.absolutePath}", error)
            outputFile.delete()
            false
        }
    }
}
