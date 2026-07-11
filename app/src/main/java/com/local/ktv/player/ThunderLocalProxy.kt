package com.local.ktv.player

import com.local.ktv.TsDecryptor
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import musethunderdecrypt.ThunderDecrypt

object ThunderLocalProxy {
    private const val HEADER_SIZE = 512L
    private const val SEGMENT_SIZE = 8192L
    private const val BUFFER_SIZE = 1024 * 1024
    private val files = ConcurrentHashMap<String, File>()
    private val workers = Executors.newCachedThreadPool()
    private val server by lazy {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { socket ->
            workers.execute {
                while (!socket.isClosed) runCatching { socket.accept() }
                    .onSuccess { client -> workers.execute { runCatching { serve(client) } } }
            }
        }
    }

    fun urlFor(file: File): String {
        if (!TsDecryptor.isEncrypted(file)) return file.absolutePath
        val token = UUID.randomUUID().toString()
        files[token] = file
        return "http://127.0.0.1:${server.localPort}/$token"
    }

    private fun serve(socket: Socket) = socket.use { client ->
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val request = reader.readLine() ?: return@use
        val token = request.split(' ').getOrNull(1)?.trim('/') ?: return@use
        var rangeStart = 0L
        var line: String? = null
        while (reader.readLine().also { line = it }?.isNotEmpty() == true) {
            if (line!!.startsWith("Range:", true)) {
                rangeStart = Regex("bytes=(\\d+)").find(line!!)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
        }
        val file = files[token] ?: return@use
        val mediaLength = (file.length() - HEADER_SIZE).coerceAtLeast(0)
        val start = rangeStart.coerceIn(0, mediaLength)
        val output = client.getOutputStream().buffered()
        val partial = start > 0
        output.write((if (partial) {
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${mediaLength - 1}/$mediaLength\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n"
        }).toByteArray(Charsets.US_ASCII))
        output.write("Accept-Ranges: bytes\r\nContent-Type: video/mp2t\r\nContent-Length: ${mediaLength - start}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))

        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(HEADER_SIZE.toInt())
            input.readFully(header)
            val decryptor = ThunderDecrypt().apply { setDecryptInfoBlock(header) }
            val alignedStart = start / SEGMENT_SIZE * SEGMENT_SIZE
            var fileOffset = HEADER_SIZE + alignedStart
            var discard = (start - alignedStart).toInt()
            input.seek(fileOffset)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                val block = if (count == buffer.size) buffer else buffer.copyOf(count)
                val decrypted = decryptor.decryptBufMultiple(block, count.toLong(), fileOffset)
                val available = minOf(count, decrypted.size)
                if (discard < available) output.write(decrypted, discard, available - discard)
                discard = (discard - available).coerceAtLeast(0)
                fileOffset += count
            }
        }
        output.flush()
    }
}
