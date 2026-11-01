package com.local.ktv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.SeekBar
import com.local.ktv.player.KtvPlaybackEngine
import com.local.ktv.player.KtvVideoView
import java.io.File

/** Runs the actual fullscreen SeekBar and vocal switch in MainActivity. Debug only. */
class DualPlayerSeekTestActivity : Activity(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var failures = 0
    private lateinit var target: MainActivity
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
    private fun record(name: String, passed: Boolean, detail: String = "") {
        if (!passed) failures++
        Log.i("DualSeekRegression", "$name pass=$passed $detail")
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        application.registerActivityLifecycleCallbacks(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity || started) return
        started = true
        target = activity
        application.unregisterActivityLifecycleCallbacks(this)
        handler.postDelayed({ runCatching { startSample() }.onFailure { Log.e("DualSeekRegression", "FATAL", it) } }, 1000)
    }
    private fun later(action: () -> Unit) {
        handler.postDelayed({ runCatching(action).onFailure { Log.e("DualSeekRegression", "FATAL", it) } }, 1200)
    }
    private fun mainPlayer() = field("player") as KtvVideoView
    private fun alternate() = field("embeddedVocalEngine") as KtvPlaybackEngine
    private fun startSample() {
        val file = File(getExternalFilesDir(null), "ffmpeg-validation/h264-mp1-dual.mkv")
        check(file.isFile) { "缺少双音轨回归样本" }
        set("vocalChannelMode", "自动")
        set("originalVocal", true)
        set("audioDelayMs", 0)
        set("musicVolume", 70)
        target.playSong(Song.local(file.path, file.name))
        handler.postDelayed({
            runCatching {
                check(field("vocalPlayerPrepared") == true && field("embeddedDualTrackMode") == true)
                target.findViewById<android.view.View>(R.id.btn_top_vocal).performClick()
                check(field("originalVocal") != field("embeddedMainTrackOriginal"))
                target.showFullScreenPlayer()
                later {
                    seekWithBar(60000)
                    later {
                        verify("audible-alternate-forward", 60000)
                        seekWithBar(12000)
                        later {
                            verify("audible-alternate-backward", 12000)
                            call("replay")
                            later {
                                verify("audible-alternate-replay", 0)
                                target.findViewById<android.view.View>(R.id.btn_top_vocal).performClick()
                                seekWithBar(45000)
                                later {
                                    verify("main-audible-muted-alternate-seek", 45000)
                                    target.findViewById<android.view.View>(R.id.btn_top_vocal).performClick()
                                    later {
                                        verify("switch-after-seek", 45000, false)
                                        Log.i("DualSeekRegression", "DONE failures=$failures")
                                    }
                                }
                            }
                        }
                    }
                }
            }.onFailure { Log.e("DualSeekRegression", "FATAL", it) }
        }, 4000)
    }
    private fun seekWithBar(position: Int) {
        val bar = field("fullScreenSeekBar") as SeekBar
        check(bar.width > 0 && bar.max > position)
        val x = bar.paddingLeft + (bar.width - bar.paddingLeft - bar.paddingRight) * position.toFloat() / bar.max
        val y = bar.height / 2f
        val now = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + 10, action, x, y, 0)
            bar.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    private fun verify(name: String, requested: Int, checkTarget: Boolean = true) {
        val main = mainPlayer().currentPosition
        val audio = alternate().currentPosition
        val drift = kotlin.math.abs(main - audio)
        record(name, drift <= 300 && (!checkTarget || main in requested..(requested + 2500)),
            "requested=$requested video=$main alternateAudio=$audio drift=$drift")
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
