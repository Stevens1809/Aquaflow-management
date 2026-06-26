package com.example.gestionaquaflow

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * ═══════════════════════════════════════════════════════════════════
 * MainActivity — Activité principale avec navigation par onglets
 * ═══════════════════════════════════════════════════════════════════
 *
 * STRUCTURE :
 * MainActivity contient un FrameLayout (conteneur_fragment) et un
 * BottomNavigationView (navigation_bas). Elle remplace dynamiquement
 * le contenu du FrameLayout selon l'onglet sélectionné.
 *
 * CORRECTION APPLIQUÉE — réutilisation des instances de fragments :
 *   Avant : chaque clic sur un onglet créait une nouvelle instance
 *   (DashboardFragment(), etc.) → le graphe temps réel et la liste
 *   pointsDebit étaient réinitialisés à chaque navigation.
 *
 *   Après : chargerFragment() cherche d'abord un fragment existant
 *   via son tag. Si trouvé → réutilisé (état conservé).
 *   Si non trouvé → création de la nouvelle instance.
 *
 * TAGS :
 *   "tag_dashboard"   → DashboardFragment
 *   "tag_historique"  → HistoryFragment
 *   "tag_stats"       → StatsFragment
 *   "tag_reglages"    → SettingsFragment
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // IMPORTANT : appliquer le thème avant setContentView()
        ThemeHelper.appliquerTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navigationBas = findViewById<BottomNavigationView>(R.id.navigation_bas)

        // Charge le tableau de bord uniquement au premier démarrage
        if (savedInstanceState == null) {
            chargerFragment(DashboardFragment(), "tag_dashboard")
        }

        navigationBas.setOnItemSelectedListener { element ->
            // FIX : passer un tag à chargerFragment pour réutiliser l'instance existante
            when (element.itemId) {
                R.id.nav_tableau_de_bord -> chargerFragment(DashboardFragment(), "tag_dashboard")
                R.id.nav_historique      -> chargerFragment(HistoryFragment(),   "tag_historique")
                R.id.nav_statistiques    -> chargerFragment(StatsFragment(),     "tag_stats")
                R.id.nav_reglages        -> chargerFragment(SettingsFragment(),  "tag_reglages")
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    /**
     * chargerFragment — Remplace le conteneur par le fragment demandé.
     *
     * FIX : findFragmentByTag() retrouve l'instance précédente si elle
     * existe déjà dans le FragmentManager. On réutilise cette instance
     * au lieu d'en créer une nouvelle, ce qui préserve :
     *   • la liste pointsDebit et le graphe temps réel (DashboardFragment)
     *   • le filtre actif Jour/Mois/Année (HistoryFragment)
     *   • l'état du scroll dans chaque liste
     *
     * @param nouveau  Instance à utiliser si aucune n'existe avec ce tag
     * @param tag      Identifiant unique du fragment dans le FragmentManager
     */
    private fun chargerFragment(nouveau: Fragment, tag: String) {
        val existant = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction()
            .replace(R.id.conteneur_fragment, existant ?: nouveau, tag)
            .commit()
    }
}
