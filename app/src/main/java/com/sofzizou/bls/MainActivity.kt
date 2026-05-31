package com.sofzizou.bls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val phone = prefs.getString(Constants.PREF_PHONE, null)

        // Premier lancement : enregistrement obligatoire
        if (phone == null) {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
            return
        }

        // Lancé via deep link ou notification FCM
        val filename  = intent.getStringExtra(Constants.EXTRA_FILENAME)
        val proxyUrl  = intent.getStringExtra(Constants.EXTRA_PROXY_URL) ?: ""
        if (filename != null) {
            openLiveness(filename, proxyUrl)
            return
        }

        // Écran d'attente normal
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tv_phone).text = "📱 Enregistré : $phone"

        // Afficher le token FCM (info de debug)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            prefs.edit().putString(Constants.PREF_TOKEN, token).apply()
            findViewById<TextView>(R.id.tv_token).text = "Token FCM: ${token.take(30)}…"
        }

        // Changer de numéro
        findViewById<Button>(R.id.btn_change).setOnClickListener {
            prefs.edit().remove(Constants.PREF_PHONE).apply()
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    // Gérer les re-lancements depuis notification quand l'app est déjà ouverte
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
}
