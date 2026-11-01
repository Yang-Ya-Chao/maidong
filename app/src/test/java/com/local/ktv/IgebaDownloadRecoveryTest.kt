package com.local.ktv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IgebaDownloadRecoveryTest {
    @Test fun expiredUrlsRefreshTwiceThenReloginOnceAndStop() {
        val events = mutableListOf<String>()
        val recovery = IgebaDownloadRecovery({ events.add("order") },
            { events.add("address") }, { events.add("login") })
        repeat(3) { assertTrue(recovery.recover(403, "URL Expired")) }
        repeat(10) { assertFalse(recovery.recover(403, "URL Expired")) }
        assertEquals(listOf("order", "address", "address", "login", "order", "address"), events)
    }

    @Test fun unrelatedErrorsNeverTriggerLoginOrConsumeRecovery() {
        var logins = 0
        var addresses = 0
        val recovery = IgebaDownloadRecovery({}, { addresses++ }, { logins++ })
        for (error in listOf(403 to "Forbidden", 401 to "expired", 429 to "expired", 500 to "expired")) {
            assertFalse(recovery.recover(error.first, error.second))
        }
        assertEquals(0, logins)
        assertEquals(0, addresses)
        assertTrue(recovery.recover(403, "expired"))
        assertEquals(1, addresses)
        assertEquals(0, logins)
    }

    @Test fun failedReloginNeverFetchesAnotherAddressOrLoops() {
        var logins = 0
        var addresses = 0
        val recovery = IgebaDownloadRecovery({}, { addresses++ }, {
            logins++
            throw IOException("登录失败")
        })
        repeat(2) { assertTrue(recovery.recover(403, "expired")) }
        assertThrows(IOException::class.java) { recovery.recover(403, "expired") }
        assertFalse(recovery.recover(403, "expired"))
        assertEquals(1, logins)
        assertEquals(2, addresses)
    }
}
