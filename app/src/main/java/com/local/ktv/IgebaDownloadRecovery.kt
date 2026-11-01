package com.local.ktv

/** Recovery is bounded per song and only applies to an explicitly expired media URL. */
internal class IgebaDownloadRecovery(
    private val notifyOrder: () -> Unit,
    private val refreshAddress: () -> Unit,
    private val relogin: () -> Unit,
) {
    private var attempts = 0
    fun recover(code: Int, detail: String): Boolean {
        if (code != 403 || !detail.contains("expired", ignoreCase = true) || attempts >= 3) return false
        attempts++
        if (attempts == 3) relogin()
        if (attempts == 1 || attempts == 3) notifyOrder()
        refreshAddress()
        return true
    }
}
