package com.example.gestionaquaflow

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * SplashActivity — Écran de démarrage
 *   1. Runnable annulé dans onDestroy (crash évité si l'utilisateur quitte pendant les 3s)
 *   2. NotificationHelper.creerCanaux() appelé ici — une seule fois au démarrage
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val runnableNavigation = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.appliquerTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Crée les canaux de notification Android (opération idempotente)
        NotificationHelper.creerCanaux(this)

        handler.postDelayed(runnableNavigation, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnableNavigation)
    }
}
