package com.local.ktv
import android.app.Activity
import android.os.*
import android.net.Uri
import android.util.Log
import android.widget.*
import com.local.ktv.player.*
import androidx.media3.common.util.UnstableApi
import java.io.File

@UnstableApi
class DecoderTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: KtvPlaybackEngine
    private lateinit var status: TextView
    private lateinit var directory: File
    private var samples = emptyList<File>()
    private var index = 0
    private var error = 0
    private var before = 0
    private val report = StringBuilder()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = 1; setBackgroundColor(-16777216) }
        status = TextView(this).apply { textSize = 18f; setTextColor(-1) }
        val view = KtvVideoView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, 160))
        setContentView(root)
        Log.i("DecoderMatrix", "RUNTIME abis=${Build.SUPPORTED_ABIS.joinToString()} nativeDir=${applicationInfo.nativeLibraryDir}")
        getSharedPreferences("playback_decoder", MODE_PRIVATE).edit().putString("mode", "SYSTEM").apply()
        engine = KtvPlaybackEngine(this)
        check(engine.decoderMode == PlaybackDecoderMode.AUTO) { "旧设置未迁移到自动模式" }
        engine.attach(view)
        engine.setOnErrorListener { _, _, code -> error = code; true }
        directory = File(getExternalFilesDir(null), "decoder-samples").apply { mkdirs() }
        Thread {
            samples = assets.list("decoder-samples").orEmpty().filter { it.endsWith(".mkv") }.sorted().map { name ->
                File(directory, name).also { output ->
                    assets.open("decoder-samples/$name").use { input -> output.outputStream().use { input.copyTo(it) } }
                }
            }
            handler.post { nextSample() }
        }.start()
    }
    private fun record(value: String) {
        Log.i("DecoderMatrix", value)
        report.append(value).append('\n')
        File(directory, "results.txt").writeText(report.toString())
        status.text = if (index < samples.size) "自动解码测试\n样本：${samples[index].name}" else "自动解码测试完成"
    }
    private fun nextSample() {
        if (index >= samples.size) {
            record("DONE samples=${samples.size}")
            report.lines().filter { it.startsWith("RESULT") }.forEach { Log.i("DecoderMatrix", "FINAL $it") }
            return
        }
        engine.stop()
        engine.setVideoUri(Uri.fromFile(samples[index]))
        engine.start()
        error = 0
        before = 0
        record("START file=${samples[index].name}")
        handler.postDelayed({ verify() }, 15000)
    }
    private fun verify() {
        val pos = engine.currentPosition
        val progressed = engine.isPlaying && pos - before > 700
        val endedShort = !engine.isPlaying && engine.duration in 1..4500 && pos >= engine.duration - 150
        val videoPassed = error == 0 && engine.hasRenderedFirstFrame && (progressed || endedShort)
        val audioExpected = samples[index].name !in setOf("hdr10tags-both.mkv", "spherical.mkv", "msmpeg4v3-subrip.mkv")
        val audioPassed = !audioExpected || !engine.decoderSummary.substringAfter('\n').contains("等待播放")
        val passed = videoPassed && audioPassed
        record("RESULT file=${samples[index].name} mode=${engine.decoderMode} pass=$passed " +
            "videoPass=$videoPassed audioPass=$audioPassed firstFrame=${engine.hasRenderedFirstFrame} " +
            "error=$error before=$before after=$pos playing=${engine.isPlaying} " +
            engine.decoderSummary.replace('\n', '|'))
        index++
        handler.postDelayed({ nextSample() }, 500)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.release()
        super.onDestroy()
    }
}