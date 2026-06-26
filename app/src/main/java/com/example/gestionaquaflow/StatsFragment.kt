package com.example.gestionaquaflow

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StatsFragment — Statistiques mensuelles
 *
 * Même source de données que HistoryFragment :
 *   Firestore uniquement (RTDB retiré du merge)
 *   + listener live pour aujourd'hui
 *   + syncroniserRTDBversFirestore au démarrage
 */
class StatsFragment : BaseFragment() {

    private var listenerTempsReel: ValueEventListener? = null
    private var donneesFirestoreCache: List<EntreeHistorique> = emptyList()
    private var volumeJourActuelLitres: Float = 0f

    private val formatJour  = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
    private val formatMois  = SimpleDateFormat("yyyy-MM",    Locale.FRENCH)
    private val dateRegex   = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val moisNoms    = listOf("","Jan","Fév","Mar","Avr","Mai","Jun","Jul","Aoû","Sep","Oct","Nov","Déc")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val vue = inflater.inflate(R.layout.fragment_stats, container, false)
        setupEnteteEtTheme(vue)
        return vue
    }

    override fun onResume() {
        super.onResume()
        view?.let { chargerDonnees(it) }
    }

    override fun onPause() {
        super.onPause()
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = null
    }

    private fun chargerDonnees(vue: View) {
        if (!FirebaseRepository.estConnecte) {
            afficherEtatVide(vue, "☁️  Non connecté",
                "Connectez-vous dans Réglages\npour accéder aux statistiques")
            return
        }
        val tarif = AppRepository.getTarif(requireContext())

        // Sync RTDB → Firestore en premier
        FirebaseRepository.syncroniserRTDBversFirestore(tarif)

        // Listener live pour aujourd'hui
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = FirebaseRepository.ecouterDonneesTempsReel(
            onDonnees = { donnees ->
                volumeJourActuelLitres = donnees.volumeJour
                this.view?.let { recalculer(it, tarif) }
            },
            onErreur = { _ -> }
        )

        // Firestore — source unique
        FirebaseRepository.lireHistoriqueCompletFirestore(
            tarifFcM3 = tarif,
            onResultat = { entrees ->
                donneesFirestoreCache = entrees
                this.view?.let { recalculer(it, tarif) }
            },
            onErreur = { }
        )
    }

    private fun recalculer(vue: View, tarif: Float) {
        val ctx         = requireContext()
        val today       = formatJour.format(Date())
        val moisCourant = formatMois.format(Date())

        // Fusion : Firestore + aujourd'hui live
        val map = donneesFirestoreCache
            .filter { it.etiquette.take(10).matches(dateRegex) }
            .associateBy { it.etiquette }
            .toMutableMap()

        if (volumeJourActuelLitres > 0f) {
            map[today] = EntreeHistorique(today, volumeJourActuelLitres,
                (volumeJourActuelLitres / 1000f) * tarif)
        }

        val tousLesJours = map.values
            .filter { it.etiquette.take(10).matches(dateRegex) }
            .sortedBy { it.etiquette }

        if (tousLesJours.isEmpty()) {
            afficherEtatVide(vue, "📊  Aucune donnée",
                "Les statistiques apparaîtront\ndès que le Dashboard sera ouvert")
            return
        }

        // Regroupement par mois (tous les mois)
        val parMois = tousLesJours
            .groupBy { it.etiquette.take(7) }
            .map { (mois, jours) -> EntreeHistorique(
                etiquette    = mois,
                volumeLitres = jours.sumOf { it.volumeLitres.toDouble() }.toFloat(),
                coutFc       = jours.sumOf { it.coutFc.toDouble() }.toFloat()
            )}.sortedBy { it.etiquette }

        // KPIs du mois courant
        val joursMoisCourant = tousLesJours.filter { it.etiquette.startsWith(moisCourant) }
        val volMoisM3  = joursMoisCourant.sumOf { it.volumeLitres.toDouble() }.toFloat() / 1000f
        val moyJourL   = if (joursMoisCourant.isNotEmpty())
            joursMoisCourant.sumOf { it.volumeLitres.toDouble() }.toFloat() / joursMoisCourant.size else 0f

        afficherKPI(vue, volMoisM3, moyJourL, tarif, joursMoisCourant.size)
        afficherTendance(vue, ctx, parMois, moisCourant, volMoisM3)
        construireGraphe(vue, ctx, parMois, moisCourant, tarif)
        construireTableau(vue, ctx, parMois, tarif)
    }

    // ── KPI ───────────────────────────────────────────────────────────────────

    private fun afficherKPI(vue: View, volM3: Float, moyL: Float, tarif: Float, nbJours: Int) {
        vue.findViewById<TextView>(R.id.tvKpiVolumeMois)?.text =
            String.format("%.3f m³", volM3)
        vue.findViewById<TextView>(R.id.tvKpiCoutMois)?.text =
            String.format("%,.0f FC", volM3 * tarif)
        vue.findViewById<TextView>(R.id.tvKpiMoyenneJour)?.text =
            if (moyL > 0f) String.format("%.1f L/j  (%d j)", moyL, nbJours) else "--"
    }

    // ── Tendance ──────────────────────────────────────────────────────────────

    private fun afficherTendance(
        vue: View, ctx: Context,
        parMois: List<EntreeHistorique>,
        moisCourant: String, volMoisM3: Float
    ) {
        val tv = vue.findViewById<TextView>(R.id.tvTendanceMois) ?: return
        if (parMois.size < 2) {
            tv.text = "📊  Continuez à utiliser l'app — la tendance s'affichera le mois prochain"
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            return
        }
        val idx    = parMois.indexOfFirst { it.etiquette == moisCourant }
        val prec   = if (idx > 0) parMois[idx - 1] else parMois.getOrNull(parMois.size - 2)
        val volPrec = prec?.volumeLitres?.div(1000f) ?: 0f
        if (volPrec <= 0f) {
            tv.text = "📊  Données du mois précédent insuffisantes"
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire)); return
        }
        val nomPrec = try { val p = prec!!.etiquette.split("-"); "${moisNoms[p[1].toInt()]} ${p[0]}" }
        catch (e: Exception) { prec?.etiquette ?: "" }
        val diff = ((volMoisM3 - volPrec) / volPrec * 100).toInt()
        tv.text = when {
            diff < 0 -> "✅ Économie de ${Math.abs(diff)}% vs $nomPrec  (−${String.format("%.3f", volPrec - volMoisM3)} m³)"
            diff > 0 -> "⚠️  Hausse de $diff% vs $nomPrec  (+${String.format("%.3f", volMoisM3 - volPrec)} m³)"
            else     -> "= Stable par rapport à $nomPrec"
        }
        tv.setTextColor(ContextCompat.getColor(ctx, if (diff <= 0) R.color.accent_vert else R.color.accent_rouge))
    }

    // ── Graphique en barres ───────────────────────────────────────────────────

    private fun construireGraphe(
        vue: View, ctx: Context,
        parMois: List<EntreeHistorique>,
        moisCourant: String, tarif: Float
    ) {
        val conteneur = vue.findViewById<LinearLayout>(R.id.conteneurBarresStats) ?: return
        conteneur.removeAllViews()
        // FIX : le conteneur d'origine était horizontal (barres côte à côte).
        // On force l'orientation verticale pour y placer titre + graphique + légende.
        conteneur.orientation = LinearLayout.VERTICAL

        if (parMois.isEmpty()) {
            conteneur.addView(msgVide(ctx, "Graphique disponible après le premier mois complet"))
            return
        }

        // Titre
        conteneur.addView(TextView(ctx).apply {
            text = "📊 Consommation mensuelle (en m³)"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(ctx, 8) }
        })

        val barres = parMois.map { entree ->
            val m3 = entree.volumeLitres / 1000f
            val estCourant = entree.etiquette == moisCourant
            val label = try {
                val p = entree.etiquette.split("-")
                "${moisNoms[p[1].toInt()]}\n${p[0]}"
            } catch (e: Exception) { entree.etiquette }
            HistoryBarChartView.Barre(
                etiquette     = label,
                valeur        = m3,
                texteValeur   = String.format("%.2f", m3),
                estAujourdhui = estCourant,
                estDepasse    = false,
                estProche     = false
            )
        }

        val chart = HistoryBarChartView(ctx).apply { uniteLabelY = "m³" }
        chart.setDonnees(barres)

        val hScroll = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            addView(chart)
        }
        conteneur.addView(hScroll)
        hScroll.post { hScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }

        // Légende couleur mois courant
        conteneur.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8))
        })
        conteneur.addView(TextView(ctx).apply {
            text = "● Mois précédents     ● Mois en cours (live)"
            textSize = 10f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
        })
    }

    // ── Tableau de tous les mois ──────────────────────────────────────────────

    private fun construireTableau(
        vue: View, ctx: Context,
        parMois: List<EntreeHistorique>, tarif: Float
    ) {
        val conteneur = vue.findViewById<LinearLayout>(R.id.conteneurCoutsStats) ?: return
        conteneur.removeAllViews()
        if (parMois.isEmpty()) { conteneur.addView(msgVide(ctx, "Aucun mois enregistré")); return }

        val moisCourant = formatMois.format(Date())

        conteneur.addView(TextView(ctx).apply {
            text = "Détail complet — ${parMois.size} mois enregistrés"
            textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(ctx, 10) }
        })

        parMois.reversed().forEachIndexed { idx, entree ->
            val label = try {
                val p = entree.etiquette.split("-"); "${moisNoms[p[1].toInt()]} ${p[0]}"
            } catch (e: Exception) { entree.etiquette }
            val estCourant = entree.etiquette == moisCourant

            // Ligne
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx,4), dp(ctx,12), dp(ctx,4), dp(ctx,12))
            }

            // Barre de couleur gauche
            ligne.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx,3), dp(ctx,28))
                    .also { it.marginEnd = dp(ctx,10) }
                setBackgroundColor(ContextCompat.getColor(ctx,
                    if (estCourant) R.color.accent_bleu else R.color.contour_carte))
            })

            // Mois
            ligne.addView(TextView(ctx).apply {
                text = label + if (estCourant) "  🔴" else ""
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx,
                    if (estCourant) R.color.accent_bleu else R.color.texte_principal))
                if (estCourant) setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Volume
            ligne.addView(TextView(ctx).apply {
                text = String.format("%.3f m³", entree.volumeLitres / 1000f)
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_bleu))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dp(ctx, 12) }
            })

            // Coût
            ligne.addView(TextView(ctx).apply {
                text = String.format("%,.0f FC", entree.coutFc)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            })

            conteneur.addView(ligne)

            if (idx < parMois.size - 1) {
                conteneur.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.contour_carte))
                })
            }
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private fun afficherEtatVide(vue: View, titre: String, msg: String) {
        vue.findViewById<TextView>(R.id.tvKpiVolumeMois)?.text  = "--"
        vue.findViewById<TextView>(R.id.tvKpiCoutMois)?.text    = "--"
        vue.findViewById<TextView>(R.id.tvKpiMoyenneJour)?.text = "--"
        vue.findViewById<TextView>(R.id.tvTendanceMois)?.apply {
            text = titre
            setTextColor(ContextCompat.getColor(context, R.color.texte_secondaire))
        }
        vue.findViewById<LinearLayout>(R.id.conteneurBarresStats)?.apply {
            removeAllViews(); addView(msgVide(context, msg))
        }
        vue.findViewById<LinearLayout>(R.id.conteneurCoutsStats)?.apply {
            removeAllViews(); addView(msgVide(context, msg))
        }
    }

    private fun msgVide(ctx: Context, texte: String) = TextView(ctx).apply {
        text = texte; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
        setTypeface(null, Typeface.ITALIC)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setPadding(dp(ctx,16), dp(ctx,28), dp(ctx,16), dp(ctx,28))
        setLineSpacing(4f, 1f)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}