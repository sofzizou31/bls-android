package com.sofzizou.bls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FCMService : FirebaseMessagingService() {

    // ── Nouveau token FCM (changement de device ou réinstallation) ───────────

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Nouveau token: ${token.take(20)}...")

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.PREF_TOKEN, token).apply()

        // Re-enregistrer sur GitHub si le téléphone est déjà configuré
        val phone = prefs.getString(Constants.PREF_PHONE, null)
        if (phone != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = GitHubClient().registerFCMToken(phone, token)
                Log.d("FCM", "Re-enregistrement token → $ok")
            }
        }
    }

    // ── Message reçu : déclencher l'alarme ──────────────────────────────────

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("FCM", "Message: ${message.data}")

        val filename = message.data["filename"] ?: run {
            Log.w("FCM", "Pas de filename dans le message")
            return
        }
        val proxyUrl = message.data["proxy_url"] ?: ""

        createNotificationChannel()

        // Intent : ouvre LivenessActivity directement au tap
        val intent = Intent(this, LivenessActivity::class.java).apply {
            putExtra(Constants.EXTRA_FILENAME,  filename)
            putExtra(Constants.EXTRA_PROXY_URL, proxyUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = message.notification?.title ?: "📸 BLS Visa — Selfie requis"
        val body  = message.notification?.body  ?: "Ouvrez l'app pour effectuer votre selfie"

        val notif = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)      // Affiche en plein écran comme une alarme
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 400))
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Constants.NOTIF_ID, notif)
    }

    // ── Canal haute priorité (Android 8+) ───────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL_ID,
                Constants.NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description    = "Alertes selfie BLS Visa"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                setShowBadge(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
