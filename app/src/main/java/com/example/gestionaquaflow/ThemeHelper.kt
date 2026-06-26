package com.example.gestionaquaflow

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * ═══════════════════════════════════════════════════════════════════
 * ThemeHelper — Gestion centralisée du thème clair / sombre
 * ═══════════════════════════════════════════════════════════════════
 *
 * PROBLÈME RÉSOLU :
 * Avant, chaque fragment avait son propre code de bascule de thème
 * (copié-collé 4 fois). Si on modifiait la logique, il fallait changer
 * 4 endroits. ThemeHelper règle ça en un seul point.
 *
 * FONCTIONNEMENT D'AppCompatDelegate :
 * AppCompatDelegate.setDefaultNightMode() change le thème globalement
 * pour toute l'application. Android recrée automatiquement l'activité
 * visible, ce qui applique immédiatement les nouvelles couleurs.
 *
 * PERSISTANCE :
 * La préférence est sauvegardée via AppRepository.sauvegarderModeSombre()
 * dans SharedPreferences, pour être retrouvée au prochain démarrage.
 */
object ThemeHelper {

    /**
     * appliquerTheme — À appeler au démarrage (SplashActivity, MainActivity)
     * Lit la préférence sauvegardée et configure le thème AVANT l'affichage.
     * Si aucune préférence sauvegardée → suit le thème système de l'appareil.
     */
    fun appliquerTheme(context: Context) {
        val systemeEstSombre = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val estSombre = AppRepository.estModeSombre(context, systemeEstSombre)
        AppCompatDelegate.setDefaultNightMode(
            if (estSombre) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /**
     * basculer — Inverse le thème actuel et sauvegarde la nouvelle préférence
     * Retourne true si le nouveau mode est "sombre", false si "clair"
     * Appelé depuis BaseFragment quand l'utilisateur clique sur btnBasculerTheme
     */
    fun basculer(context: Context): Boolean {
        val systemeEstSombre = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val actuel = AppRepository.estModeSombre(context, systemeEstSombre)
        val nouveau = !actuel
        AppRepository.sauvegarderModeSombre(context, nouveau)
        AppCompatDelegate.setDefaultNightMode(
            if (nouveau) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        return nouveau
    }

    /**
     * estSombreActuellement — Retourne l'état actuel du thème
     * Utilisé pour synchroniser le libellé du bouton (☀️ Clair / 🌙 Sombre)
     */
    fun estSombreActuellement(context: Context): Boolean {
        val systemeEstSombre = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return AppRepository.estModeSombre(context, systemeEstSombre)
    }
}
