package com.example.gestionaquaflow

import android.content.Context
import android.content.SharedPreferences

/**
 * ═══════════════════════════════════════════════════════════════════
 * AppRepository — Source unique de vérité pour les préférences locales
 * ═══════════════════════════════════════════════════════════════════
 *
 * Contient uniquement :
 *   • Les clés et valeurs par défaut des SharedPreferences
 *   • Les getters/setters de configuration utilisateur
 *   • Les calculs de coût (tarif × volume)
 *
 * LES DONNÉES DE CONSOMMATION  viennent exclusivement de Firebase (FirebaseRepository) :
 *   • Realtime Database → débit, volume jour (ESP32 toutes les 30s)
 *   • Firestore         → historique journalier et mensuel
 *
 * Il  se remplit au fur et à mesure des vraies mesures du capteur.
 */
object AppRepository {

    private const val NOM_PREFERENCES = "AquaFlowPrefs"

    // ── Clés SharedPreferences ───────────────────────────────────────────────
    const val CLE_NOM_ABONNE    = "USER_NAME"
    const val CLE_NUM_COMPTEUR  = "METER_ID"
    const val CLE_ADRESSE       = "ADDRESS"
    const val CLE_TARIF         = "TARIFF"         // FC par m³
    const val CLE_SEUIL         = "THRESHOLD"      // m³ par jour
    const val CLE_MODE_SOMBRE   = "DARK_MODE"
    const val CLE_NOTIFICATIONS = "NOTIF_ENABLED"
    const val CLE_ANOMALIES     = "ANOMALIES_ENABLED"
    const val CLE_DERNIER_MOIS  = "DERNIER_MOIS_CONNU"  // pour le reset mensuel

    // ── Valeurs par défaut ───────────────────────────────────────────────────
    const val DEFAUT_NOM_ABONNE   = ""       // vide → l'utilisateur doit renseigner
    const val DEFAUT_NUM_COMPTEUR = ""
    const val DEFAUT_ADRESSE      = ""
    const val DEFAUT_TARIF        = 270f     // 270 FC par m³ (tarif Butembo)
    const val DEFAUT_SEUIL        = 15f      // 15 m³ par jour

    // ── Accès aux préférences ────────────────────────────────────────────────
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NOM_PREFERENCES, Context.MODE_PRIVATE)

    // Getters
    fun getNomAbonne(ctx: Context)   = prefs(ctx).getString(CLE_NOM_ABONNE,   DEFAUT_NOM_ABONNE)   ?: DEFAUT_NOM_ABONNE
    fun getNumCompteur(ctx: Context) = prefs(ctx).getString(CLE_NUM_COMPTEUR, DEFAUT_NUM_COMPTEUR) ?: DEFAUT_NUM_COMPTEUR
    fun getAdresse(ctx: Context)     = prefs(ctx).getString(CLE_ADRESSE,      DEFAUT_ADRESSE)      ?: DEFAUT_ADRESSE
    fun getTarif(ctx: Context)       = prefs(ctx).getFloat(CLE_TARIF,         DEFAUT_TARIF)
    fun getSeuil(ctx: Context)       = prefs(ctx).getFloat(CLE_SEUIL,         DEFAUT_SEUIL)
    fun estModeSombre(ctx: Context, defautSysteme: Boolean) =
        prefs(ctx).getBoolean(CLE_MODE_SOMBRE, defautSysteme)
    fun estNotificationsActive(ctx: Context) = prefs(ctx).getBoolean(CLE_NOTIFICATIONS, true)
    fun estDetectionAnomalies(ctx: Context)  = prefs(ctx).getBoolean(CLE_ANOMALIES, true)

    // ── Sauvegarde configuration ─────────────────────────────────────────────
    fun sauvegarderConfig(
        context: Context,
        nomAbonne: String, numCompteur: String, adresse: String,
        tarif: Float, seuil: Float,
        notificationsActive: Boolean, anomaliesActive: Boolean
    ) {
        prefs(context).edit().apply {
            putString(CLE_NOM_ABONNE,   nomAbonne.trim())
            putString(CLE_NUM_COMPTEUR, numCompteur.trim())
            putString(CLE_ADRESSE,      adresse.trim())
            putFloat(CLE_TARIF,         tarif)
            putFloat(CLE_SEUIL,         seuil)
            putBoolean(CLE_NOTIFICATIONS, notificationsActive)
            putBoolean(CLE_ANOMALIES,     anomaliesActive)
            apply()
        }
    }

    fun sauvegarderModeSombre(context: Context, estSombre: Boolean) {
        prefs(context).edit().putBoolean(CLE_MODE_SOMBRE, estSombre).apply()
    }

    // ── Calculs métier ───────────────────────────────────────────────────────

    /** Calcule le coût FC pour un volume donné en m³ */
    fun calculerCout(context: Context, volumeM3: Float): Float =
        getTarif(context) * volumeM3

    /**
     * Vérifie si on est dans un nouveau mois depuis le dernier lancement.
     * Si oui → enregistre le nouveau mois (le reset visuel est géré dans
     * chaque fragment via les états vides Firebase).
     * Retourne true si c'est effectivement un nouveau mois.
     */
    fun estNouveauMois(context: Context): Boolean {
        val prefs = prefs(context)
        val formatMois = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.FRENCH)
        val moisActuel = formatMois.format(java.util.Calendar.getInstance().time)
        val moisStocke = prefs.getString(CLE_DERNIER_MOIS, "") ?: ""

        return if (moisStocke != moisActuel) {
            prefs.edit().putString(CLE_DERNIER_MOIS, moisActuel).apply()
            moisStocke.isNotEmpty() // true seulement si ce n'est pas le tout premier lancement
        } else false
    }
}
