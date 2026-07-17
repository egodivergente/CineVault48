package com.vidal.cinevault.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vidal.cinevault.core.AppConfig
import com.vidal.cinevault.core.ScreenshotManager
import com.vidal.cinevault.notifications.ExpiryNotifier

/** Android-managed hourly task for warnings, trash moves and final deletion. */
class MaintenanceWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    /** Executes one idempotent maintenance pass. */
    override suspend fun doWork(): Result = try {
        val config = AppConfig.load(applicationContext)
        val manager = ScreenshotManager(applicationContext, config)
        if (config.notificationsEnabled) {
            val candidates = manager.notificationCandidates()
            if (ExpiryNotifier.notifyExpiring(applicationContext, candidates)) {
                manager.markWarningsNotified(candidates.map { it.id })
            }
        }
        val report = manager.runMaintenance()
        if (config.notificationsEnabled && report.movedToTrash.isNotEmpty()) {
            ExpiryNotifier.notifyTrashGrace(applicationContext, report.movedToTrash)
        }
        if (report.errors.isEmpty()) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}
