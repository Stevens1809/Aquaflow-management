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
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * HistoryFragment — Historique des consommations
 *
 * ═══════════════════════════════════════════════════════════════════
 * CORRECTION DÉFINITIVE DU BUG "DONNÉES QUI DISPARAISSENT"
 * ═══════════════════════════════════════════════════════════════════
 *
 * CAUSE RACINE IDENTIFIÉE :
 *   Le merge utilisait RTDB dans l'affichage. Quand l'ESP32 redémarre
 *   et réécrit son nœud historique_journalier (avec NTP KO = date vide
 *   ou corrupted), RTDB pouvait écraser les données de jours passés dans
 *   la fusion, faisant "disparaître" des entrées malgré Firestore intact.
 *
 * SOLUTION :
 *   RTDB est RETIRÉ du merge d'affichage.
 *   → Firestore seul + aujourd'hui live = ce qu'on affiche.
 *   → RTDB sert UNIQUEMENT à syncroniserRTDBversFirestore (copie vers Firestore).
 *   → Une fois dans Firestore (finalise=true), la donnée est protégée.
 *   → L'ESP32 peut redémarrer autant de fois qu'il veut, l'affichage
 *     ne change pas pour les jours passés.
 * ═══════════════════════════════════════════════════════════════════
 */
class HistoryFragment : BaseFragment() {

    private var filtreActif = "Jour"

    // Un seul listener : le live d'aujourd'hui
    private var listenerTempsReel: ValueEventListener? = null

    // Caches
    private var donneesFirestoreCache: List<EntreeHistorique> = emptyList()
    private var volumeJourActuelLitres: Float = 0f

    private val formatJour  = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
    private val formatMois  = SimpleDateFormat("yyyy-MM",    Locale.FRENCH)
    private val dateRegex   = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val joursSemaine = listOf("Dim","Lun","Mar","Mer","Jeu","Ven","Sam")
    private val moisNoms    = listOf("","Jan","Fév","Mar","Avr","Mai","Jun","Jul","Aoû","Sep","Oct","Nov","Déc")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val vue = inflater.inflate(R.layout.fragment_history, container, false)
        setupEnteteEtTheme(vue)
        configurerFiltres(vue)
        return vue
    }

    override fun onResume() {
        super.onResume()
        view?.let { demarrerEcoutes(it) }
    }

    override fun onPause() {
        super.onPause()
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = null
    }

    // ── Chargement des données ────────────────────────────────────────────────

    private fun demarrerEcoutes(vue: View) {
        val conteneur = vue.findViewById<LinearLayout>(R.id.conteneurHistorique) ?: return
        if (!FirebaseRepository.estConnecte) {
            afficherVide(conteneur, "☁️  Non connecté",
                "Connectez-vous dans Réglages pour accéder à votre historique")
            return
        }

        val tarif = AppRepository.getTarif(requireContext())

        // ÉTAPE 1 : Synchroniser RTDB → Firestore AVANT que le capteur
        // puisse potentiellement corrompre les données RTDB de jours passés.
        FirebaseRepository.syncroniserRTDBversFirestore(tarif)

        // ÉTAPE 2 : Listener live pour la valeur d'aujourd'hui uniquement.
        // Le passé vient de Firestore, pas de RTDB.
        FirebaseRepository.arreterEcoute(listenerTempsReel)
        listenerTempsReel = FirebaseRepository.ecouterDonneesTempsReel(
            onDonnees = { donnees ->
                volumeJourActuelLitres = donnees.volumeJour
                this.view?.let { rebatirEtAfficher(it, tarif) }
            },
            onErreur = { _ -> }
        )

        // ÉTAPE 3 : Charger Firestore (source unique pour l'historique passé).
        FirebaseRepository.lireHistoriqueCompletFirestore(
            tarifFcM3 = tarif,
            onResultat = { entrees ->
                donneesFirestoreCache = entrees
                this.view?.let { rebatirEtAfficher(it, tarif) }
            },
            onErreur = { _ ->
                this.view?.let { v ->
                    if (donneesFirestoreCache.isEmpty()) {
                        val c = v.findViewById<LinearLayout>(R.id.conteneurHistorique) ?: return@let
                        afficherVide(c, "⚠️  Erreur de chargement",
                            "Vérifiez votre connexion Internet\net réessayez")
                    }
                }
            }
        )
    }

    // ── Construction de la liste finale (Firestore + live uniquement) ─────────

    private fun rebatirEtAfficher(vue: View, tarif: Float) {
        val today = formatJour.format(Date())

        // Firestore : source unique pour le passé (jamais RTDB ici)
        val map = donneesFirestoreCache
            .filter { it.etiquette.take(10).matches(dateRegex) }
            .associateBy { it.etiquette }
            .toMutableMap()

        // Aujourd'hui : valeur live (peut être 0 si capteur éteint → pas ajouté)
        if (volumeJourActuelLitres > 0f) {
            map[today] = EntreeHistorique(
                etiquette    = today,
                volumeLitres = volumeJourActuelLitres,
                coutFc       = (volumeJourActuelLitres / 1000f) * tarif
            )
        } else if (donneesFirestoreCache.any { it.etiquette == today }) {
            // Aujourd'hui existe dans Firestore (sauvegarde live) → le conserver
            // même si le capteur est éteint maintenant
        }

        // Tri croissant (oldest left, newest right dans le graphique)
        val listeFinale = map.values
            .filter { it.etiquette.take(10).matches(dateRegex) }
            .sortedBy { it.etiquette }

        val conteneur = vue.findViewById<LinearLayout>(R.id.conteneurHistorique) ?: return
        if (listeFinale.isEmpty()) {
            afficherVide(conteneur, "📭  Aucune donnée enregistrée",
                "Ouvrez le Dashboard avec le capteur actif.\nLes données s'enregistrent automatiquement.")
            return
        }

        afficherSelonFiltre(vue, listeFinale, today, tarif)
    }

    // ── Affichage selon filtre ────────────────────────────────────────────────

    private fun afficherSelonFiltre(
        vue: View, jours: List<EntreeHistorique>, today: String, tarif: Float
    ) {
        val ctx = requireContext()
        val conteneur = vue.findViewById<LinearLayout>(R.id.conteneurHistorique) ?: return
        val seuil = AppRepository.getSeuil(ctx)

        when (filtreActif) {
            "Mois" -> {
                val parMois = jours.groupBy { it.etiquette.take(7) }
                    .map { (mois, j) -> EntreeHistorique(
                        etiquette    = mois,
                        volumeLitres = j.sumOf { it.volumeLitres.toDouble() }.toFloat(),
                        coutFc       = j.sumOf { it.coutFc.toDouble() }.toFloat()
                    )}.sortedBy { it.etiquette }
                if (parMois.isEmpty()) afficherVide(conteneur, "📭  Pas encore de données mensuelles",
                    "Revenez après quelques jours d'utilisation")
                else afficherGraphique(conteneur, parMois, today.take(7), tarif, seuil, ctx, "Mois")
            }
            "Année" -> {
                val parAnnee = jours.groupBy { it.etiquette.take(4) }
                    .map { (an, j) -> EntreeHistorique(
                        etiquette    = an,
                        volumeLitres = j.sumOf { it.volumeLitres.toDouble() }.toFloat(),
                        coutFc       = j.sumOf { it.coutFc.toDouble() }.toFloat()
                    )}.sortedBy { it.etiquette }
                if (parAnnee.isEmpty()) afficherVide(conteneur, "📭  Pas encore de données annuelles",
                    "Elles s'accumuleront mois après mois")
                else afficherGraphique(conteneur, parAnnee, today.take(4), tarif, seuil, ctx, "Année")
            }
            else -> afficherGraphique(conteneur, jours, today, tarif, seuil, ctx, "Jour")
        }
    }

    // ── Construction du graphique ─────────────────────────────────────────────

    private fun afficherGraphique(
        conteneur: LinearLayout,
        entrees: List<EntreeHistorique>,
        todayKey: String,
        tarif: Float,
        seuil: Float,
        ctx: Context,
        filtre: String
    ) {
        conteneur.removeAllViews()

        // ── Carte résumé ───────────────────────────────────────────────────
        conteneur.addView(creerCarteResume(ctx, entrees, tarif, filtre))
        conteneur.addView(espaceur(ctx, 14))

        // ── Titre du graphique ─────────────────────────────────────────────
        val titreTxt = when (filtre) {
            "Jour"  -> "📊 Consommation par jour (en litres)"
            "Mois"  -> "📊 Consommation par mois (en m³)"
            else    -> "📊 Consommation par année (en m³)"
        }
        conteneur.addView(TextView(ctx).apply {
            text = titreTxt; textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(ctx, 8) }
        })

        // ── Barres ─────────────────────────────────────────────────────────
        val seuilUnitaire = when (filtre) {
            "Jour"  -> seuil * 1000f  // seuil en m³ → litres
            "Mois"  -> seuil * 30f    // approximation mensuelle en m³
            else    -> seuil * 365f
        }

        val barres = entrees.map { entree ->
            val valAff = if (filtre == "Jour") entree.volumeLitres else entree.volumeLitres / 1000f
            val estAuj = entree.etiquette == todayKey
            val dep    = valAff > seuilUnitaire && seuilUnitaire > 0
            val proche = !dep && seuilUnitaire > 0 && valAff > seuilUnitaire * 0.8f
            val texte  = when (filtre) {
                "Jour"  -> if (valAff >= 1000f) String.format("%.2f kL", valAff/1000f)
                else String.format("%.1f L", valAff)
                "Mois"  -> String.format("%.2f m³", valAff)
                else    -> String.format("%.1f m³", valAff)
            }
            HistoryBarChartView.Barre(
                etiquette    = formaterEtiquette(entree.etiquette, filtre),
                valeur       = valAff,
                texteValeur  = texte,
                estAujourdhui = estAuj,
                estDepasse    = dep,
                estProche     = proche
            )
        }

        val chart = HistoryBarChartView(ctx).apply {
            uniteLabelY  = if (filtre == "Jour") "L" else "m³"
            seuilValeur  = if (seuilUnitaire > 0) seuilUnitaire else 0f
        }
        chart.setDonnees(barres)

        val hScroll = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            addView(chart)
        }
        conteneur.addView(hScroll)

        // Auto-scroll vers la droite (données les plus récentes)
        hScroll.post { hScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }

        // ── Légende ────────────────────────────────────────────────────────
        conteneur.addView(espaceur(ctx, 8))
        conteneur.addView(creerLegende(ctx))

        // ── Table des chiffres sous le graphique ───────────────────────────
        conteneur.addView(espaceur(ctx, 18))
        conteneur.addView(creerSeparateurSection(ctx, when (filtre) {
            "Mois"  -> "📋 Détail par mois (${entrees.size} mois)"
            "Année" -> "📋 Détail par année (${entrees.size} ans)"
            else    -> "📋 Détail par jour (${entrees.size} jours)"
        }))
        conteneur.addView(espaceur(ctx, 6))
        conteneur.addView(creerEnteteTable(ctx, filtre))
        // Ordre décroissant dans la table : plus récent en premier
        entrees.reversed().forEachIndexed { index, entree ->
            conteneur.addView(creerLigneTable(ctx, entree, filtre, todayKey, seuil, tarif))
            if (index < entrees.size - 1) {
                conteneur.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.contour_carte))
                })
            }
        }
        conteneur.addView(espaceur(ctx, 24))
    }

    // ── Carte résumé ──────────────────────────────────────────────────────────

    private fun creerCarteResume(
        ctx: Context, entrees: List<EntreeHistorique>, tarif: Float, filtre: String
    ): LinearLayout {
        val totalL    = entrees.sumOf { it.volumeLitres.toDouble() }.toFloat()
        val totalM3   = totalL / 1000f
        val totalCout = entrees.sumOf { it.coutFc.toDouble() }.toFloat()
        val moy       = if (entrees.size > 1) totalL / entrees.size else totalL
        val maxE      = entrees.maxByOrNull { it.volumeLitres }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.carte_verre))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        fun cellule(icone: String, titre: String, valeur: String, sous: String? = null) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx,8), dp(ctx,14), dp(ctx,8), dp(ctx,14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply { text = icone; textSize = 18f; gravity = Gravity.CENTER })
            addView(TextView(ctx).apply {
                text = titre; textSize = 8.5f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                letterSpacing = 0.05f; setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = valeur; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_bleu))
                setTypeface(null, Typeface.BOLD); setPadding(0, 3, 0, 0)
            })
            if (sous != null) addView(TextView(ctx).apply {
                text = sous; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
            })
        }

        val divider: () -> View = {
            View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx,1), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.contour_carte))
            }
        }

        val totalLabel = when (filtre) { "Mois" -> "TOTAL (m³)"; "Année" -> "TOTAL (m³)"; else -> "TOTAL (L)" }
        val moyLabel   = when (filtre) { "Mois" -> "MOY/MOIS"; "Année" -> "MOY/AN"; else -> "MOY/JOUR" }

        val totalTexte = when (filtre) {
            "Jour"  -> if (totalL >= 1000) String.format("%.2f kL", totalL/1000) else String.format("%.1f L", totalL)
            else    -> String.format("%.3f m³", totalM3)
        }
        val moyTexte = when (filtre) {
            "Jour"  -> String.format("%.1f L", moy)
            else    -> String.format("%.3f m³", moy / 1000f)
        }
        val maxTexte = maxE?.let {
            val etq = try {
                val p = it.etiquette.split("-")
                if (filtre == "Jour") "${p[2]} ${moisNoms[p[1].toInt()]}"
                else if (filtre == "Mois") "${moisNoms[p[1].toInt()]} ${p[0]}"
                else it.etiquette
            } catch (e: Exception) { it.etiquette }
            "$etq\n${String.format("%.1f L", it.volumeLitres)}"
        } ?: "--"

        layout.addView(cellule("📦", totalLabel, totalTexte, String.format("%,.0f FC", totalCout)))
        layout.addView(divider())
        layout.addView(cellule("📈", moyLabel, moyTexte))
        layout.addView(divider())
        layout.addView(cellule("🏆", "RECORD", maxTexte))

        return layout
    }

    // ── Légende ───────────────────────────────────────────────────────────────

    private fun creerLegende(ctx: Context): LinearLayout {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(ctx,4), 0, dp(ctx,4))
        }
        data class Item(val couleur: Int, val texte: String)
        listOf(
            Item(0xFF1E88E5.toInt(), "Normal"),
            Item(0xFF00BCD4.toInt(), "Aujourd'hui"),
            Item(0xFFFFA726.toInt(), "Proche limite"),
            Item(0xFFE53935.toInt(), "Dépassé")
        ).forEach { item ->
            layout.addView(TextView(ctx).apply {
                text = "● ${item.texte}"; textSize = 10f
                setTextColor(item.couleur)
                setPadding(0, 0, dp(ctx, 12), 0)
            })
        }
        return layout
    }

    // ── Filtres ───────────────────────────────────────────────────────────────

    private fun configurerFiltres(vue: View) {
        val btnJour  = vue.findViewById<com.google.android.material.card.MaterialCardView>(R.id.btnFiltreJour)
        val btnMois  = vue.findViewById<com.google.android.material.card.MaterialCardView>(R.id.btnFiltreMois)
        val btnAnnee = vue.findViewById<com.google.android.material.card.MaterialCardView>(R.id.btnFiltreAnnee)
        val tvJour   = vue.findViewById<TextView>(R.id.tvLibelleFiltreJour)
        val tvMois   = vue.findViewById<TextView>(R.id.tvLibelleFiltreMois)
        val tvAnnee  = vue.findViewById<TextView>(R.id.tvLibelleFiltreAnnee)

        fun select(choix: String) {
            filtreActif = choix
            val ctx = requireContext()
            listOf(Triple(btnJour,tvJour,"Jour"), Triple(btnMois,tvMois,"Mois"), Triple(btnAnnee,tvAnnee,"Année"))
                .forEach { (c, t, lib) ->
                    val actif = lib == choix
                    c?.setCardBackgroundColor(ContextCompat.getColor(ctx, if (actif) R.color.accent_bleu else R.color.carte_verre))
                    t?.setTextColor(ContextCompat.getColor(ctx, if (actif) R.color.fond_application else R.color.texte_principal))
                }
            val tarif = AppRepository.getTarif(ctx)
            this.view?.let { rebatirEtAfficher(it, tarif) }
        }
        btnJour?.setOnClickListener  { select("Jour")  }
        btnMois?.setOnClickListener  { select("Mois")  }
        btnAnnee?.setOnClickListener { select("Année") }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private fun formaterEtiquette(etiquette: String, filtre: String): String {
        return try {
            when (filtre) {
                "Jour" -> {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
                    val cal = Calendar.getInstance().apply { time = sdf.parse(etiquette)!! }
                    val jourSem = joursSemaine[cal.get(Calendar.DAY_OF_WEEK) - 1]
                    val p = etiquette.split("-")
                    "$jourSem ${p[2]}\n${moisNoms[p[1].toInt()]}"
                }
                "Mois" -> {
                    val p = etiquette.split("-")
                    "${moisNoms[p[1].toInt()]}\n${p[0]}"
                }
                else -> etiquette
            }
        } catch (e: Exception) { etiquette }
    }

    private fun afficherVide(conteneur: LinearLayout, titre: String, sousTitre: String) {
        conteneur.removeAllViews()
        val ctx = conteneur.context
        conteneur.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(dp(ctx,32), dp(ctx,64), dp(ctx,32), dp(ctx,64))
            addView(TextView(ctx).apply {
                text = titre; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_principal))
                setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(ctx,12))
            })
            addView(TextView(ctx).apply {
                text = sousTitre; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                setLineSpacing(4f, 1f)
            })
        })
    }

    // ── Séparateur de section ─────────────────────────────────────────────────

    private fun creerSeparateurSection(ctx: Context, titre: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 20))
                    .also { it.marginEnd = dp(ctx, 10) }
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_bleu))
            })
            addView(TextView(ctx).apply {
                text = titre; textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    // ── En-tête du tableau ────────────────────────────────────────────────────

    private fun creerEnteteTable(ctx: Context, filtre: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x18FFFFFF)
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(TextView(ctx).apply {
                text = when (filtre) { "Mois" -> "MOIS"; "Année" -> "ANNÉE"; else -> "DATE" }
                textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            addView(TextView(ctx).apply {
                text = "VOLUME"; textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                letterSpacing = 0.05f; gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })
            addView(TextView(ctx).apply {
                text = "COÛT"; textSize = 10f; setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                letterSpacing = 0.05f; gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })
        }
    }

    // ── Ligne de détail ───────────────────────────────────────────────────────

    private fun creerLigneTable(
        ctx: Context, entree: EntreeHistorique,
        filtre: String, todayKey: String,
        seuil: Float, tarif: Float
    ): LinearLayout {
        val estAujourd = entree.etiquette == todayKey
        val valM3      = entree.volumeLitres / 1000f
        val depasse    = filtre == "Jour" && valM3 > seuil && seuil > 0

        // Couleur de la ligne
        val couleurTexte = when {
            estAujourd -> ContextCompat.getColor(ctx, R.color.accent_bleu)
            depasse    -> ContextCompat.getColor(ctx, R.color.accent_rouge)
            else       -> ContextCompat.getColor(ctx, R.color.texte_principal)
        }

        // Label de date lisible
        val labelDate = try {
            when (filtre) {
                "Jour" -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
                    val cal = java.util.Calendar.getInstance().apply { time = sdf.parse(entree.etiquette)!! }
                    val j   = joursSemaine[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                    val p   = entree.etiquette.split("-")
                    "$j ${p[2]} ${moisNoms[p[1].toInt()]} ${p[0]}"
                }
                "Mois" -> {
                    val p = entree.etiquette.split("-")
                    "${moisNoms[p[1].toInt()]} ${p[0]}"
                }
                else -> entree.etiquette
            }
        } catch (e: Exception) { entree.etiquette }

        // Volume formaté
        val texteVolume = when (filtre) {
            "Jour"  -> String.format("%.3f L", entree.volumeLitres)
            else    -> String.format("%.3f m³", valM3)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 11), dp(ctx, 8), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            // Indicateur coloré à gauche
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 24))
                    .also { it.marginEnd = dp(ctx, 8) }
                setBackgroundColor(when {
                    estAujourd -> ContextCompat.getColor(ctx, R.color.accent_bleu)
                    depasse    -> ContextCompat.getColor(ctx, R.color.accent_rouge)
                    else       -> ContextCompat.getColor(ctx, R.color.contour_carte)
                })
            })

            // Date
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                addView(TextView(ctx).apply {
                    text = labelDate + if (estAujourd) "  🔴" else if (depasse) "  ⚠️" else ""
                    textSize = 13f
                    setTextColor(couleurTexte)
                    if (estAujourd) setTypeface(null, Typeface.BOLD)
                })
                if (estAujourd) {
                    addView(TextView(ctx).apply {
                        text = "En cours (live)"; textSize = 10f
                        setTextColor(ContextCompat.getColor(ctx, R.color.accent_bleu))
                    })
                } else if (depasse) {
                    addView(TextView(ctx).apply {
                        text = "Seuil dépassé"; textSize = 10f
                        setTextColor(ContextCompat.getColor(ctx, R.color.accent_rouge))
                    })
                }
            })

            // Volume
            addView(TextView(ctx).apply {
                text = texteVolume
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(couleurTexte)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })

            // Coût
            addView(TextView(ctx).apply {
                text = String.format("%,.0f FC", entree.coutFc)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })
        }
    }

    private fun espaceur(ctx: Context, dpSize: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, dpSize))
    }

    private fun dp(ctx: Context, value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
}