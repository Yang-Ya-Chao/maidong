package com.local.ktv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.local.ktv.player.*
import java.io.File
import org.json.JSONObject

/** Exercises the production FFmpeg engine with local regression files; never used in release. */
class DecoderTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: KtvPlaybackEngine
    private lateinit var root: LinearLayout
    private lateinit var view: KtvVideoView
    private lateinit var status: TextView
    private lateinit var directory: File
    private val report = StringBuilder()
    private val sampleNames = setOf("rv40-aac-dual.mkv", "h264-mp1-dual.mkv", "h264-aac-dual.mkv", "aac-sbr.mkv", "h264-ac3.mkv", "h264-eac3.mkv", "h264-mp3.mkv", "vorbis-multiaudio.mkv")
    private var samples = emptyList<File>()
    private val decodedDurations = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var index = 0
    private var error = 0
    private var complete = false
    private var failures = 0
    private var sampleFailed = false
    private var positionBeforePause = 0
    private var seekTarget = 0
    private var pcmBeforeSwitch = 0L
    private var framesBeforeSeek = 0L
    private var run = 0
    private var destroyed = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(-16777216) }
        status = TextView(this).apply { textSize = 16f; setTextColor(-1) }
        view = KtvVideoView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, 180))
        setContentView(root)
        directory = File(getExternalFilesDir(null), "ffmpeg-validation").apply { mkdirs() }
        engine = KtvPlaybackEngine(this)
        engine.attach(view)
        engine.setOnErrorListener { _, _, code -> error = code; true }
        engine.setOnCompletionListener { complete = true }
        Thread {
            try {
                samples = assets.list("decoder-samples").orEmpty().filter { it in sampleNames }.sorted().map { name ->
                    File(directory, name).also { output ->
                        assets.open("decoder-samples/$name").use { input -> output.outputStream().use { input.copyTo(it) } }
                    }
                }
                if (samples.isEmpty()) samples = directory.listFiles().orEmpty().filter { it.name in sampleNames }.sortedBy { it.name }
                check(samples.isNotEmpty()) { "未找到解码回归样本" }
                for (sample in samples) {
                    if (destroyed) return@Thread
                    val nativeResult = FfmpegValidation.checkFile(sample.path)
                    val result = JSONObject(nativeResult)
                    decodedDurations[sample.name] = result.getLong("decodedDurationMs").toInt()
                    val passed = result.getInt("errors") == 0 &&
                        result.getLong("videoFrames") > 0 && result.getLong("audioSamples") > 0 &&
                        result.getInt("audioTracks") > 0
                    handler.post {
                        if (!passed) failures++
                        record("FULL file=${sample.name} pass=$passed $nativeResult")
                    }
                }
                handler.post { nextSample() }
            } catch (failure: Throwable) {
                handler.post { failures++; record("FATAL $failure"); record("DONE failures=$failures") }
            }
        }.start()
    }

    private fun record(text: String) {
        Log.i("DecoderMatrix", text)
        report.append(text).append('\n')
        File(directory, "results.txt").writeText(report.toString())
        status.text = text
    }
    private fun checkResult(name: String, passed: Boolean, details: String = "") {
        if (!passed) { failures++; sampleFailed = true }
        record("CHECK file=${samples[index].name} $name pass=$passed $details")
    }
    private fun later(delay: Long, action: () -> Unit) {
        val generation = run
        handler.postDelayed({ if (!destroyed && generation == run) action() }, delay)
    }
    private fun nextSample() {
        if (destroyed) return
        if (index >= samples.size) {
            engine.stop()
            record("DONE samples=${samples.size} failures=$failures")
            return
        }
        ++run
        error = 0; complete = false; sampleFailed = false
        engine.stop()
        engine.setVideoUri(Uri.fromFile(samples[index]))
        engine.start()
        record("START file=${samples[index].name}")
        later(5000) {
            checkResult("initial", error == 0 && engine.hasRenderedFirstFrame &&
                engine.decodedNonSilentSamples > 0 && engine.currentPosition > 500,
                "error=$error position=${engine.currentPosition} tracks=${engine.audioTrackCount()} " +
                    "pcm=${engine.decodedNonSilentSamples} ${engine.decoderSummary.replace('\n', '|')}")
            engine.pause()
            positionBeforePause = engine.currentPosition
            later(700) {
                checkResult("pause", kotlin.math.abs(engine.currentPosition - positionBeforePause) < 100)
                seekTarget = minOf(60000, (decodedDurations[samples[index].name] ?: engine.duration) / 3)
                framesBeforeSeek = engine.renderedVideoFrames
                engine.seekTo(seekTarget)
                engine.start()
                later(2500) {
                    checkResult("seek", error == 0 && engine.currentPosition in seekTarget..(seekTarget + 5000) && engine.renderedVideoFrames > framesBeforeSeek,
                        "target=$seekTarget actual=${engine.currentPosition}")
                    pcmBeforeSwitch = engine.decodedNonSilentSamples
                    checkResult("select-second-track", engine.selectAudioTrack(false) == (engine.audioTrackCount() >= 2))
                    engine.setTone(3)
                    engine.selectAudioChannel(AudioChannels.AUDIO_CHANNEL_LEFT, 0.5f)
                    later(1800) {
                        checkResult("audio-switch-and-pitch", error == 0 && engine.decodedNonSilentSamples > pcmBeforeSwitch)
                        engine.setTone(-3)
                        val replacement = KtvVideoView(this)
                        root.removeView(view)
                        root.addView(replacement, 0, LinearLayout.LayoutParams(-1, 0, 1f))
                        view = replacement
                        engine.attach(view)
                        val before = engine.currentPosition
                        later(1000) {
                            checkResult("surface-transfer", error == 0 && (engine.currentPosition > before || complete))
                            engine.setTone(0)
                            engine.seekTo(((decodedDurations[samples[index].name] ?: engine.duration) - 1700).coerceAtLeast(0))
                            engine.start()
                            complete = false
                            later(4500) {
                                checkResult("completion", error == 0 && complete, "error=$error position=${engine.currentPosition}")
                                record("RESULT file=${samples[index].name} pass=${!sampleFailed}")
                                index++
                                later(200) { nextSample() }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        engine.release()
        super.onDestroy()
    }
}

internal object FfmpegValidation {
    init { System.loadLibrary("ktvffmpeg") }
    external fun checkFile(path: String): String
}
