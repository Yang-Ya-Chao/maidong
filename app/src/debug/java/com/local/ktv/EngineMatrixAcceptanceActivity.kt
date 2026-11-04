package com.local.ktv

import android.app.Activity
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.local.ktv.player.*
import java.io.File

/** Production engines, real media and explicitly labelled error injection. Debug only. */
class EngineMatrixAcceptanceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: KtvPlaybackEngine
    private lateinit var view: KtvVideoView
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var sample: File
    private var previous = PlaybackDecoderMode.AUTO
    private var failures = 0
    private var errors = 0
    private var prepared = 0
    private var active = true
    private val report = StringBuilder()
    private val modes = listOf(PlaybackDecoderMode.SYSTEM, PlaybackDecoderMode.SOFTWARE, PlaybackDecoderMode.AUTO)
    private var modeIndex = 0

    private fun record(name: String, passed: Boolean, detail: String = "") {
        if (!passed) failures++
        val line = "$name pass=$passed $detail"
        Log.i("EngineMatrix", line)
        report.append(line).append('\n')
        File(getExternalFilesDir(null), "engine-matrix.txt").writeText(report.toString())
        status.text = report.toString().takeLast(1800)
    }
    private fun later(delay: Long = 1500, block: () -> Unit) {
        handler.postDelayed({
            if (active) try { block() } catch (failure: Throwable) {
                record("FATAL", false, failure.toString()); done()
            }
        }, delay)
    }
    private fun until(name: String, timeout: Long = 15000, condition: () -> Boolean, next: () -> Unit) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        fun check() {
            if (!active) return
            if (condition()) next()
            else if (android.os.SystemClock.elapsedRealtime() >= end) {
                record(name, false, "timeout errors=$errors " + engine.decoderSummary.replace('\n', '|'))
                done()
            } else later(250) { check() }
        }
        check()
    }
    private fun backend(): Any = KtvPlaybackEngine::class.java.getDeclaredField("backend")
        .apply { isAccessible = true }.get(engine)!!
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun injectError() {
        val callback = field(backend(), "errorListener") as MediaPlayer.OnErrorListener
        callback.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 4003)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        previous = PlaybackDecoderSettings.load(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        view = KtvVideoView(this)
        status = TextView(this).apply { textSize = 12f }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, 200))
        setContentView(root)
        val dedicated = File(getExternalFilesDir(null), "ffmpeg-validation/h264-aac-dual.mkv")
        val real = File("/storage/emulated/0/MaidongKTV/video/cloud-song/210012312.mkv")
        sample = listOf(dedicated, real).firstOrNull { it.isFile } ?: run {
            record("SAMPLE", false, "no dual-track regression media"); return
        }
        Log.i("EngineMatrix", "SAMPLE " + sample.path)
        startMode()
    }
    private fun create(mode: PlaybackDecoderMode) {
        if (::engine.isInitialized) engine.release()
        PlaybackDecoderSettings.save(this, mode)
        errors = 0; prepared = 0
        engine = KtvPlaybackEngine(this)
        engine.attach(view)
        engine.setOnPreparedListener { prepared++ }
        engine.setOnErrorListener { _, _, _ -> errors++; true }
        engine.setVideoUri(Uri.fromFile(sample))
        engine.start()
    }
    private fun startMode() {
        if (modeIndex >= modes.size) { fallbackTest(); return }
        val mode = modes[modeIndex]
        create(mode)
        until("initial-$mode", condition = { engine.hasRenderedFirstFrame && engine.currentPosition > 1200 }) {
            record("initial-$mode", errors == 0 && engine.renderedVideoFrames > 0 && engine.decodedNonSilentSamples > 0,
                "position=${engine.currentPosition} frames=${engine.renderedVideoFrames} pcm=${engine.decodedNonSilentSamples} " + engine.decoderSummary.replace('\n', '|'))
            engine.pause()
            val paused = engine.currentPosition
            later(700) {
                record("pause-$mode", kotlin.math.abs(engine.currentPosition - paused) < 120)
                val seekTarget = (engine.duration / 4).coerceAtLeast(500)
            engine.seekTo(seekTarget); engine.start()
                later(800) {
                    record("seek-$mode", errors == 0 && engine.currentPosition in seekTarget..(seekTarget + 2500),
                        "position=${engine.currentPosition}")
                    val pcm = engine.decodedNonSilentSamples
                    val selected = engine.selectAudioTrack(false)
                    engine.setTone(3)
                    engine.selectAudioChannel(AudioChannels.AUDIO_CHANNEL_LEFT, 0.5f)
                    later(800) {
                        record("alternate-track-pitch-channel-$mode", selected && errors == 0 &&
                            engine.audioTrackCount() >= 2 && engine.decodedNonSilentSamples > pcm,
                            "tracks=${engine.audioTrackCount()} pcm=${engine.decodedNonSilentSamples}")
                        engine.selectAudioTrack(true)
                        engine.setTone(-3)
                        engine.seekTo(0)
                        later(800) {
                            record("replay-$mode", errors == 0 && engine.currentPosition in 200..4000,
                                "position=${engine.currentPosition}")
                            val replacement = KtvVideoView(this@EngineMatrixAcceptanceActivity)
                            root.removeView(view); root.addView(replacement, 0, LinearLayout.LayoutParams(-1, 0, 1f))
                            view = replacement; engine.attach(view)
                            val position = engine.currentPosition
                            later(800) {
                                record("surface-transfer-$mode", errors == 0 && engine.currentPosition > position)
                                modeIndex++; startMode()
                            }
                        }
                    }
                }
            }
        }
    }
    private fun fallbackTest() {
        create(PlaybackDecoderMode.AUTO)
        until("fallback-initial", condition = { engine.hasRenderedFirstFrame && engine.currentPosition > 1000 }) {
            engine.seekTo((engine.duration / 3).coerceAtLeast(500))
            engine.selectAudioTrack(false)
            engine.setTone(3)
            engine.selectAudioChannel(AudioChannels.AUDIO_CHANNEL_LEFT, 0.6f)
            later(500) {
                engine.pause()
                val savedPosition = engine.currentPosition
                val countPrepared = prepared
                injectError()
                until("injected-fallback", condition = { engine.decoderSummary.startsWith("播放内核：FFmpeg") && engine.duration > 0 }) {
                    later(1000) {
                        val native = backend()
                        record("injected-auto-fallback-preserves-state",
                            errors == 0 && !engine.isPlaying && prepared == countPrepared &&
                                kotlin.math.abs(engine.currentPosition - savedPosition) <= 300 &&
                                field(native, "tone") == 3 && field(native, "channel") == AudioChannels.AUDIO_CHANNEL_LEFT &&
                                field(native, "selectedAudio") == 1 && field(native, "volumeLeft") == 0.6f,
                            "before=$savedPosition after=${engine.currentPosition} prepared=$prepared errors=$errors " + engine.decoderSummary.replace('\n', '|'))
                        engine.start()
                        later(1000) {
                            record("resume-after-fallback", engine.hasRenderedFirstFrame && engine.currentPosition > savedPosition && errors == 0,
                                "position=${engine.currentPosition} saved=$savedPosition frames=${engine.renderedVideoFrames} first=${engine.hasRenderedFirstFrame} errors=$errors")
                            injectError()
                            later(700) {
                                record("native-failure-does-not-loop", errors == 1 && engine.decoderSummary.startsWith("播放内核：FFmpeg"))
                                engine.stop()
                                create(PlaybackDecoderMode.SYSTEM)
                                until("fixed-mode-ready", condition = { engine.hasRenderedFirstFrame }) {
                                    injectError()
                                    later(700) {
                                        record("fixed-exo-does-not-switch", errors == 1 && engine.decoderSummary.startsWith("播放内核：ExoPlayer"))
                                        engine.stop()
                                        create(PlaybackDecoderMode.AUTO)
                                        until("cancel-ready", condition = { engine.hasRenderedFirstFrame }) {
                                            injectError(); engine.stop()
                                            later(700) {
                                                record("stop-cancels-pending-fallback", field(engine, "backend") == null)
                                                unsupportedAudioTest()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun unsupportedAudioTest() {
        val regular = sample
        sample = File(getExternalFilesDir(null), "ffmpeg-validation/h264-mp1-dual.mkv")
        check(sample.isFile)
        create(PlaybackDecoderMode.SYSTEM)
        until("fixed-exo-unsupported-audio", condition = { errors > 0 }) {
            record("fixed-exo-reports-hidden-audio", errors == 1 &&
                engine.decoderSummary.startsWith("播放内核：ExoPlayer"))
            create(PlaybackDecoderMode.AUTO)
            until("natural-mp1-fallback", condition = {
                engine.decoderSummary.startsWith("播放内核：FFmpeg") &&
                    engine.hasRenderedFirstFrame && engine.decodedNonSilentSamples > 0
            }) {
                record("natural-mp1-auto-fallback", errors == 0 && engine.audioTrackCount() == 2,
                    "frames=${engine.renderedVideoFrames} pcm=${engine.decodedNonSilentSamples} " +
                        engine.decoderSummary.replace('\n', '|'))
                val selected = engine.selectAudioTrack(false)
                later(800) {
                    record("mp1-alternate-track", selected && errors == 0)
                    sample = regular
                    create(PlaybackDecoderMode.AUTO)
                    until("next-song-ready", condition = { engine.hasRenderedFirstFrame }) {
                        record("next-song-resets-to-exo", errors == 0 &&
                            engine.decoderSummary.startsWith("播放内核：ExoPlayer"))
                        done()
                    }
                }
            }
        }
    }

    private fun done() {
        if (!active) return
        active = false
        if (::engine.isInitialized) engine.release()
        PlaybackDecoderSettings.save(this, previous)
        Log.i("EngineMatrix", "DONE failures=$failures")
        report.append("DONE failures=$failures\n")
        File(getExternalFilesDir(null), "engine-matrix.txt").writeText(report.toString())
        status.text = report.toString().takeLast(1800)
    }
    override fun onDestroy() {
        if (::engine.isInitialized) engine.release()
        active = false; handler.removeCallbacksAndMessages(null)
        PlaybackDecoderSettings.save(this, previous)
        super.onDestroy()
    }
}
