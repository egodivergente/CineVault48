package com.vidal.cinevault.core

import kotlin.math.max

/** Pure time calculations for the 48-hour lifecycle. */
class LifecyclePolicy(private val config: AppConfig) {
    private val hourMillis = 60L * 60L * 1000L

    /** Calculates the instant at which a new image leaves the temporary gallery. */
    fun expiresAt(createdAt: Long): Long = createdAt + config.retentionHours * hourMillis

    /** Calculates the notification instant before temporary expiry. */
    fun warningAt(createdAt: Long): Long =
        expiresAt(createdAt) - config.warningHoursBeforeExpiry * hourMillis

    /** Calculates final deletion after the trash grace period. */
    fun deleteAfter(trashedAt: Long): Long = trashedAt + config.trashGraceHours * hourMillis

    /** Returns whole remaining hours rounded up for friendly badges. */
    fun hoursLeft(deadline: Long, now: Long): Long {
        val remaining = max(0L, deadline - now)
        return (remaining + hourMillis - 1L) / hourMillis
    }
}
