package com.vidal.cinevault.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vidal.cinevault.R
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.ui.MainActivity

/** Posts one grouped Android notification for images approaching expiry. */
object ExpiryNotifier {
    private const val CHANNEL_ID = "expiry_warnings"
    private const val NOTIFICATION_ID = 4801
    private const val TRASH_NOTIFICATION_ID = 4802

    /** Creates the low-noise expiry notification channel. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Posts the warning and returns true only when Android accepted it. */
    fun notifyExpiring(context: Context, images: List<ImageRecord>): Boolean {
        if (images.isEmpty()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHOW_EXPIRING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4801,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (images.size == 1) {
            "1 imagen pasará a la papelera en unas 24 h."
        } else {
            "${images.size} imágenes pasarán a la papelera en unas 24 h."
        }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(body)
        images.take(6).forEach { inbox.addLine("${it.originalName} · ${it.projectName}") }
        if (images.size > 6) inbox.setSummaryText("y ${images.size - 6} más")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(body)
            .setStyle(inbox)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Posts the final 24-hour warning immediately after images enter the trash. */
    fun notifyTrashGrace(context: Context, images: List<ImageRecord>): Boolean {
        if (images.isEmpty()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHOW_TRASH, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4802,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (images.size == 1) {
            "1 imagen está en la papelera y se borrará definitivamente en unas 24 h."
        } else {
            "${images.size} imágenes están en la papelera y se borrarán definitivamente en unas 24 h."
        }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(body)
        images.take(6).forEach { inbox.addLine("${it.originalName} · ${it.projectName}") }
        if (images.size > 6) inbox.setSummaryText("y ${images.size - 6} más")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Última oportunidad para restaurar")
            .setContentText(body)
            .setStyle(inbox)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(TRASH_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
