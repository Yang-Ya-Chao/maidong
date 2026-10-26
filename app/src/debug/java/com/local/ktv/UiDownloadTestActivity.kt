package com.local.ktv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

/** Debug-only visual loop and real downloader cancellation/resume acceptance. */
class UiDownloadTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var small: LoopingStatusTextView
    private lateinit var full: LoopingStatusTextView
    private val report = StringBuilder()
    private var cancelledOnce = false
    private var complete = false
    private var downloadStarted = 0L
    private var resumedAt = 0L
    private val song = Song().apply {
        id = "igeba:song:30000458"
        sourceSongNumber = "30000458"
        title = "下载及续传验证"
        singer = "测试"
        filename = "qa-network-30000458.mkv"
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        val text = "当前：歌曲连续循环    下一首：从右侧衔接进入"
        small = LoopingStatusTextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE) }
        full = LoopingStatusTextView(this).apply { this.text = text; textSize = 24f; setTextColor(Color.YELLOW) }
        root.addView(small, LinearLayout.LayoutParams(600, 90))
        root.addView(full, LinearLayout.LayoutParams(-1, 110))
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        handler.postDelayed({
            record("LOOPS list=${small.completedLoops} fullscreen=${full.completedLoops} pass=${small.completedLoops > 0 && full.completedLoops > 0}")
        }, 45000)
        downloadStarted = System.nanoTime()
        startDownload()
    }
    private fun record(value: String) {
        Log.i("UiDownloadValidation", value)
        report.append(value).append('\n')
        val file = File(getExternalFilesDir(null), "ui-download-validation.txt")
        file.writeText(report.toString())
        status.text = report.toString().takeLast(2200)
    }
    private fun startDownload() {
        SongOkDownloadManager.download(song, object : SongOkDownloadManager.DownloadCallback {
            override fun onDownloadStart(song: Song) { handler.post { record("START resumed=$cancelledOnce") } }
            override fun onDownloadProgress(song: Song, progress: Int) {
                handler.post {
                    val transfer = SongOkDownloadManager.transferStatus(song)
                    status.text = "下载 $progress%  ${transfer?.second ?: 0} B/s\n" + report.toString().takeLast(1600)
                    // Changing progress text every second must not restart the marquee.
                    small.text = "当前：歌曲连续循环    下一首：从右侧衔接进入    下载 $progress%"
                    full.text = small.text
                }
                if (progress >= 10 && !cancelledOnce) {
                    cancelledOnce = true
                    SongOkDownloadManager.cancelDownload(song)
                    handler.post {
                        record("CANCEL progress=$progress")
                        handler.postDelayed({ resumedAt = System.nanoTime(); startDownload() }, 1200)
                    }
                }
            }
            override fun onDownloadReadyToPlay(song: Song) = Unit
            override fun onDownloadComplete(song: Song, localPath: String) {
                complete = true
                val bytes = File(localPath).length()
                val digest = MessageDigest.getInstance("SHA-256")
                File(localPath).inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val result = FfmpegValidation.checkFile(localPath)
                val json = org.json.JSONObject(result)
                val elapsed = (System.nanoTime() - downloadStarted) / 1000000
                handler.post {
                    record("COMPLETE bytes=$bytes ms=$elapsed sha256=$hash native=$result")
                    record("DONE downloadPass=${json.getInt("errors") == 0 && bytes > 0} resumed=$cancelledOnce")
                }
            }
            override fun onDownloadFailed(song: Song, error: String) { handler.post { record("FAILED $error") } }
        })
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!complete) SongOkDownloadManager.cancelDownload(song)
        super.onDestroy()
    }
}
