package com.sofzizou.bls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission accordée ou refusée — on continue dans les deux cas */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        askNotificationPermission()

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val phone = prefs.getString(Constants.PREF_PHONE, null)

        if (phone == null) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        val filename  = intent.getStringExtra(Constants.EXTRA_FILENAME)
        val proxyUrl  = intent.getStringExtra(Constants.EXTRA_PROXY_URL) ?: ""
        if (filename != null) {
            openLiveness(filename, proxyUrl)
            return
        }

        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tv_phone).text = "📱 Enregistré : $phone"

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            prefs.edit().putString(Constants.PREF_TOKEN, token).apply()
            findViewById<TextView>(R.id.tv_token).text = "Token FCM: ${token.take(30)}…"
        }

        findViewById<Button>(R.id.btn_change).setOnClickListener {
            prefs.edit().remove(Constants.PREF_PHONE).apply()
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val filename = intent?.getStringExtra(Constants.EXTRA_FILENAME) ?: return
        val proxyUrl = intent.getStringExtra(Constants.EXTRA_PROXY_URL) ?: ""
        openLiveness(filename, proxyUrl)
    }

    private fun openLiveness(filename: String, proxyUrl: String) {
        startActivity(Intent(this, LivenessActivity::class.java).apply {
            putExtra(Constants.EXTRA_FILENAME,  filename)
            putExtra(Constants.EXTRA_PROXY_URL, proxyUrl)
        })
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL_ID,
                Constants.NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description     = "Alertes selfie BLS Visa"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                setShowBadge(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
