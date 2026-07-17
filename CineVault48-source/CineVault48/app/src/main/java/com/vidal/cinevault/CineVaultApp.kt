package com.vidal.cinevault

import android.app.Application
import com.vidal.cinevault.notifications.ExpiryNotifier
import com.vidal.cinevault.work.MaintenanceScheduler

/** Initializes notifications and persistent hourly maintenance when Android starts the app. */
class CineVaultApp : Application() {
    /** Creates the notification channel and registers recurring maintenance. */
    override fun onCreate() {
        super.onCreate()
        ExpiryNotifier.createChannel(this)
        MaintenanceScheduler.schedule(this)
    }
}
