package com.example.gestionaquaflow

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView

/**
 * ═══════════════════════════════════════════════════════════════════
 * BaseFragment — Classe parente commune à tous les fragments
 * ═══════════════════════════════════════════════════════════════════
 * HÉRITAGE : DashboardFragment, HistoryFragment, StatsFragment
 *            et SettingsFragment héritent tous de BaseFragment.
 * Chaque page de l'application a :
 *   1. Un en-tête identique (nom abonné, compteur, adresse)
 *   2. Un bouton bascule thème ( Clair / Sombre)
 *
 * Sans BaseFragment, ce code serait copié 4 fois.
 * Avec BaseFragment, on l'écrit une fois ici et chaque fragment
 * appelle simplement setupEnteteEtTheme(vue).
 *
 * CYCLE DE VIE ANDROID :
 * onResume() est appelé chaque fois que le fragment devient visible.
 * On l'utilise pour rafraîchir l'en-tête après retour des Réglages.
 *
 * IDs XML attendus dans chaque layout de fragment :
 *   • tvTitreEntete       — titre principal (nom abonné)
 *   • tvSousTitreEntete   — sous-titre (compteur + adresse)
 *   • btnBasculerTheme    — MaterialCardView bouton thème
 *   • tvLibelleTheme      — TextView à l'intérieur du bouton
 */
abstract class BaseFragment : Fragment() {

    /**
     * setupEnteteEtTheme — Initialise l'en-tête ET le bouton thème
     * À appeler dans onCreateView() de chaque fragment, après inflate()
     * @param vue La vue racine retournée par inflater.inflate()
     */
    protected fun setupEnteteEtTheme(vue: View) {
        mettreAJourEntete(vue)
        configurerBoutonTheme(vue)
    }

    /**
     * mettreAJourEntete — Remplit les TextViews de l'en-tête
     * Lit les données depuis AppRepository (qui lit SharedPreferences)
     * Visible = public pour pouvoir être rappelé depuis onResume()
     */
    protected fun mettreAJourEntete(vue: View) {
        val ctx = requireContext()
        // Nom de l'abonné (ou valeur par défaut si vide)
        vue.findViewById<TextView>(R.id.tvTitreEntete)?.text =
            AppRepository.getNomAbonne(ctx).ifBlank { "Gestion-Automatique AquaFlow" }
        // Numéro de compteur + adresse sur une ligne
        vue.findViewById<TextView>(R.id.tvSousTitreEntete)?.text =
            "Compteur #${AppRepository.getNumCompteur(ctx)} • ${AppRepository.getAdresse(ctx)}"
    }

    /**
     * configurerBoutonTheme — Branche le clic sur btnBasculerTheme
     * Au clic : appelle ThemeHelper.basculer() → Android recrée l'activité
     * Le libellé est mis à jour immédiatement avant la recréation
     */
    private fun configurerBoutonTheme(vue: View) {
        val btnTheme  = vue.findViewById<MaterialCardView>(R.id.btnBasculerTheme) ?: return
        val tvLibelle = vue.findViewById<TextView>(R.id.tvLibelleTheme)           ?: return

        // Synchroniser le libellé avec l'état actuel au chargement
        mettreAJourLibelleTheme(tvLibelle)

        btnTheme.setOnClickListener {
            // ThemeHelper.basculer() retourne le NOUVEAU mode (true = sombre)
            val estSombre = ThemeHelper.basculer(requireContext())
            tvLibelle.text = if (estSombre) "🌙 Sombre" else "☀️ Clair"
            // Android recrée automatiquement l'activité pour appliquer le thème
        }
    }

    /** Met à jour le texte du bouton selon le thème actif */
    private fun mettreAJourLibelleTheme(tv: TextView) {
        val estSombre = ThemeHelper.estSombreActuellement(requireContext())
        tv.text = if (estSombre) "🌙 Sombre" else "☀️ Clair"
    }

    /**
     * onResume — Appelé à chaque fois que le fragment devient visible
     * Rafraîchit l'en-tête pour refléter les changements faits dans Réglages.
     * Les sous-classes peuvent surcharger onResume() et appeler super.onResume()
     * pour déclencher leur propre logique de rafraîchissement en plus.
     */
    override fun onResume() {
        super.onResume()
        view?.let { mettreAJourEntete(it) }
    }
}
