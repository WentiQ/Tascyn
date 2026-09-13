package com.example.tascyn

import com.example.tascyn.data.DynamicQrSession
import com.example.tascyn.data.DynamicQrSyncManager
import org.junit.Assert.*
import org.junit.Test

class DynamicQrSyncTest {

    @Test
    fun testDynamicQrConstants() {
        assertEquals(30_000L, DynamicQrSyncManager.UNHIDE_DURATION_MS)
        assertEquals(300_000L, DynamicQrSyncManager.DATA_EXPIRATION_MS)
    }

    @Test
    fun testDynamicQrSessionLifecycle() {
        val now = 1000000L
        val deviceId = "TSYN-TEST01"
        val payload1 = "TASCYN_SYNC:initial_payload"

        // 1. Initial / Unhide Action
        val session1 = DynamicQrSession(
            deviceId = deviceId,
            updatedAt = now,
            expiresAt = now + DynamicQrSyncManager.DATA_EXPIRATION_MS,
            unhideExpiresAt = now + DynamicQrSyncManager.UNHIDE_DURATION_MS,
            isExpired = false,
            tasksPayload = payload1,
            activeTasksCount = 5
        )

        // Verify timestamps
        assertEquals(now + 30_000L, session1.unhideExpiresAt)
        assertEquals(now + 300_000L, session1.expiresAt)
        assertFalse(session1.isExpired)
        assertEquals(payload1, session1.tasksPayload)

        // 2. State at 15 seconds (Still unhidden)
        val timeAt15s = now + 15_000L
        assertTrue(timeAt15s < session1.unhideExpiresAt)
        assertTrue(timeAt15s < session1.expiresAt)

        // 3. State at 35 seconds (30s passed -> Auto-blurred, but data still active)
        val timeAt35s = now + 35_000L
        assertTrue(timeAt35s >= session1.unhideExpiresAt) // Re-blurred
        assertTrue(timeAt35s < session1.expiresAt) // Data still active for remaining ~4.5 min
        assertFalse(session1.isExpired)

        // 4. State after 5 minutes (301 seconds -> Expired & Erased)
        val timeAt301s = now + 301_000L
        val isExpiredAt301s = timeAt301s >= session1.expiresAt
        assertTrue(isExpiredAt301s)

        // Simulate erasure
        val expiredSession = session1.copy(
            isExpired = true,
            tasksPayload = null
        )
        assertTrue(expiredSession.isExpired)
        assertNull(expiredSession.tasksPayload)

        // 5. Subsequent Unhide click: loads new data into the SAME device dynamic QR
        val reloadTime = timeAt301s + 50_000L
        val payload2 = "TASCYN_SYNC:updated_payload_v2"
        val session2 = DynamicQrSession(
            deviceId = deviceId, // Exact same device QR identifier!
            updatedAt = reloadTime,
            expiresAt = reloadTime + DynamicQrSyncManager.DATA_EXPIRATION_MS,
            unhideExpiresAt = reloadTime + DynamicQrSyncManager.UNHIDE_DURATION_MS,
            isExpired = false,
            tasksPayload = payload2,
            activeTasksCount = 8
        )

        assertEquals(deviceId, session2.deviceId)
        assertEquals(payload2, session2.tasksPayload)
        assertEquals(8, session2.activeTasksCount)
        assertEquals(reloadTime + 30_000L, session2.unhideExpiresAt)
        assertEquals(reloadTime + 300_000L, session2.expiresAt)
        assertFalse(session2.isExpired)
    }

    @Test
    fun testDynamicQrPayloadPrefix() {
        val deviceId = "TSYN-ABCD1234"
        val qrPayload = "TASCYN_DYNAMIC:$deviceId"
        assertTrue(qrPayload.startsWith("TASCYN_DYNAMIC:"))
        assertEquals("TSYN-ABCD1234", qrPayload.removePrefix("TASCYN_DYNAMIC:"))
    }
}
