package com.dreamsparkgroups.qdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives data-only FCM messages from the PHP backend (modules/PushService.php)
 * and shows them as notifications while the app is backgrounded. In the
 * foreground the web app's own polling surfaces the event, so the message is
 * dropped to avoid duplicates.
 */
class QDateMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: return
        val body = data["body"] ?: ""
        val url = data["url"]

        if (MainActivity.isForeground) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("push_url", url)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, url.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Messages & matches",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Distinct id per URL so a like, a match and a message don't overwrite
        // each other in the shade.
        manager.notify(url.hashCode(), notification)
    }

    override fun onNewToken(token: String) {
        // Persisted for MainActivity to hand to the page bridge, which POSTs it
        // to /api/push/register on the next page load.
        getSharedPreferences("qdate", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    companion object {
        private const val CHANNEL_ID = "qdate_push"
    }
}
