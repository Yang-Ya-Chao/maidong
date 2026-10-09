package com.local.ktv

import android.util.AtomicFile
import java.io.File

/** A provider switch clears old downloads once, before copying the APK's new catalog. */
object LegacyStorageUpgrade {
    private val marker get() = AtomicFile(File(AppPaths.root, ".igeba-cache-migration-v1"))

    @Synchronized
    internal fun clearOldDownloads(state: KtvStateDatabase): OwnedSongCacheCleaner.Result? {
        if (runCatching { marker.openRead().use { it.read() == '1'.code } }.getOrDefault(false)) return null
        val result = OwnedSongCacheCleaner.clear(AppPaths.cloudSongsDir)
        state.clearDownloads()
        val record = marker
        val output = record.startWrite()
        try {
            output.write("1\n".toByteArray(Charsets.UTF_8))
            record.finishWrite(output)
        } catch (error: Throwable) {
            record.failWrite(output)
            throw error
        }
        return result
    }
}
