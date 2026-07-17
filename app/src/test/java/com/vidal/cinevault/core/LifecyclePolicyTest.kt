package com.vidal.cinevault.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fast JVM tests for all deadline calculations and badge rounding. */
class LifecyclePolicyTest {
    private val config = AppConfig(
        rootFolder = "test",
        retentionHours = 48,
        warningHoursBeforeExpiry = 24,
        trashGraceHours = 24,
        checkIntervalHours = 1,
        thumbnailSizePx = 720,
        maxImportBatch = 30,
        notificationsEnabled = true,
        dryRun = false
    )
    private val policy = LifecyclePolicy(config)
    private val hour = 60L * 60L * 1000L

    /** Verifies the primary temporary retention deadline. */
    @Test
    fun `new image expires after exactly 48 hours`() {
        assertEquals(48L * hour, policy.expiresAt(0L))
    }

    /** Verifies that the early warning occurs halfway through retention. */
    @Test
    fun `warning occurs 24 hours before expiry`() {
        assertEquals(24L * hour, policy.warningAt(0L))
    }

    /** Verifies the final recoverable trash window. */
    @Test
    fun `trash grace lasts 24 hours`() {
        assertEquals(124L * hour, policy.deleteAfter(100L * hour))
    }

    /** Verifies human badge rounding at partial and expired intervals. */
    @Test
    fun `badge rounds partial hours up and never becomes negative`() {
        assertEquals(2L, policy.hoursLeft(2L * hour, 1L))
        assertEquals(0L, policy.hoursLeft(hour, hour * 2))
    }
}
