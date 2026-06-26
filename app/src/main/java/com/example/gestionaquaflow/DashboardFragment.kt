package com.example.gestionaquaflow

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * DashboardFragment — Tableau de bord
 */
class DashboardFragment : BaseFragment() {

    private var listenerTempsReel: ValueEventListener? = null
    private val pointsDebit = mutableListOf<Float>()
    private val formatMois  = SimpleDateFormat("yyyy-MM",    Locale.FRENCH)
    private val formatJour  = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)

    // ── Accumulation volume (Fix 1) ───────────────────────────────────────
    // Offset = somme des volumes des sessions ESP32 précédentes du jour courant
    private var volumeOffset       = 0f
    private var dernierVolumeESP32 = 0f
    private var premiereReception  = true   // Pour éviter un faux positif de reset

    // Jour actuellement suivi (pour détecter le changement à minuit)
    private var jourSuivi = ""

    // Sauvegarde Firestore throttlée : toutes les 60 s
    private var tsDerniereSauvegarde = 0L
    private val INTERVALLE_SAUVEGARDE_MS = 60_000L

    // Notification seuil
    private var notifSeuilEnvoyee = false

    // Clés SharedPreferences
    private fun cleOffset(date: String)  = "vol_offset_$date"
    private fun cleDernier(date: String) = "vol_dernier_$date"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val vue = inflater.inflate(R.layout.fragment_dashboard, container, false)
        setupEnteteEtTheme(vue)
        return vue
    }

    override fun onResume() {
        super.onResume()
        val vue = view ?: return
        val ctx = requireContext()
        FirebaseRepository.verifierEtResetterSiNouveauMois(ctx)

        if (FirebaseRepository.estConnecte) {
            val tarif = AppRepository.getTarif(ctx)
            val today = formatJour.format(java.util.Date())
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
            val hier = formatJour.format(cal.time)

            // ── Fix 2 : finaliser HIER si pas encore fait ────────────────
            // Ceci est l'unique mécanisme de sauvegarde de fin de journée.
            // Il s'exécute à chaque ouverture de l'app — simple et fiable.
            FirebaseRepository.finaliserJourFirestoreExistant(hier, tarif)

            // Sync RTDB → Firestore pour jours manquants
            FirebaseRepository.syncroniserRTDBversFirestore(tarif)

            // ── Fix 1 : charger l'offset du jour depuis SharedPreferences ─
            chargerOffsetDuJour(ctx, today)

            afficherStatutConnexion(vue, connecte = true)
            rafraichirCoutEtSeuil(vue, null)
            demarrerEcouteFirebase(vue)
        } else {
            afficherEtatVide(vue)
            afficherStatutConnexion(vue, connecte = false)
        }
    }

    override fun onPause() {
        super.onPause()
        // Sauvegarder l'état courant avant de passer en pause
        val ctx = context
        if (ctx != null && jourSuivi.isNotEmpty()) {
            val tarif = AppRepository.getTarif(ctx)
            val volumeReel = volumeOffset + dernierVolumeESP32
            // Écriture Firestore de la dernière valeur connue
            if (volumeReel > 0f) {
                FirebaseRepository.mettreAJourJourLive(jourSuivi, volumeReel, tarif)
            }
            // Sauvegarder l'offset en prefs pour survivre au redémarrage de l'app
            sauvegarderOffsetEnPrefs(ctx)
        }
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = null
    }

    // ── Gestion de l'offset (persistance) ────────────────────────────────────

    private fun chargerOffsetDuJour(ctx: Context, date: String) {
        val prefs = ctx.getSharedPreferences("AquaFlowPrefs", Context.MODE_PRIVATE)
        val dateStockee = prefs.getString("vol_date", "")
        if (dateStockee == date) {
            // Même journée → restaurer l'offset accumulé
            volumeOffset       = prefs.getFloat(cleOffset(date), 0f)
            dernierVolumeESP32 = prefs.getFloat(cleDernier(date), 0f)
        } else {
            // Nouvelle journée → reset complet
            volumeOffset       = 0f
            dernierVolumeESP32 = 0f
        }
        jourSuivi         = date
        premiereReception = true
    }

    private fun sauvegarderOffsetEnPrefs(ctx: Context) {
        ctx.getSharedPreferences("AquaFlowPrefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat(cleOffset(jourSuivi), volumeOffset)
            .putFloat(cleDernier(jourSuivi), dernierVolumeESP32)
            .putString("vol_date", jourSuivi)
            .apply()
    }

    // ── Calcul du volume réel avec accumulation ───────────────────────────────

    /**
     * calculerVolumeReel — Gère l'accumulation quand l'ESP32 redémarre.
     *
     * L'ESP32 reset volume_jour à 0 à chaque démarrage. Cette méthode
     * détecte cette chute et additionne l'ancien volume à l'offset.
     * Résultat : le volume affiché est continu sur toute la journée,
     * même après plusieurs redémarrages de l'ESP32.
     */
    private fun calculerVolumeReel(volumeESP32: Float): Float {
        val ctx = requireContext()

        if (!premiereReception) {
            // Détecter un redémarrage ESP32 : le volume chute à moins de 30% de la valeur précédente
            val resetDetecte = dernierVolumeESP32 > 0.5f && volumeESP32 < dernierVolumeESP32 * 0.3f
            if (resetDetecte) {
                volumeOffset += dernierVolumeESP32
                sauvegarderOffsetEnPrefs(ctx)
            }
        }

        dernierVolumeESP32 = volumeESP32
        premiereReception  = false
        return volumeOffset + volumeESP32
    }

    // ── Listener Firebase ─────────────────────────────────────────────────────

    private fun demarrerEcouteFirebase(vue: View) {
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = null

        listenerTempsReel = FirebaseRepository.ecouterDonneesTempsReel(
            onDonnees = { donnees ->
                val volumeReel = calculerVolumeReel(donnees.volumeJour)

                // Détecter changement de jour (minuit passé pendant que l'app est ouverte)
                val jourdHui = formatJour.format(java.util.Date())
                if (jourSuivi.isNotEmpty() && jourdHui != jourSuivi) {
                    val tarif = AppRepository.getTarif(requireContext())
                    // Finaliser HIER avec son volume réel accumulé
                    FirebaseRepository.finaliserJournee(jourSuivi, volumeOffset + dernierVolumeESP32, tarif)
                    // Reset pour le nouveau jour
                    volumeOffset       = 0f
                    dernierVolumeESP32 = 0f
                    premiereReception  = true
                    notifSeuilEnvoyee  = false
                    jourSuivi          = jourdHui
                    sauvegarderOffsetEnPrefs(requireContext())
                }

                // Sauvegarde Firestore toutes les 60 s (date téléphone — toujours fiable)
                val maintenant = System.currentTimeMillis()
                if (volumeReel > 0f && maintenant - tsDerniereSauvegarde >= INTERVALLE_SAUVEGARDE_MS) {
                    tsDerniereSauvegarde = maintenant
                    FirebaseRepository.mettreAJourJourLive(
                        date         = jourdHui,
                        volumeLitres = volumeReel,   // volume RÉEL accumulé
                        tarifFcM3    = AppRepository.getTarif(requireContext())
                    )
                    sauvegarderOffsetEnPrefs(requireContext())
                }

                // Construire les données affichées avec le volume réel
                val donneesReelles = donnees.copy(volumeJour = volumeReel)
                afficherDonneesTempsReel(vue, donneesReelles)
                rafraichirCoutEtSeuil(vue, donneesReelles)
                vue.findViewById<TextView>(R.id.tvStatutCapteur)?.apply {
                    text = "● Capteur connecté"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_vert))
                }
            },
            onErreur = { _ ->
                afficherEtatVide(vue)
                vue.findViewById<TextView>(R.id.tvStatutCapteur)?.apply {
                    text = "● Capteur non détecté"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_rouge))
                }
                vue.findViewById<TextView>(R.id.tvStatutFirebase)?.apply {
                    text = "● Erreur de connexion Firebase"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_rouge))
                }
            }
        )
    }

    private fun afficherDonneesTempsReel(vue: View, donnees: DonneesTempsReel) {
        vue.findViewById<TextView>(R.id.tvValeurDebit)?.text =
            String.format("%.3f", donnees.debit)
        vue.findViewById<TextView>(R.id.tvValeurVolume)?.text =
            String.format("%.3f", donnees.volumeJour)
        ajouterPointDebit(vue, donnees.debit)

        val tarif = AppRepository.getTarif(requireContext())
        FirebaseRepository.lireHistoriqueJournalier(
            tarifFcM3 = tarif,
            onResultat = { entrees ->
                val cleMoisCourant = formatMois.format(Calendar.getInstance().time)
                val cleJourCourant = formatJour.format(Calendar.getInstance().time)
                val historiqueMoisM3 = entrees
                    .filter { it.etiquette.startsWith(cleMoisCourant) && it.etiquette != cleJourCourant }
                    .sumOf { it.volumeLitres.toDouble() }.toFloat() / 1000f
                val totalMoisM3 = historiqueMoisM3 + (donnees.volumeJour / 1000f)
                vue.findViewById<TextView>(R.id.tvValeurMois)?.text =
                    String.format("%.3f m³", totalMoisM3)
                vue.findViewById<TextView>(R.id.tvCoutMois)?.text =
                    String.format("%,.0f FC", totalMoisM3 * tarif)
            },
            onErreur = {
                vue.findViewById<TextView>(R.id.tvValeurMois)?.text =
                    String.format("%.3f m³", donnees.volumeJour / 1000f)
                vue.findViewById<TextView>(R.id.tvCoutMois)?.text =
                    String.format("%,.0f FC", (donnees.volumeJour / 1000f) * tarif)
            }
        )
    }

    private fun ajouterPointDebit(vue: View, debit: Float) {
        pointsDebit.add(debit.coerceAtLeast(0f))
        if (pointsDebit.size > 24) pointsDebit.removeAt(0)
        vue.findViewById<RealtimeLineChartView>(R.id.graphDebitTempsReel)?.setValues(pointsDebit)
    }

    private fun rafraichirCoutEtSeuil(vue: View, donnees: DonneesTempsReel?) {
        val ctx   = requireContext()
        val tarif = AppRepository.getTarif(ctx)
        val seuil = AppRepository.getSeuil(ctx)

        val volumeL  = donnees?.volumeJour ?: 0f
        val volumeM3 = volumeL / 1000f

        vue.findViewById<TextView>(R.id.tvCoutMois)?.text =
            if (volumeL == 0f) "-- FC" else String.format("%,.0f FC", tarif * volumeM3)

        vue.findViewById<TextView>(R.id.tvInfoTarif)?.text =
            "Tarif : ${tarif.toInt()} FC/m³  •  Réinitialisation le 1er"

        val pct = if (seuil > 0) ((volumeM3 / seuil) * 100).toInt().coerceAtMost(100) else 0
        vue.findViewById<LinearProgressIndicator>(R.id.barreProgressionSeuil)?.let {
            it.progress = pct
            it.setIndicatorColor(ContextCompat.getColor(ctx, when {
                pct >= 100 -> R.color.accent_rouge
                pct >= 80  -> R.color.accent_jaune
                else       -> R.color.accent_vert
            }))
        }

        vue.findViewById<TextView>(R.id.tvEtatSeuil)?.text =
            if (volumeL == 0f) "En attente de données..."
            else "${String.format("%.3f", volumeM3)} m³ / ${String.format("%.3f", seuil)} m³  ($pct%)"

        val depasse = volumeM3 > seuil && seuil > 0
        vue.findViewById<TextView>(R.id.tvAlerteSeuil)?.visibility =
            if (depasse) View.VISIBLE else View.GONE
        if (depasse) vue.findViewById<TextView>(R.id.tvAlerteSeuil)?.text = "⚠️  Seuil journalier dépassé !"

        if (AppRepository.estNotificationsActive(ctx) && depasse && !notifSeuilEnvoyee) {
            notifSeuilEnvoyee = true
            NotificationHelper.notifierSeuilDepasse(ctx, volumeM3, seuil)
        }
        if (volumeM3 <= seuil * 0.9f) notifSeuilEnvoyee = false
    }

    private fun afficherEtatVide(vue: View) {
        vue.findViewById<TextView>(R.id.tvValeurDebit)?.text    = "--"
        vue.findViewById<TextView>(R.id.tvValeurVolume)?.text   = "--"
        vue.findViewById<TextView>(R.id.tvValeurMois)?.text     = "-- m³"
        vue.findViewById<TextView>(R.id.tvCoutMois)?.text       = "-- FC"
        vue.findViewById<TextView>(R.id.tvEtatSeuil)?.text      = "En attente de données..."
        vue.findViewById<LinearProgressIndicator>(R.id.barreProgressionSeuil)?.progress = 0
        pointsDebit.clear()
        vue.findViewById<RealtimeLineChartView>(R.id.graphDebitTempsReel)?.setValues(emptyList())
        vue.findViewById<TextView>(R.id.tvAlerteSeuil)?.visibility = View.GONE
        vue.findViewById<TextView>(R.id.tvStatutCapteur)?.apply {
            text = "● Capteur inconnu"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.texte_secondaire))
        }
    }

    private fun afficherStatutConnexion(vue: View, connecte: Boolean) {
        vue.findViewById<TextView>(R.id.tvStatutFirebase)?.apply {
            text = if (connecte) "● Firebase synchronisé" else "● Non connecté à Firebase"
            setTextColor(ContextCompat.getColor(requireContext(),
                if (connecte) R.color.accent_vert else R.color.accent_rouge))
        }
        vue.findViewById<TextView>(R.id.tvStatutCapteur)?.apply {
            text = if (connecte) "● En attente du capteur..." else "● Capteur inconnu"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.texte_secondaire))
        }
    }
}
