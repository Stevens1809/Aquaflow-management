package com.example.gestionaquaflow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * NotificationHelper — Gestion des notifications locales AquaFlow
 *
 * CANAUX :
 *   aquaflow_seuil    → Dépassement du seuil journalier  (IMPORTANCE_HIGH)
 *   aquaflow_anomalie → Débit anormal / fuite potentielle (IMPORTANCE_DEFAULT)
 *
 * UTILISATION :
 *   1. Appeler creerCanaux(context) UNE FOIS au démarrage (dans SplashActivity.onCreate)
 *   2. Appeler notifierSeuilDepasse() quand volumeM3 > seuil
 *   3. Le flag anti-spam est géré par l'appelant (DashboardFragment)
 *
 * ANDROID 13+ : la permission POST_NOTIFICATIONS est requise.
 *   → Demandée dans SettingsFragment au premier lancement.
 *   → Si refusée, les notifications sont silencieusement ignorées.
 */
object NotificationHelper {

    const val CANAL_SEUIL    = "aquaflow_seuil"
    const val CANAL_ANOMALIE = "aquaflow_anomalie"

    private const val ID_NOTIF_SEUIL    = 1001
    private const val ID_NOTIF_ANOMALIE = 1002

    /**
     * creerCanaux — À appeler dans SplashActivity.onCreate() (une seule fois).
     * Android ignore silencieusement si les canaux existent déjà.
     */
    fun creerCanaux(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CANAL_SEUIL,
                    "Alertes de consommation",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notification quand le seuil journalier est dépassé"
                    enableVibration(true)
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CANAL_ANOMALIE,
                    "Anomalies de débit",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notification en cas de débit anormal ou fuite potentielle"
                }
            )
        }
    }

    /**
     * notifierSeuilDepasse — Envoie une notification quand le seuil est dépassé.
     * Vérifie la permission avant d'envoyer (Android 13+).
     */
    fun notifierSeuilDepasse(context: Context, volumeM3: Float, seuilM3: Float) {
        if (!permissionAccordee(context)) return

        val notif = NotificationCompat.Builder(context, CANAL_SEUIL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Seuil dépassé — AquaFlow")
            .setContentText(
                "Consommation : ${String.format("%.3f", volumeM3)} m³  |  Seuil : ${String.format("%.3f", seuilM3)} m³"
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Votre consommation journalière a atteint ${String.format("%.3f", volumeM3)} m³, " +
                        "ce qui dépasse le seuil fixé à ${String.format("%.3f", seuilM3)} m³/j.\n" +
                        "Pensez à vérifier vos installations."
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(ID_NOTIF_SEUIL, notif)
    }

    /**
     * notifierDebitAnormal — Envoie une notification pour un débit suspect.
     * @param debit  Débit actuel en L/min
     */
    fun notifierDebitAnormal(context: Context, debit: Float) {
        if (!permissionAccordee(context)) return

        val notif = NotificationCompat.Builder(context, CANAL_ANOMALIE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔍 Débit anormal — AquaFlow")
            .setContentText("Débit inhabituel détecté : ${String.format("%.3f", debit)} L/min")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(ID_NOTIF_ANOMALIE, notif)
    }

    /**
     * permissionAccordee — Vérifie si POST_NOTIFICATIONS est accordée (Android 13+).
     * Sur Android < 13, retourne toujours true (permission implicite).
     */
    fun permissionAccordee(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
