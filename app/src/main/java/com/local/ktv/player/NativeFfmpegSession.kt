package com.local.ktv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** Owns one media item. JNI callbacks run on the native demux/audio/video threads. */
internal class NativeFfmpegSession(
    private val context: Context,
    private val audioOnly: Boolean,
    private val executor: Executor,
    private val listener: Listener,
) {
    interface Listener {
        fun prepared(duration: Long, width: Int, height: Int, tracks: Int, video: String, audio: String)
        fun firstFrame()
        fun completed()
        fun error(code: Int, message: String)
    }

    private val audioLock = Any()
    private val handleLock = Any()
    private var handle = nativeCreate(audioOnly)
    @Volatile private var cancelled = false
    @Volatile private var playing = false
    @Volatile private var audioSerial = 0
    private var output: AudioTrack? = null
    private var audioBaseMs = 0L
    private var writtenFrames = 0L
    private var headOffset = 0L
    private var previousHead = 0L
    private var wallPosition = 0L
    private var wallAnchor = SystemClock.elapsedRealtime()
    private var positionFloor = 0L
    @Volatile var nonSilentSamples = 0L
        private set
    @Volatile var leftGain = 1f
    @Volatile var rightGain = 1f
    @Volatile var channel = AudioChannels.AUDIO_CHANNEL_STEREO
    @Volatile var audioTracks = 0
        private set
    @Volatile var duration = 0L
        private set
    @Volatile var prepared = false
        private set
    @Volatile var completed = false
        private set

    fun open(uri: Uri) {
        executor.execute {
            if (cancelled) return@execute
            try {
                if (uri.scheme == "content" || uri.scheme == "android.resource") {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        withHandle { nativeOpen(it, "", descriptor.parcelFileDescriptor.fd, descriptor.startOffset, descriptor.length, "") }
                    } ?: error("无法打开歌曲文件")
                } else {
                    val source = if (uri.scheme == "file") uri.path.orEmpty() else uri.toString()
                    withHandle { nativeOpen(it, source, -1, 0, -1, if (uri.scheme == "https") trustFile(context) else "") }
                }
            } catch (failure: Exception) {
                if (!cancelled) onNativeError(2000, failure.message ?: "无法读取歌曲")
            }
        }
    }

    // Do not hold handleLock during blocking native open: stop must interrupt network/file IO.
    private inline fun withHandle(block: (Long) -> Unit) {
        val current = synchronized(handleLock) { if (cancelled) 0 else handle }
        if (current != 0L) block(current)
    }

    fun setSurface(surface: Surface?) = synchronized(handleLock) {
        if (!cancelled && handle != 0L) nativeSurface(handle, surface)
    }

    fun play(play: Boolean) {
        synchronized(audioLock) {
            if (cancelled) return
            wallPosition = clockLocked()
            wallAnchor = SystemClock.elapsedRealtime()
            playing = play
            if (play) output?.play() else output?.pause()
        }
        synchronized(handleLock) {
            if (!cancelled && handle != 0L) nativePlay(handle, play)
        }
    }

    fun seek(position: Long, audioIndex: Int = -1) {
        if (cancelled) return
        val target = position.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        completed = false
        onNativeAudioReset(-1, target)
        synchronized(handleLock) {
            if (!cancelled && handle != 0L) nativeSeek(handle, target, audioIndex)
        }
    }

    fun setPitch(step: Int) = synchronized(handleLock) {
        if (!cancelled && handle != 0L) nativePitch(handle, step)
    }

    /** Playback-head time, not the number of decoded/queued bytes. */
    fun clockMillis(): Long = synchronized(audioLock) { clockLocked() }

    private fun clockLocked(): Long {
        val track = output
        val value = if (track != null) {
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (head < previousHead) headOffset += 1L shl 32
            previousHead = head
            audioBaseMs + (head + headOffset) * 1000 / SAMPLE_RATE
        } else {
            wallPosition + if (playing && prepared) SystemClock.elapsedRealtime() - wallAnchor else 0
        }
        return value.coerceAtLeast(positionFloor)
    }

    fun onNativePrepared(duration: Long, width: Int, height: Int, tracks: Int, video: String, audio: String) {
        if (cancelled) return
        this.duration = duration
        audioTracks = tracks
        prepared = true
        synchronized(audioLock) { wallAnchor = SystemClock.elapsedRealtime() }
        Log.i("KtvFFmpeg", "Prepared video=$video audio=$audio tracks=$tracks duration=$duration")
        listener.prepared(duration, width, height, tracks, video, audio)
    }

    fun onNativeAudioReset(serial: Int, position: Long) {
        synchronized(audioLock) {
            if (cancelled) return
            audioSerial = serial
            output?.let { it.pause(); it.flush(); it.release() }
            output = null
            writtenFrames = 0
            previousHead = 0
            headOffset = 0
            audioBaseMs = position
            positionFloor = position
            wallPosition = position
            wallAnchor = SystemClock.elapsedRealtime()
        }
    }

    fun onNativePcm(buffer: ByteBuffer, size: Int, ptsMs: Long, serial: Int): Boolean {
        if (cancelled || audioSerial != serial) return false
        try {
            buffer.order(ByteOrder.nativeOrder())
            val samples = buffer.asShortBuffer()
            val left = leftGain
            val right = rightGain
            val mode = channel
            var count = 0L
            var offset = 0
            while (offset + 1 < size / 2) {
                val l = samples.get(offset).toInt()
                val r = samples.get(offset + 1).toInt()
                if (l != 0) count++
                if (r != 0) count++
                val outL = if (mode == AudioChannels.AUDIO_CHANNEL_RIGHT) r else l
                val outR = if (mode == AudioChannels.AUDIO_CHANNEL_LEFT) l else r
                samples.put(offset, (outL * left).toInt().coerceIn(-32768, 32767).toShort())
                samples.put(offset + 1, (outR * right).toInt().coerceIn(-32768, 32767).toShort())
                offset += 2
            }
            nonSilentSamples += count
            buffer.position(0)
            buffer.limit(size)
            while (buffer.hasRemaining() && !cancelled && audioSerial == serial) {
                if (!playing) { Thread.sleep(5); continue }
                val result = synchronized(audioLock) {
                    if (cancelled || audioSerial != serial) return false
                    var track = output
                    if (track == null) {
                        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0) { "音频输出不支持 48kHz PCM" }
                        track = AudioTrack(
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                            AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                            maxOf(minimum, SAMPLE_RATE * 4 / 5), AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE)
                        check(track.state == AudioTrack.STATE_INITIALIZED) { "音频输出初始化失败" }
                        output = track
                        // Use the actual first decoded timestamp, clamped to the requested seek.
                        audioBaseMs = maxOf(positionFloor, ptsMs)
                        track.play()
                    }
                    track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING).also {
                        if (it > 0) writtenFrames += it / 4
                    }
                }
                if (result < 0) error("音频输出失败：$result")
                if (result == 0) Thread.sleep(3)
            }
            return !cancelled && audioSerial == serial
        } catch (failure: Exception) {
            if (!cancelled) onNativeError(5001, failure.message ?: "音频输出失败")
            return false
        }
    }

    fun onNativeAudioEnd() {
        synchronized(audioLock) {
            wallPosition = clockLocked()
            wallAnchor = SystemClock.elapsedRealtime()
            output?.let { it.pause(); it.release() }
            output = null
        }
    }

    fun onNativeFrame() { if (!cancelled) listener.firstFrame() }
    fun onNativeComplete() {
        if (cancelled) return
        completed = true
        play(false)
        listener.completed()
    }
    fun onNativeError(code: Int, message: String) {
        if (cancelled) return
        Log.e("KtvFFmpeg", "Playback error=$code $message")
        listener.error(code, message)
    }

    /** Interrupt first, join/release on the serial control executor, never on the UI thread. */
    fun close() {
        synchronized(handleLock) {
            if (cancelled) return
            cancelled = true
            if (handle != 0L) nativeStop(handle)
        }
        synchronized(audioLock) { output?.pause(); output?.flush() }
        executor.execute {
            val old = synchronized(handleLock) { handle.also { handle = 0 } }
            if (old != 0L) nativeRelease(old)
            synchronized(audioLock) { output?.release(); output = null }
        }
    }

    private external fun nativeCreate(audioOnly: Boolean): Long
    private external fun nativeOpen(handle: Long, url: String, fd: Int, start: Long, length: Long, caFile: String)
    private external fun nativePlay(handle: Long, play: Boolean)
    private external fun nativeSeek(handle: Long, position: Long, audioIndex: Int)
    private external fun nativePitch(handle: Long, step: Int)
    private external fun nativeSurface(handle: Long, surface: Surface?)
    private external fun nativeStop(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        @Volatile private var caFile: String? = null
        @Synchronized private fun trustFile(context: Context): String {
            caFile?.let { return it }
            val store = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = StringBuilder()
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val certificate = store.getCertificate(aliases.nextElement()) ?: continue
                pem.append("-----BEGIN CERTIFICATE-----\n")
                    .append(android.util.Base64.encodeToString(certificate.encoded, android.util.Base64.NO_WRAP))
                    .append("\n-----END CERTIFICATE-----\n")
            }
            check(pem.isNotEmpty()) { "未找到系统可信证书" }
            val file = java.io.File(context.cacheDir, "ffmpeg-trusted-ca.pem")
            file.writeText(pem.toString())
            return file.path.also { caFile = it }
        }
        const val SAMPLE_RATE = 48000
        init { System.loadLibrary("ktvffmpeg") }
    }
}
