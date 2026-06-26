package com.example.gestionaquaflow

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════
 * FirebaseRepository — Couche d'accès à tous les services Firebase
 * ═══════════════════════════════════════════════════════════════════
 *
 * ARCHITECTURE GÉNÉRALE :
 *
 *   ESP32 (YF-S201)
 *      │  Wi-Fi + HTTPS toutes les 10 secondes
 *      ▼
 *   Firebase Realtime Database  ← chemin fixe, UID du capteur hardcodé
 *      └─ compteurs/UID_CAPTEUR/temps_reel/
 *              debit          : 3.7   (L/min)
 *              volume_jour    : 47    (Litres)
 *              timestamp_unix : 1712750400  (secondes Unix)
 *      └─ compteurs/UID_CAPTEUR/historique_journalier/
 *              {YYYY-MM-DD}/
 *                  date          : "2025-04-10"
 *                  volume_litres : 198.0
 *                  volume        : 198.0
 *                  timestamp_unix: ...
 *
 *   Firebase Firestore          ← chemin par utilisateur connecté
 *      └─ utilisateurs/{uid}/
 *              └─ historique/{YYYY-MM}/
 *                      volume_m3   : 6.8
 *                      cout_fc     : 1836
 *                      mois        : "2025-03"
 *                      └─ jours/{YYYY-MM-DD}/
 *                              volume_litres : 198
 *                              cout_fc       : 53.46
 *                              date          : "2025-03-28"
 *
 *   Firebase Authentication
 *      └─ L'app doit être connectée avec le compte dont l'UID
 *         correspond à UID_CAPTEUR pour que les règles de sécurité
 *         Realtime DB autorisent la lecture.
 *
 *   1. UID_CAPTEUR — constante séparant l'UID de l'ESP32 (chemin RTDB)
 *      de l'UID de l'utilisateur connecté (chemin Firestore).
 *      → Tous les accès RTDB utilisent UID_CAPTEUR.
 *      → Tous les accès Firestore utilisent auth.currentUser?.uid.
 *   2. snapshot.exists() — vérification avant de lire les données dans
 *      onDataChange : si le nœud n'existe pas encore (ESP32 pas démarré),
 *      on ne propage pas de données vides, l'UI reste en "En attente…".
 *   3. arreterEcoute — ne dépend plus de auth.currentUser?.uid.
 *      Dans l'ancienne version, si l'utilisateur était déconnecté au
 *      moment du onPause(), le listener n'était jamais retiré (fuite mémoire).
 */
object FirebaseRepository {

    // ── UID fixe de l'ESP32 / du capteur ────────────────────────────────
    // FIX 1 : L'ESP32 écrit toujours sous cet UID hardcodé.
    // Tous les accès Realtime DB utilisent cette constante.
    // L'app doit être connectée avec le compte dont l'UID est égal à cette
    // valeur pour satisfaire la règle ".read: $uid === auth.uid" de la RTDB.
    private const val UID_CAPTEUR = "Y6LGKNzcR9TzUxusU6IOateQHvl1"

    // ── Références aux services Firebase ────────────────────────────────
    private val auth       = FirebaseAuth.getInstance()
    private val database   = FirebaseDatabase.getInstance()
    private val firestore  = FirebaseFirestore.getInstance()

    private val formatMois = SimpleDateFormat("yyyy-MM",    Locale.FRENCH)
    private val formatJour = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)

    // ── Accès à l'utilisateur connecté ──────────────────────────────────
    val utilisateurConnecte: FirebaseUser?
        get() = auth.currentUser

    /** UID de l'utilisateur connecté — utilisé UNIQUEMENT pour Firestore */
    val uid: String?
        get() = auth.currentUser?.uid

    val estConnecte: Boolean
        get() = auth.currentUser != null

    // ── Extensions internes de lecture de snapshot ───────────────────────
    private fun DataSnapshot.nombreFloat(): Float {
        val valeur = value
        return when (valeur) {
            is Number -> valeur.toFloat()
            is String -> valeur.replace(',', '.').toFloatOrNull() ?: 0f
            else -> 0f
        }
    }

    private fun DataSnapshot.nombreLong(): Long {
        val valeur = value
        return when (valeur) {
            is Number -> valeur.toLong()
            is String -> valeur.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun DataSnapshot.premierFloat(vararg champs: String): Float {
        champs.forEach { champ ->
            val enfant = child(champ)
            if (enfant.exists()) return enfant.nombreFloat()
        }
        return nombreFloat()
    }

    private fun DataSnapshot.versEntreeHistorique(tarifFcM3: Float): EntreeHistorique? {
        // Priorité : champ "date" valide, sinon clé du nœud
        val dateCandidat = child("date").getValue(String::class.java)
            ?.takeIf { it.length >= 10 }
            ?: key?.takeIf { it.length == 10 }
            ?: return null
        // VALIDATION FORMAT AAAA-MM-JJ :
        // Quand le NTP de l'ESP32 échoue, obtenirDate() retourne "" et les données
        // sont écrites directement sous historique_journalier/ (sans clé de date).
        // Les clés parasites ("volume_litres", "timestamp_unix"…) ont une longueur ≥ 10
        // et passeraient sans cette vérification, corrompant l'affichage.
        if (!dateCandidat.take(10).matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
        val date = dateCandidat

        val volumeLitres   = premierFloat("volume_litres", "volume", "volume_jour")
        val coutEnregistre = premierFloat("cout_fc", "cout", "coutFc")
        val coutFc = if (coutEnregistre > 0f) coutEnregistre else (volumeLitres / 1000f) * tarifFcM3

        return EntreeHistorique(
            etiquette    = date.take(10),
            volumeLitres = volumeLitres,
            coutFc       = coutFc
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // SECTION 1 — AUTHENTIFICATION
    // ════════════════════════════════════════════════════════════════════

    fun creerCompte(
        email: String,
        motDePasse: String,
        onSucces: (FirebaseUser) -> Unit,
        onErreur: (String) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, motDePasse)
            .addOnSuccessListener { resultat ->
                val utilisateur = resultat.user ?: return@addOnSuccessListener
                creerProfilFirestore(utilisateur.uid, email)
                onSucces(utilisateur)
            }
            .addOnFailureListener { exception ->
                val message = when {
                    exception.message?.contains("email address is already") == true ->
                        "Cette adresse e-mail est déjà utilisée"
                    exception.message?.contains("badly formatted") == true ->
                        "Format d'e-mail invalide"
                    exception.message?.contains("weak-password") == true ->
                        "Mot de passe trop court (minimum 6 caractères)"
                    else -> exception.message ?: "Erreur de création du compte"
                }
                onErreur(message)
            }
    }

    fun seConnecter(
        email: String,
        motDePasse: String,
        onSucces: (FirebaseUser) -> Unit,
        onErreur: (String) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, motDePasse)
            .addOnSuccessListener { resultat ->
                onSucces(resultat.user ?: return@addOnSuccessListener)
            }
            .addOnFailureListener { exception ->
                val message = when {
                    exception.message?.contains("no user record") == true ->
                        "Aucun compte trouvé pour cet e-mail"
                    exception.message?.contains("password is invalid") == true ->
                        "Mot de passe incorrect"
                    else -> "Erreur de connexion : ${exception.message}"
                }
                onErreur(message)
            }
    }

    fun seDeconnecter() = auth.signOut()

    // ════════════════════════════════════════════════════════════════════
    // SECTION 2 — PROFIL UTILISATEUR (Firestore — UID dynamique)
    // ════════════════════════════════════════════════════════════════════

    private fun creerProfilFirestore(uid: String, email: String) {
        val profil = hashMapOf(
            "email"         to email,
            "nom_abonne"    to AppRepository.DEFAUT_NOM_ABONNE,
            "num_compteur"  to AppRepository.DEFAUT_NUM_COMPTEUR,
            "adresse"       to AppRepository.DEFAUT_ADRESSE,
            "tarif_fc_m3"   to AppRepository.DEFAUT_TARIF,
            "seuil_m3_jour" to AppRepository.DEFAUT_SEUIL,
            "date_creation" to System.currentTimeMillis()
        )
        firestore.collection("utilisateurs").document(uid).set(profil)
    }

    fun sauvegarderProfil(
        nomAbonne: String, numCompteur: String, adresse: String,
        tarif: Float, seuil: Float,
        onSucces: () -> Unit = {},
        onErreur: (String) -> Unit = {}
    ) {
        val uid = uid ?: return onErreur("Utilisateur non connecté")
        val miseAJour = hashMapOf<String, Any>(
            "nom_abonne"    to nomAbonne,
            "num_compteur"  to numCompteur,
            "adresse"       to adresse,
            "tarif_fc_m3"   to tarif,
            "seuil_m3_jour" to seuil
        )
        firestore.collection("utilisateurs").document(uid)
            .update(miseAJour)
            .addOnSuccessListener { onSucces() }
            .addOnFailureListener { onErreur(it.message ?: "Erreur") }
    }

    // ════════════════════════════════════════════════════════════════════
    // SECTION 3 — DONNÉES TEMPS RÉEL (Realtime DB — UID_CAPTEUR fixe)
    // ════════════════════════════════════════════════════════════════════

    /**
     * ecouterDonneesTempsReel — Écoute le nœud écrit par l'ESP32.
     *
     * FIX 1 : utilise UID_CAPTEUR au lieu de auth.currentUser?.uid
     *         pour construire le chemin RTDB.
     * FIX 2 : vérifie snapshot.exists() avant de lire les valeurs.
     *         Si le nœud n'existe pas encore (ESP32 pas encore démarré),
     *         on ne propage pas de zéros — l'UI reste en "En attente…".
     */
    fun ecouterDonneesTempsReel(
        onDonnees: (DonneesTempsReel) -> Unit,
        onErreur: (String) -> Unit
    ): ValueEventListener? {
        // L'utilisateur doit être authentifié pour que les règles RTDB l'autorisent
        if (!estConnecte) return null

        // FIX 1 : chemin fixe lié à l'ESP32, pas à l'UID de l'utilisateur connecté
        val reference = database.getReference("compteurs/$UID_CAPTEUR/temps_reel")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // FIX 2 : ne rien propager si le nœud est vide
                // (ESP32 pas encore démarré ou premier lancement)
                if (!snapshot.exists()) return

                val timestampMs   = snapshot.child("timestamp").nombreLong()
                val timestampUnix = snapshot.child("timestamp_unix").nombreLong()
                val donnees = DonneesTempsReel(
                    debit      = snapshot.child("debit").nombreFloat(),
                    volumeJour = snapshot.child("volume_jour").nombreFloat(),
                    pression   = snapshot.child("pression").nombreFloat(),
                    timestamp  = when {
                        timestampMs   > 0L -> timestampMs
                        timestampUnix > 0L -> timestampUnix * 1000L
                        else               -> System.currentTimeMillis()
                    }
                )
                onDonnees(donnees)
            }

            override fun onCancelled(error: DatabaseError) {
                onErreur(error.message)
            }
        }

        reference.addValueEventListener(listener)
        return listener
    }

    /**
     * arreterEcoute — Retire le listener de la Realtime Database.
     *
     * FIX 3 : utilise UID_CAPTEUR au lieu de auth.currentUser?.uid.
     * Dans l'ancienne version, si l'utilisateur était déconnecté au moment
     * du onPause(), uid revenait null et le listener n'était jamais retiré
     * → fuite mémoire. Avec UID_CAPTEUR, l'arrêt fonctionne quel que soit
     * l'état de l'authentification.
     */
    fun arreterEcoute(listener: ValueEventListener?) {
        if (listener == null) return
        database.getReference("compteurs/$UID_CAPTEUR/temps_reel")
            .removeEventListener(listener)
    }

    /**
     * ecouterStatutCapteur — Variante pour l'écran Réglages uniquement.
     *
     * Deux différences clés vs ecouterDonneesTempsReel :
     *   1. Appelle onMiseAJour même si snapshot.exists() = false
     *      → le badge ne reste plus bloqué à " Attente" si le nœud est vide.
     *   2. Le bool aDonnees signale si l'ESP32 a déjà écrit (false = jamais démarré).
     *      L'appelant utilise l'horloge du TÉLÉPHONE pour l'âge, pas donnees.timestamp
     *      → immunisé contre les bugs NTP de l'ESP32 (timestamp_unix = 0).
     */
    /**
     * ecouterStatutCapteur — Variante pour l'écran Réglages.
     *
     * Passe un 3e paramètre timestampEsp32Ms : valeur BRUTE de l'ESP32
     * SANS le fallback System.currentTimeMillis(). Vaut 0 si NTP non synchronisé.
     * SettingsFragment s'en sert pour distinguer :
     *   - timestamp > Jan 2023 → NTP fiable → calculer ageSec directement
     *   - timestamp = 0         → NTP non fiable → utiliser comptage de callbacks
     * Cette séparation évite le cas "capteur mort = Actif" causé par
     * Firebase renvoyant les données cachées dès l'ajout du listener.
     */
    fun ecouterStatutCapteur(
        onMiseAJour: (donnees: DonneesTempsReel, aDonnees: Boolean, timestampEsp32Ms: Long) -> Unit,
        onErreur: (String) -> Unit
    ): ValueEventListener? {
        if (!estConnecte) return null
        val reference = database.getReference("compteurs/$UID_CAPTEUR/temps_reel")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onMiseAJour(DonneesTempsReel(), false, 0L)
                    return
                }
                val timestampMs   = snapshot.child("timestamp").nombreLong()
                val timestampUnix = snapshot.child("timestamp_unix").nombreLong()
                // Timestamp BRUT sans fallback — 0 = NTP non synchronisé
                val timestampEsp32Ms = when {
                    timestampMs   > 0L -> timestampMs
                    timestampUnix > 0L -> timestampUnix * 1000L
                    else               -> 0L
                }
                val donnees = DonneesTempsReel(
                    debit      = snapshot.child("debit").nombreFloat(),
                    volumeJour = snapshot.child("volume_jour").nombreFloat(),
                    pression   = snapshot.child("pression").nombreFloat(),
                    timestamp  = if (timestampEsp32Ms > 0L) timestampEsp32Ms
                    else System.currentTimeMillis()
                )
                onMiseAJour(donnees, true, timestampEsp32Ms)
            }
            override fun onCancelled(error: DatabaseError) = onErreur(error.message)
        }
        reference.addValueEventListener(listener)
        return listener
    }

    // ════════════════════════════════════════════════════════════════════
    // SECTION 3b — ÉCOUTE LIVE DE L'HISTORIQUE JOURNALIER (Realtime DB)
    // Utilisé par HistoryFragment pour afficher données passées + aujourd'hui
    // en temps réel (se met à jour toutes les ~10 s avec l'ESP32).
    // ════════════════════════════════════════════════════════════════════

    /**
     * ecouterHistoriqueJournalier — Listener permanent sur le nœud
     * historique_journalier. Appelé à chaque écriture ESP32.
     *
     * Retourne TOUTES les entrées (tous les jours passés + aujourd'hui).
     * L'appelant filtre par Jour / Mois / Année selon son besoin.
     */
    fun ecouterHistoriqueJournalier(
        tarifFcM3: Float,
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ): ValueEventListener? {
        if (!estConnecte) return null

        val reference = database.getReference("compteurs/$UID_CAPTEUR/historique_journalier")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val entrees = snapshot.children
                    .mapNotNull { it.versEntreeHistorique(tarifFcM3) }
                    .distinctBy { it.etiquette }
                    .sortedByDescending { it.etiquette }
                onResultat(entrees)
            }
            override fun onCancelled(error: DatabaseError) = onErreur(error.message)
        }

        reference.addValueEventListener(listener)
        return listener
    }

    /** Retire le listener posé par ecouterHistoriqueJournalier. */
    fun arreterEcouteHistorique(listener: ValueEventListener?) {
        if (listener == null) return
        database.getReference("compteurs/$UID_CAPTEUR/historique_journalier")
            .removeEventListener(listener)
    }

    // ════════════════════════════════════════════════════════════════════
    // SECTION 4 — HISTORIQUE (RTDB UID_CAPTEUR + Firestore UID dynamique)
    // ════════════════════════════════════════════════════════════════════

    fun sauvegarderDonneeJournaliere(
        volumeLitres: Float,
        tarifFcM3: Float,
        onSucces: () -> Unit = {},
        onErreur: (String) -> Unit = {}
    ) {
        // Firestore : UID de l'utilisateur connecté
        val uid = uid ?: return onErreur("Non connecté")
        val maintenant = Calendar.getInstance()
        val cleMois = formatMois.format(maintenant.time)
        val cleJour = formatJour.format(maintenant.time)

        val volumeM3 = volumeLitres / 1000f
        val coutFc   = volumeM3 * tarifFcM3

        val donneeJour = hashMapOf(
            "date"          to cleJour,
            "volume_litres" to volumeLitres,
            "volume_m3"     to volumeM3,
            "cout_fc"       to coutFc,
            "timestamp"     to System.currentTimeMillis()
        )

        firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(cleMois)
            .collection("jours").document(cleJour)
            .set(donneeJour)
            .addOnSuccessListener {
                mettreAJourResumeMensuel(uid, cleMois, volumeM3, coutFc)
                onSucces()
            }
            .addOnFailureListener { onErreur(it.message ?: "Erreur") }
    }

    private fun mettreAJourResumeMensuel(
        uid: String, cleMois: String,
        volumeJourM3: Float, coutJourFc: Float
    ) {
        val resumeRef = firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(cleMois)

        resumeRef.update(
            "volume_total_m3", com.google.firebase.firestore.FieldValue.increment(volumeJourM3.toDouble()),
            "cout_total_fc",   com.google.firebase.firestore.FieldValue.increment(coutJourFc.toDouble()),
            "mois",            cleMois
        ).addOnFailureListener {
            resumeRef.set(hashMapOf(
                "mois"            to cleMois,
                "volume_total_m3" to volumeJourM3,
                "cout_total_fc"   to coutJourFc
            ))
        }
    }

    /**
     * lireHistoriqueJournalier — Lit d'abord la RTDB (données ESP32),
     * puis bascule sur Firestore si la RTDB est vide.
     *
     * FIX 1 : chemin RTDB utilise UID_CAPTEUR.
     * Le chemin Firestore (fallback) utilise l'UID de l'utilisateur connecté.
     */
    fun lireHistoriqueJournalier(
        tarifFcM3: Float = AppRepository.DEFAUT_TARIF,
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ) {
        if (!estConnecte) return onErreur("Non connecté")

        // FIX 1 : UID_CAPTEUR pour la RTDB
        database.getReference("compteurs/$UID_CAPTEUR/historique_journalier")
            .get()
            .addOnSuccessListener { snapshot ->
                val entreesRealtime = snapshot.children
                    .mapNotNull { it.versEntreeHistorique(tarifFcM3) }
                    .distinctBy { it.etiquette }
                    .sortedByDescending { it.etiquette }

                if (entreesRealtime.isNotEmpty()) {
                    onResultat(entreesRealtime)
                } else {
                    lireHistoriqueJournalierFirestore(tarifFcM3, onResultat, onErreur)
                }
            }
            .addOnFailureListener {
                lireHistoriqueJournalierFirestore(tarifFcM3, onResultat, onErreur)
            }
    }

    /**
     * lireHistoriqueMensuel — Groupe les jours par mois depuis la RTDB,
     * bascule sur Firestore si la RTDB est vide.
     *
     * FIX 1 : chemin RTDB utilise UID_CAPTEUR.
     */
    fun lireHistoriqueMensuel(
        tarifFcM3: Float = AppRepository.DEFAUT_TARIF,
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ) {
        if (!estConnecte) return onErreur("Non connecté")

        // FIX 1 : UID_CAPTEUR pour la RTDB
        database.getReference("compteurs/$UID_CAPTEUR/historique_journalier")
            .get()
            .addOnSuccessListener { snapshot ->
                val entreesJournalieres = snapshot.children
                    .mapNotNull { it.versEntreeHistorique(tarifFcM3) }

                val entreesMensuelles = entreesJournalieres
                    .filter { it.etiquette.length >= 7 }
                    .groupBy { it.etiquette.take(7) }
                    .map { (mois, jours) ->
                        EntreeHistorique(
                            etiquette    = mois,
                            volumeLitres = jours.sumOf { it.volumeLitres.toDouble() }.toFloat(),
                            coutFc       = jours.sumOf { it.coutFc.toDouble() }.toFloat()
                        )
                    }
                    .sortedBy { it.etiquette }

                if (entreesMensuelles.isNotEmpty()) {
                    onResultat(entreesMensuelles.takeLast(12))
                } else {
                    lireHistoriqueMensuelFirestore(onResultat, onErreur)
                }
            }
            .addOnFailureListener {
                lireHistoriqueMensuelFirestore(onResultat, onErreur)
            }
    }

    // Firestore fallback — UID dynamique de l'utilisateur connecté
    private fun lireHistoriqueJournalierFirestore(
        tarifFcM3: Float,
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ) {
        val uid = uid ?: return onErreur("Non connecté")
        val cleMois = formatMois.format(Calendar.getInstance().time)

        firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(cleMois)
            .collection("jours")
            .orderBy("date", Query.Direction.DESCENDING)
            .limit(31)
            .get()
            .addOnSuccessListener { documents ->
                val entrees = documents.map { doc ->
                    val volumeLitres  = (doc.getDouble("volume_litres") ?: 0.0).toFloat()
                    val coutFirestore = (doc.getDouble("cout_fc") ?: 0.0).toFloat()
                    EntreeHistorique(
                        etiquette    = doc.getString("date") ?: "",
                        volumeLitres = volumeLitres,
                        coutFc       = if (coutFirestore > 0f) coutFirestore else (volumeLitres / 1000f) * tarifFcM3
                    )
                }
                onResultat(entrees)
            }
            .addOnFailureListener { onErreur(it.message ?: "Erreur") }
    }

    private fun lireHistoriqueMensuelFirestore(
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ) {
        val uid = uid ?: return onErreur("Non connecté")

        firestore.collection("utilisateurs").document(uid)
            .collection("historique")
            .orderBy("mois", Query.Direction.DESCENDING)
            .limit(12)
            .get()
            .addOnSuccessListener { documents ->
                val entrees = documents.map { doc ->
                    EntreeHistorique(
                        etiquette    = doc.getString("mois") ?: "",
                        volumeLitres = ((doc.getDouble("volume_total_m3") ?: 0.0) * 1000).toFloat(),
                        coutFc       = (doc.getDouble("cout_total_fc") ?: 0.0).toFloat()
                    )
                }
                onResultat(entrees.reversed())
            }
            .addOnFailureListener { onErreur(it.message ?: "Erreur") }
    }

    // ════════════════════════════════════════════════════════════════════
    // SECTION 4b — FINALISATION IMMUABLE DES JOURNÉES
    // ════════════════════════════════════════════════════════════════════

    /**
     * mettreAJourJourLive — Mise à jour de l'entrée du JOUR COURANT dans Firestore.
     *
     * C'est la méthode critique qui manquait : elle sauvegarde les données
     * d'aujourd'hui toutes les 60 s en utilisant la DATE DU TÉLÉPHONE.
     * Indépendante du NTP de l'ESP32 — fonctionne même si l'ESP32 n'a pas de date.
     *
     * Règle : si l'entrée a finalise=true (jour passé finalisé), on ne touche PAS.
     *         Si finalise=false ou entrée absente, on écrit/écrase la valeur.
     *
     * Appelée par DashboardFragment toutes les 60 s quand volume > 0.
     */
    fun mettreAJourJourLive(
        date: String,        // date téléphone format "YYYY-MM-DD"
        volumeLitres: Float,
        tarifFcM3: Float
    ) {
        val uid = uid ?: return
        if (!estConnecte || volumeLitres <= 0f) return
        val cleMois = date.take(7)
        val jourRef = firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(cleMois)
            .collection("jours").document(date)

        jourRef.get().addOnSuccessListener { snapshot ->
            // Règle absolue : ne jamais toucher un jour finalisé
            if (snapshot.exists() && snapshot.getBoolean("finalise") == true) return@addOnSuccessListener

            val volumeM3 = volumeLitres / 1000f
            jourRef.set(hashMapOf(
                "date"          to date,
                "volume_litres" to volumeLitres,
                "volume_m3"     to volumeM3,
                "cout_fc"       to volumeM3 * tarifFcM3,
                "finalise"      to false,   // Pas encore terminé — sera mis à true à minuit
                "timestamp"     to System.currentTimeMillis()
            ))
        }
    }

    /**
     * finaliserJourFirestoreExistant — Finalise une entrée Firestore déjà présente
     * mais pas encore marquée finalise=true (ex: entrée live du jour précédent
     * quand l'app rouvre le lendemain et que le capteur n'était pas actif à minuit).
     */
    fun finaliserJourFirestoreExistant(date: String, tarif: Float) {
        val uid = uid ?: return
        val jourRef = firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(date.take(7))
            .collection("jours").document(date.take(10))

        jourRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) return@addOnSuccessListener
            if (snapshot.getBoolean("finalise") == true) return@addOnSuccessListener
            val volumeLitres = (snapshot.getDouble("volume_litres") ?: 0.0).toFloat()
            if (volumeLitres <= 0f) return@addOnSuccessListener
            jourRef.update(
                mapOf(
                    "finalise"      to true,
                    "timestamp_fin" to System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * finaliserJournee — Enregistrement définitif et IMMUABLE d'une journée.
     *
     * RÈGLE ABSOLUE : si un document Firestore avec finalise=true existe déjà
     * pour cette date, on ne fait RIEN — aucun écrasement, jamais.
     * Chaque journée est donc enregistrée une seule fois, pour toujours.
     *
     * Appelée par :
     *   • DashboardFragment lors d'un changement de jour détecté en direct
     *   • syncroniserRTDBversFirestore au démarrage (pour rattraper les jours passés)
     */
    fun finaliserJournee(
        date: String,
        volumeLitres: Float,
        tarifFcM3: Float,
        onSucces: () -> Unit = {},
        onErreur: (String) -> Unit = {}
    ) {
        val uid = uid ?: return onErreur("Non connecté")
        if (volumeLitres <= 0f) return
        val datePropre = date.take(10)
        if (!datePropre.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return

        val cleMois = datePropre.take(7)
        val jourRef = firestore.collection("utilisateurs").document(uid)
            .collection("historique").document(cleMois)
            .collection("jours").document(datePropre)

        jourRef.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.getBoolean("finalise") == true) {
                    onSucces()  // Déjà finalisé — on ne touche pas
                    return@addOnSuccessListener
                }
                val volumeM3 = volumeLitres / 1000f
                jourRef.set(hashMapOf(
                    "date"          to datePropre,
                    "volume_litres" to volumeLitres,
                    "volume_m3"     to volumeM3,
                    "cout_fc"       to volumeM3 * tarifFcM3,
                    "finalise"      to true,
                    "timestamp_fin" to System.currentTimeMillis()
                ))
                    .addOnSuccessListener { onSucces() }
                    .addOnFailureListener { onErreur(it.message ?: "Erreur Firestore") }
            }
            .addOnFailureListener { onErreur(it.message ?: "Erreur lecture") }
    }

    /**
     * syncroniserRTDBversFirestore — Copie tous les jours PASSÉS de RTDB vers Firestore.
     * Appelé à chaque onResume du Dashboard.
     *
     * Pour chaque entrée RTDB dont la date est valide et < aujourd'hui :
     *   → finaliserJournee() vérifie finalise=true avant toute écriture.
     *   → Résultat : accumulation progressive, sans doublon, sans écrasement.
     *
     * Couvre le cas où l'app était fermée pendant plusieurs jours : dès la
     * prochaine ouverture, TOUS les jours passés présents dans RTDB sont
     * automatiquement finalisés dans Firestore.
     */
    fun syncroniserRTDBversFirestore(tarifFcM3: Float) {
        if (!estConnecte) return
        val today = formatJour.format(Calendar.getInstance().time)

        database.getReference("compteurs/$UID_CAPTEUR/historique_journalier")
            .get()
            .addOnSuccessListener { snapshot ->
                for (child in snapshot.children) {
                    val date = child.key ?: continue
                    if (date == today) continue
                    if (!date.take(10).matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue

                    val volumeLitres = child.child("volume_litres").nombreFloat()
                        .takeIf { it > 0f }
                        ?: child.child("volume").nombreFloat().takeIf { it > 0f }
                        ?: continue

                    finaliserJournee(date, volumeLitres, tarifFcM3)
                }
            }
    }

    /**
     * lireHistoriqueCompletFirestore — Lit TOUS les mois de Firestore (entrées finalisées).
     * Source primaire de l'historique : immuable, fiable, complète.
     */
    fun lireHistoriqueCompletFirestore(
        tarifFcM3: Float = AppRepository.DEFAUT_TARIF,
        onResultat: (List<EntreeHistorique>) -> Unit,
        onErreur: (String) -> Unit
    ) {
        val uid = uid ?: return onErreur("Non connecté")

        firestore.collection("utilisateurs").document(uid)
            .collection("historique")
            .get()
            .addOnSuccessListener { moisDocs ->
                if (moisDocs.isEmpty) { onResultat(emptyList()); return@addOnSuccessListener }
                val allEntrees = mutableListOf<EntreeHistorique>()
                var remaining  = moisDocs.size()
                for (moisDoc in moisDocs) {
                    moisDoc.reference.collection("jours")
                        .orderBy("date", Query.Direction.DESCENDING)
                        .get()
                        .addOnSuccessListener { joursDocs ->
                            for (jour in joursDocs) {
                                val date      = jour.getString("date") ?: ""
                                val volLitres = (jour.getDouble("volume_litres") ?: 0.0).toFloat()
                                val coutFc    = (jour.getDouble("cout_fc")       ?: 0.0).toFloat()
                                if (date.isNotEmpty()) {
                                    allEntrees.add(EntreeHistorique(
                                        etiquette    = date,
                                        volumeLitres = volLitres,
                                        coutFc       = if (coutFc > 0f) coutFc
                                        else (volLitres / 1000f) * tarifFcM3
                                    ))
                                }
                            }
                            if (--remaining == 0)
                                onResultat(allEntrees.sortedByDescending { it.etiquette })
                        }
                        .addOnFailureListener {
                            if (--remaining == 0)
                                onResultat(allEntrees.sortedByDescending { it.etiquette })
                        }
                }
            }
            .addOnFailureListener { onErreur(it.message ?: "Erreur Firestore") }
    }

    // SECTION 5 — RESET MENSUEL AUTOMATIQUE
    // ════════════════════════════════════════════════════════════════════

    fun verifierEtResetterSiNouveauMois(context: android.content.Context) {
        val prefs = context.getSharedPreferences("AquaFlowPrefs", android.content.Context.MODE_PRIVATE)
        val moisStocke = prefs.getString("DERNIER_MOIS_CONNU", "") ?: ""
        val moisActuel = formatMois.format(Calendar.getInstance().time)

        if (moisStocke != moisActuel && moisStocke.isNotEmpty()) {
            prefs.edit().putString("DERNIER_MOIS_CONNU", moisActuel).apply()
        } else if (moisStocke.isEmpty()) {
            prefs.edit().putString("DERNIER_MOIS_CONNU", moisActuel).apply()
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// MODÈLES DE DONNÉES
// ════════════════════════════════════════════════════════════════════

data class DonneesTempsReel(
    val debit: Float = 0f,
    val volumeJour: Float = 0f,
    val pression: Float = 0f,
    val timestamp: Long = 0L
)

data class EntreeHistorique(
    val etiquette: String,
    val volumeLitres: Float,
    val coutFc: Float
)
