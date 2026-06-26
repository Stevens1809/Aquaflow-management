package com.example.gestionaquaflow

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SettingsFragment — Réglages
 *
 * ═══════════════════════════════════════════════════════════════════
 * CAPTEUR — Comment ça fonctionnait mal :
 *
 *   Firebase renvoie IMMÉDIATEMENT les données cachées (dernière valeur
 *   connue) dès que le listener est ajouté, même si l'ESP32 est éteint.
 *   Avec l'horloge du téléphone seule : tsDerniereReception = now → ageSec = 0
 *   → "● Actif" alors que le capteur est physiquement déconnecté.
 *
 * Double stratégie selon fiabilité du NTP Le Network Time Protocol :
 *
 *   NTP fiable (timestampEsp32Ms > janv. 2023) :
 *     ageSec = (System.now() - timestampEsp32Ms) / 1000
 *     → Si l'ESP32 est éteint depuis hier, ageSec = ~86400 s → "Hors ligne" ✓
 *     → Si l'ESP32 vient d'envoyer, ageSec ≈ 0-10 s → "Actif" ✓
 *
 *   NTP non fiable (timestampEsp32Ms = 0) :
 *     Comptage de callbacks : Firebase tire les données cached une seule
 *     fois si l'ESP32 est mort. Si l'ESP32 est vivant il envoie toutes
 *     les 10 s → Firebase fire à nouveau → callbackCount ≥ 2.
 *     - callbackCount = 1 : attendre 30 s avant de déclarer "Aucun signal"
 *     - callbackCount ≥ 2 : ESP32 prouvé vivant → tsDerniereReception = now
 * ═══════════════════════════════════════════════════════════════════
 */
class SettingsFragment : BaseFragment() {

    private var listenerStatut: ValueEventListener? = null

    // ── Détection fiabilité capteur ───────────────────────────────────────
    private val EPOQUE_MIN_MS = 1672531200000L  // 1er janv. 2023 — en dessous = NTP KO
    private var callbackCount        = 0
    private var tsPremierCallback    = 0L   // ms téléphone, set au 1er callback
    private var tsDerniereReception  = 0L   // ms téléphone, set quand on est sûr
    private var dernierDebit         = 0f
    private var dernierVolume        = 0f
    private var aDonneesFiables      = false

    private val handler = Handler(Looper.getMainLooper())
    private val runnableCompteur = object : Runnable {
        override fun run() {
            view?.let { actualiserAffichageAge(it) }
            handler.postDelayed(this, 1000)
        }
    }

    private val lanceurPermissionNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { accorde ->
        val ctx = context ?: return@registerForActivityResult
        Toast.makeText(ctx,
            if (accorde) "✅ Notifications activées" else "❌ Permission refusée",
            Toast.LENGTH_LONG).show()
        view?.let { mettreAJourStatutPermission(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val vue = inflater.inflate(R.layout.fragment_settings, container, false)
        setupEnteteEtTheme(vue)
        val ctx = requireContext()

        val champNom      = vue.findViewById<TextInputEditText>(R.id.etNomAbonne)
        val champCompteur = vue.findViewById<TextInputEditText>(R.id.etNumCompteur)
        val champAdresse  = vue.findViewById<TextInputEditText>(R.id.etAdresse)
        val champTarif    = vue.findViewById<TextInputEditText>(R.id.etTarif)
        val champSeuil    = vue.findViewById<TextInputEditText>(R.id.etSeuil)
        val switchNotifs  = vue.findViewById<SwitchMaterial>(R.id.switchNotifications)
        val champEmail    = vue.findViewById<TextInputEditText>(R.id.etEmail)
        val champMdp      = vue.findViewById<TextInputEditText>(R.id.etMotDePasse)
        val layoutEmail   = vue.findViewById<TextInputLayout>(R.id.layoutEmail)
        val layoutMdp     = vue.findViewById<TextInputLayout>(R.id.layoutMotDePasse)
        val btnCreer      = vue.findViewById<Button>(R.id.btnCreerCompte)
        val btnConnecter  = vue.findViewById<Button>(R.id.btnSeConnecter)
        val btnDeconnecter= vue.findViewById<Button>(R.id.btnSeDeconnecter)
        val tvStatut      = vue.findViewById<TextView>(R.id.tvStatutCompte)

        champNom.setText(AppRepository.getNomAbonne(ctx))
        champCompteur.setText(AppRepository.getNumCompteur(ctx))
        champAdresse.setText(AppRepository.getAdresse(ctx))
        champTarif.setText(AppRepository.getTarif(ctx).toInt().toString())
        champSeuil.setText(AppRepository.getSeuil(ctx).toInt().toString())
        switchNotifs.isChecked = AppRepository.estNotificationsActive(ctx)

        mettreAJourChips(vue)
        mettreAJourStatutPermission(vue)
        mettreAJourAffichageCompte(
            FirebaseRepository.estConnecte,
            FirebaseRepository.utilisateurConnecte?.email,
            layoutEmail, layoutMdp, btnCreer, btnConnecter, btnDeconnecter, tvStatut, vue
        )

        vue.findViewById<MaterialCardView>(R.id.btnActualiserStatut)?.setOnClickListener {
            reinitialiserEtatCapteur()
            demarrerListenerStatutCapteur(vue)
            Toast.makeText(ctx, "Statut mis à jour", Toast.LENGTH_SHORT).show()
        }

        vue.findViewById<Button>(R.id.btnDemanderPermissionNotif)?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                lanceurPermissionNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        switchNotifs.setOnCheckedChangeListener { _, checked ->
            AppRepository.sauvegarderConfig(ctx,
                champNom.text.toString().ifBlank { AppRepository.getNomAbonne(ctx) },
                champCompteur.text.toString().ifBlank { AppRepository.getNumCompteur(ctx) },
                champAdresse.text.toString().ifBlank { AppRepository.getAdresse(ctx) },
                champTarif.text.toString().toFloatOrNull() ?: AppRepository.getTarif(ctx),
                champSeuil.text.toString().toFloatOrNull() ?: AppRepository.getSeuil(ctx),
                checked, false)
        }

        btnCreer?.setOnClickListener {
            val email = champEmail?.text.toString().trim()
            val mdp   = champMdp?.text.toString()
            if (email.isEmpty()) { layoutEmail?.error = "E-mail requis"; return@setOnClickListener }
            if (mdp.length < 6)  { layoutMdp?.error = "6 caractères minimum"; return@setOnClickListener }
            layoutEmail?.error = null; layoutMdp?.error = null
            btnCreer.isEnabled = false; btnCreer.text = "Création..."
            FirebaseRepository.creerCompte(email, mdp,
                onSucces = { user ->
                    Toast.makeText(ctx, "✅ Compte créé !", Toast.LENGTH_SHORT).show()
                    mettreAJourAffichageCompte(true, user.email,
                        layoutEmail, layoutMdp, btnCreer, btnConnecter, btnDeconnecter, tvStatut, vue)
                    reinitialiserEtatCapteur()
                    demarrerListenerStatutCapteur(vue)
                },
                onErreur = { msg ->
                    Toast.makeText(ctx, "❌ $msg", Toast.LENGTH_LONG).show()
                    btnCreer.isEnabled = true; btnCreer.text = "Créer un compte"
                }
            )
        }

        btnConnecter?.setOnClickListener {
            val email = champEmail?.text.toString().trim()
            val mdp   = champMdp?.text.toString()
            if (email.isEmpty()) { layoutEmail?.error = "E-mail requis"; return@setOnClickListener }
            if (mdp.isEmpty())   { layoutMdp?.error = "Mot de passe requis"; return@setOnClickListener }
            layoutEmail?.error = null; layoutMdp?.error = null
            btnConnecter.isEnabled = false; btnConnecter.text = "Connexion..."
            FirebaseRepository.seConnecter(email, mdp,
                onSucces = { user ->
                    Toast.makeText(ctx, "✅ Connecté !", Toast.LENGTH_SHORT).show()
                    mettreAJourAffichageCompte(true, user.email,
                        layoutEmail, layoutMdp, btnCreer, btnConnecter, btnDeconnecter, tvStatut, vue)
                    reinitialiserEtatCapteur()
                    demarrerListenerStatutCapteur(vue)
                },
                onErreur = { msg ->
                    Toast.makeText(ctx, "❌ $msg", Toast.LENGTH_LONG).show()
                    btnConnecter.isEnabled = true; btnConnecter.text = "Se connecter"
                }
            )
        }

        btnDeconnecter?.setOnClickListener {
            FirebaseRepository.seDeconnecter()
            arreterListenerStatutCapteur()
            mettreAJourAffichageCompte(false, null,
                layoutEmail, layoutMdp, btnCreer, btnConnecter, btnDeconnecter, tvStatut, vue)
            afficherCapteurHorsLigne(vue)
            Toast.makeText(ctx, "Déconnecté", Toast.LENGTH_SHORT).show()
        }

        vue.findViewById<Button>(R.id.btnEnregistrerConfig)?.setOnClickListener {
            val tarif = champTarif.text.toString().toFloatOrNull()
            val seuil = champSeuil.text.toString().toFloatOrNull()
            when {
                champNom.text.isNullOrBlank() -> { champNom.error = "Requis"; return@setOnClickListener }
                tarif == null || tarif <= 0   -> { champTarif.error = "Tarif invalide"; return@setOnClickListener }
                seuil == null || seuil <= 0   -> { champSeuil.error = "Seuil invalide"; return@setOnClickListener }
            }
            AppRepository.sauvegarderConfig(ctx,
                champNom.text.toString(), champCompteur.text.toString(),
                champAdresse.text.toString(), tarif!!, seuil!!,
                switchNotifs.isChecked, false)
            if (FirebaseRepository.estConnecte) {
                FirebaseRepository.sauvegarderProfil(
                    champNom.text.toString(), champCompteur.text.toString(),
                    champAdresse.text.toString(), tarif, seuil,
                    onSucces = { demarrerListenerStatutCapteur(vue) }
                )
            }
            mettreAJourChips(vue)
            mettreAJourEntete(vue)
            Toast.makeText(ctx, "✅ Configuration enregistrée", Toast.LENGTH_SHORT).show()
        }

        vue.findViewById<TextView>(R.id.tvVersionApp)?.text =
            "AquaFlow v1.0.0  •  YF-S201 + ESP32 + Firebase"

        return vue
    }

    override fun onResume() {
        super.onResume()
        view?.let {
            reinitialiserEtatCapteur()
            demarrerListenerStatutCapteur(it)
            handler.postDelayed(runnableCompteur, 1000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(runnableCompteur)
        arreterListenerStatutCapteur()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(runnableCompteur)
        arreterListenerStatutCapteur()
    }

    // ── Gestion d'état capteur ────────────────────────────────────────────────

    private fun reinitialiserEtatCapteur() {
        callbackCount       = 0
        tsPremierCallback   = 0L
        tsDerniereReception = 0L
        dernierDebit        = 0f
        dernierVolume       = 0f
        aDonneesFiables     = false
    }

    private fun demarrerListenerStatutCapteur(vue: View) {
        val ctx = requireContext()

        // ── Badge Firebase ────────────────────────────────────────────────
        vue.findViewById<MaterialCardView>(R.id.badgeFirebase)
            ?.setCardBackgroundColor(ContextCompat.getColor(ctx,
                if (FirebaseRepository.estConnecte) R.color.accent_vert else R.color.accent_rouge))
        vue.findViewById<TextView>(R.id.tvBadgeFirebase)?.text =
            if (FirebaseRepository.estConnecte) "✓ Connecté" else "✗ Hors ligne"
        vue.findViewById<TextView>(R.id.tvStatutFirebaseDetail)?.apply {
            text = if (FirebaseRepository.estConnecte)
                FirebaseRepository.utilisateurConnecte?.email ?: "Compte actif"
            else "Non connecté — allez dans Compte Firebase ci-dessous"
            setTextColor(ContextCompat.getColor(ctx,
                if (FirebaseRepository.estConnecte) R.color.accent_vert else R.color.accent_rouge))
        }

        if (!FirebaseRepository.estConnecte) { afficherCapteurHorsLigne(vue); return }

        arreterListenerStatutCapteur()

        // Badge d'attente initial
        vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "◌ Vérification..."
        vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
            text = "Interrogation de l'ESP32 en cours..."
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
        }

        listenerStatut = FirebaseRepository.ecouterStatutCapteur(
            onMiseAJour = { donnees, aDonnees, timestampEsp32Ms ->
                callbackCount++

                if (!aDonnees) {
                    // Nœud vide = jamais écrit
                    vue.findViewById<MaterialCardView>(R.id.badgeCapteur)
                        ?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                    vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "◌ Jamais connecté"
                    vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
                        text = "L'ESP32 n'a encore jamais envoyé de données\nVérifiez alimentation et Wi-Fi"
                        setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                    }
                    return@ecouterStatutCapteur
                }

                dernierDebit  = donnees.debit
                dernierVolume = donnees.volumeJour

                if (timestampEsp32Ms > EPOQUE_MIN_MS) {
                    // ── CAS 1 : NTP fiable ────────────────────────────────
                    // On utilise le timestamp ESP32 directement.
                    // Si l'ESP32 est éteint depuis hier, l'âge sera énorme → "Hors ligne" ✓
                    // Si l'ESP32 vient d'envoyer, l'âge ≈ 0-10 s → "Actif" ✓
                    tsDerniereReception = timestampEsp32Ms
                    aDonneesFiables     = true
                } else {
                    // ── CAS 2 : NTP non fiable (timestamp_unix = 0) ───────
                    // On compte les callbacks Firebase :
                    //   - 1er callback : peut être les données cached (ESP32 éteint)
                    //   - 2e+ callback : Firebase ne renvoie que si les données CHANGENT
                    //     → l'ESP32 a réécrit → il est vivant
                    if (callbackCount == 1) {
                        tsPremierCallback = System.currentTimeMillis()
                        // Pas encore fiable — le Handler montrera le compte à rebours
                    } else {
                        // callbackCount ≥ 2 → ESP32 confirmé vivant
                        tsDerniereReception = System.currentTimeMillis()
                        aDonneesFiables     = true
                    }
                }

                this.view?.let { actualiserAffichageAge(it) }

                val heure = SimpleDateFormat("HH:mm:ss", Locale.FRENCH).format(Date())
                vue.findViewById<TextView>(R.id.tvDerniereSynchro)?.text = "Aujourd'hui à $heure"
            },
            onErreur = { msg ->
                vue.findViewById<MaterialCardView>(R.id.badgeCapteur)
                    ?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_rouge))
                vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "✗ Accès refusé"
                vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
                    text = "Règle Firebase — connectez-vous avec le bon compte ($msg)"
                    setTextColor(ContextCompat.getColor(ctx, R.color.accent_rouge))
                }
            }
        )

        val heure = SimpleDateFormat("HH:mm:ss", Locale.FRENCH).format(Date())
        vue.findViewById<TextView>(R.id.tvDerniereSynchro)?.text = "Aujourd'hui à $heure"
    }

    /**
     * actualiserAffichageAge — appelée chaque seconde par le Handler.
     *
     * CAS 1 (NTP fiable) : ageSec depuis le timestamp ESP32
     * CAS 2 (NTP KO, callbackCount=1) : compte à rebours 30 s avant "Aucun signal"
     * CAS 2 (NTP KO, callbackCount≥2) : ageSec depuis tsDerniereReception (téléphone)
     */
    private fun actualiserAffichageAge(vue: View) {
        val ctx = requireContext()

        if (!aDonneesFiables) {
            // NTP non fiable, en attente du 2e callback ou timeout
            if (tsPremierCallback == 0L) return
            val attenteSec = (System.currentTimeMillis() - tsPremierCallback) / 1000L
            if (attenteSec < 30) {
                vue.findViewById<MaterialCardView>(R.id.badgeCapteur)
                    ?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "◌ Vérification..."
                vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
                    text = "En attente du prochain signal ESP32 (${30 - attenteSec}s)..."
                    setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
                }
            } else {
                // 30 s sans 2e callback → ESP32 ne répond pas
                vue.findViewById<MaterialCardView>(R.id.badgeCapteur)
                    ?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_rouge))
                vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "✗ Aucun signal"
                vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
                    text = "ESP32 ne répond pas — vérifier alimentation et Wi-Fi"
                    setTextColor(ContextCompat.getColor(ctx, R.color.accent_rouge))
                }
            }
            return
        }

        // NTP fiable ou 2e callback confirmé → calculer l'âge
        val ageSec = (System.currentTimeMillis() - tsDerniereReception) / 1000L
        val (couleurRes, badge, detail) = when {
            ageSec < 30   -> Triple(R.color.accent_vert,  "● Actif",
                "Débit : ${String.format("%.3f", dernierDebit)} L/min  •  il y a ${ageSec}s")
            ageSec < 180  -> Triple(R.color.accent_vert,  "● Récent",
                "Volume : ${String.format("%.3f", dernierVolume)} L  •  il y a ${ageSec}s")
            ageSec < 600  -> Triple(R.color.accent_jaune, "⚠ Silencieux",
                "Dernière donnée il y a ${ageSec / 60} min — ESP32 en veille ?")
            else          -> Triple(R.color.accent_rouge, "✗ Hors ligne",
                "Aucun signal depuis ${ageSec / 60} min — vérifier alimentation")
        }

        vue.findViewById<MaterialCardView>(R.id.badgeCapteur)
            ?.setCardBackgroundColor(ContextCompat.getColor(ctx, couleurRes))
        vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = badge
        vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
            text = detail
            setTextColor(ContextCompat.getColor(ctx, couleurRes))
        }
    }

    private fun arreterListenerStatutCapteur() {
        FirebaseRepository.arreterEcoute(listenerStatut)
        listenerStatut = null
    }

    private fun afficherCapteurHorsLigne(vue: View) {
        val ctx = requireContext()
        vue.findViewById<MaterialCardView>(R.id.badgeCapteur)?.setCardBackgroundColor(0xFF555555.toInt())
        vue.findViewById<TextView>(R.id.tvBadgeCapteur)?.text = "— Inconnu"
        vue.findViewById<TextView>(R.id.tvStatutCapteurDetail)?.apply {
            text = "Connexion Firebase requise"
            setTextColor(ContextCompat.getColor(ctx, R.color.texte_secondaire))
        }
        vue.findViewById<TextView>(R.id.tvDerniereSynchro)?.text = "Jamais"
    }

    // ── Utilitaires UI ────────────────────────────────────────────────────────

    private fun mettreAJourChips(vue: View) {
        val ctx = requireContext()
        vue.findViewById<TextView>(R.id.tvTarifActuel)?.text =
            "${AppRepository.getTarif(ctx).toInt()} FC/m³"
        vue.findViewById<TextView>(R.id.tvSeuilActuel)?.text =
            String.format("%.3f m³/j", AppRepository.getSeuil(ctx))
    }

    private fun mettreAJourStatutPermission(vue: View) {
        val ctx = requireContext()
        val accordee = NotificationHelper.permissionAccordee(ctx)
        val besoin13 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        vue.findViewById<TextView>(R.id.tvStatutPermissionNotif)?.text =
            if (accordee) "🔔 Notifications : autorisées"
            else if (besoin13) "🔕 Permission requise (Android 13+)"
            else "🔔 Notifications disponibles"
        vue.findViewById<Button>(R.id.btnDemanderPermissionNotif)?.visibility =
            if (besoin13 && !accordee) View.VISIBLE else View.GONE
    }

    private fun mettreAJourAffichageCompte(
        connecte: Boolean, email: String?,
        layoutEmail: TextInputLayout?, layoutMdp: TextInputLayout?,
        btnCreer: Button?, btnConnecter: Button?, btnDeconnecter: Button?,
        tvStatut: TextView?, vue: View
    ) {
        val layoutStatut = vue.findViewById<LinearLayout>(R.id.layoutStatutConnecte)
        if (connecte) {
            layoutEmail?.visibility   = View.GONE; layoutMdp?.visibility = View.GONE
            btnCreer?.visibility      = View.GONE; btnConnecter?.visibility = View.GONE
            btnDeconnecter?.visibility = View.VISIBLE; layoutStatut?.visibility = View.VISIBLE
            tvStatut?.text = "✅ Connecté : $email"
        } else {
            layoutEmail?.visibility   = View.VISIBLE; layoutMdp?.visibility = View.VISIBLE
            btnCreer?.visibility      = View.VISIBLE; btnConnecter?.visibility = View.VISIBLE
            btnDeconnecter?.visibility = View.GONE; layoutStatut?.visibility = View.GONE
            btnCreer?.isEnabled = true; btnCreer?.text = "Créer un compte"
            btnConnecter?.isEnabled = true; btnConnecter?.text = "Se connecter"
        }
    }
}
