package com.vidal.cinevault.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vidal.cinevault.core.AppConfig
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Registers persistent WorkManager jobs that survive app closure and phone restarts. */
object MaintenanceScheduler {
    private const val UNIQUE_PERIODIC_WORK = "cinevault_hourly_maintenance"

    /** Schedules hourly-ish periodic work and an immediate startup check. */
    fun schedule(context: Context) {
        val config = AppConfig.load(context)
        val repeatHours = max(1L, config.checkIntervalHours)
        val periodic = PeriodicWorkRequestBuilder<MaintenanceWorker>(
            repeatHours,
            TimeUnit.HOURS,
            15L,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        runNow(context)
    }

    /** Enqueues a one-off pass, used after imports and settings changes. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<MaintenanceWorker>().build())
    }
}
