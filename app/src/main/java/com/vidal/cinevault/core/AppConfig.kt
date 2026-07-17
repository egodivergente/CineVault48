package com.vidal.cinevault.core

import android.content.Context
import org.json.JSONObject

/** Runtime configuration loaded from assets/config.json plus user overrides. */
data class AppConfig(
    val rootFolder: String,
    val retentionHours: Long,
    val warningHoursBeforeExpiry: Long,
    val trashGraceHours: Long,
    val checkIntervalHours: Long,
    val thumbnailSizePx: Int,
    val maxImportBatch: Int,
    val notificationsEnabled: Boolean,
    val dryRun: Boolean
) {
    companion object {
        private const val PREFERENCES = "cinevault_settings"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"

        /** Loads defaults from config.json and applies editable phone settings. */
        fun load(context: Context): AppConfig {
            val json = context.assets.open("config.json").bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val defaultDryRun = json.optBoolean("dry_run", false)
            val defaultNotifications = json.optBoolean("notifications_enabled", true)
            return AppConfig(
                rootFolder = json.optString("root_folder", "CineVault48"),
                retentionHours = json.optLong("retention_hours", 48L),
                warningHoursBeforeExpiry = json.optLong("warning_hours_before_expiry", 24L),
                trashGraceHours = json.optLong("trash_grace_hours", 24L),
                checkIntervalHours = json.optLong("check_interval_hours", 1L),
                thumbnailSizePx = json.optInt("thumbnail_size_px", 720),
                maxImportBatch = json.optInt("max_import_batch", 30),
                notificationsEnabled = preferences.getBoolean(KEY_NOTIFICATIONS, defaultNotifications),
                dryRun = preferences.getBoolean(KEY_DRY_RUN, defaultDryRun)
            )
        }

        /** Persists the dry-run switch selected in the app. */
        fun setDryRun(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DRY_RUN, enabled)
                .apply()
        }

        /** Persists whether expiry notifications should be posted. */
        fun setNotificationsEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTIFICATIONS, enabled)
                .apply()
        }
    }
}
