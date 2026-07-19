package com.local.ktv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small image loader for the singer grid; keeps recycled rows from receiving another singer's photo. */
object SingerAvatarLoader {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(view: ImageView, imageUrl: String?, placeholder: Int) {
        val key = imageUrl.orEmpty()
        view.tag = key
        view.setImageResource(placeholder)
        if (key.isEmpty()) return

        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = runCatching { decode(key) }.getOrNull() ?: return@execute
            memory.put(key, bitmap)
            main.post {
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(value: String): Bitmap? {
        if (value.startsWith("/")) return BitmapFactory.decodeFile(File(value).absolutePath)
        val connection = URL(value).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MaidongKTV/1.2")
            connection.inputStream.buffered().use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }
}
