package com.sofzizou.bls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etPhone  = findViewById<EditText>(R.id.et_phone)
        val btnReg   = findViewById<Button>(R.id.btn_register)
        val progress = findViewById<ProgressBar>(R.id.progress_bar)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        btnReg.setOnClickListener {
            val raw = etPhone.text.toString().trim()
            if (raw.length < 9) {
                Toast.makeText(this, "Entrez un numéro valide (ex: +213XXXXXXXXX)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Normaliser → toujours préfixe +213
            val phone = when {
                raw.startsWith("+")    -> raw
                raw.startsWith("0")    -> "+213${raw.drop(1)}"
                raw.startsWith("213")  -> "+$raw"
                else                   -> "+213$raw"
            }

            btnReg.isEnabled        = false
            progress.visibility     = View.VISIBLE
            tvStatus.text           = "Récupération du token FCM…"

            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcmToken ->
                    tvStatus.text = "Enregistrement sur GitHub…"

                    CoroutineScope(Dispatchers.Main).launch {
                        val ok = withContext(Dispatchers.IO) {
                            GitHubClient().registerFCMToken(phone, fcmToken)
                        }
                        progress.visibility = View.GONE

                        if (ok) {
                            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putString(Constants.PREF_PHONE, phone)
                                .putString(Constants.PREF_TOKEN, fcmToken)
                                .apply()

                            tvStatus.text = "✅ Enregistré ! En attente des notifications…"
                            startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                            finish()
                        } else {
                            tvStatus.text = "❌ Erreur — vérifiez votre connexion"
                            btnReg.isEnabled = true
                        }
                    }
                }
                .addOnFailureListener { e ->
                    progress.visibility = View.GONE
                    tvStatus.text       = "❌ Erreur FCM: ${e.message}"
                    btnReg.isEnabled    = true
                }
        }
    }
}
