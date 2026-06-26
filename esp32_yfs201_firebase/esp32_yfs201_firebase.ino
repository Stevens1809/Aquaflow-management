/**
 * ============================================================================
 * PROJET : AquaFlow Management - Prototype de compteur d'eau intelligent
 * FICHIER : esp32_yfs201_firebase.ino
 * DESCRIPTION : Firmware du microcontrôleur ESP32. 
 * RÔLE : Ce programme lit les impulsions du capteur YF-S201, calcule le débit 
 * et le volume d'eau en temps réel, synchronise l'heure via Internet, 
 * et transmet les données de consommation vers une base Firebase.
 * ============================================================================
 */

// --- 1. BIBLIOTHÈQUES NÉCESSAIRES ---
#include <WiFi.h>                   // Permet à l'ESP32 de se connecter au réseau Wi-Fi
#include <Firebase_ESP_Client.h>    // Gère la communication sécurisée avec Google Firebase
#include "addons/TokenHelper.h"     // Gère la génération des jetons d'accès Firebase
#include "addons/RTDBHelper.h"      // Facilite l'écriture dans la Realtime Database
#include <time.h>                   // Permet de manipuler et formater l'heure et la date

// --- 2. CONFIGURATION DU RÉSEAU ET DES ACCÈS ---
// Identifiants Wi-Fi (Remplacés par des valeurs génériques pour la sécurité)
#define WIFI_SSID     "NOM_DU_RESEAU_WIFI"
#define WIFI_PASSWORD "MOT_DE_PASSE_WIFI"

// Paramètres Firebase (Base de données Cloud)
#define FIREBASE_API_KEY      "VOTRE_CLE_API_FIREBASE"
#define FIREBASE_DATABASE_URL "https://votre-projet.firebaseio.com"
#define UTILISATEUR_UID       "UID_DE_LUTILISATEUR_FIREBASE" // Identifiant unique du compteur/abonné

// Identifiants du compte utilisateur de l'application
#define FIREBASE_EMAIL    "email_utilisateur@domaine.com"
#define FIREBASE_PASSWORD "mot_de_passe_utilisateur"

// --- 3. CONFIGURATION MATÉRIELLE (Capteur) ---
#define PIN_CAPTEUR         18     // Broche de l'ESP32 reliée au fil de signal (jaune) du capteur
#define FACTEUR_CALIBRATION 7.5f   // Constante du YF-S201 (7.5 impulsions par seconde = 1 L/min)
#define INTERVALLE_ENVOI_MS 10000  // Délai entre deux envois vers Firebase (10 000 ms = 10 secondes)

// --- 4. CONFIGURATION DE L'HORLOGE (Serveur de temps NTP) ---
const char* ntpServer = "pool.ntp.org";   // Serveur mondial gratuit pour obtenir l'heure exacte
const long  gmtOffset_sec = 7200;         // Décalage horaire en secondes (7200s = +2h pour l'Afrique Centrale / Butembo)
const int   daylightOffset_sec = 0;       // Pas d'heure d'été/hiver dans cette région

// --- 5. VARIABLES GLOBALES DU SYSTÈME ---
FirebaseData   donneesFirebase; // Objet contenant les données à envoyer
FirebaseAuth   authFirebase;    // Objet gérant l'authentification
FirebaseConfig configFirebase;  // Objet contenant la configuration de connexion

// Variables de mesure (Le mot-clé "volatile" indique que la variable change lors d'une interruption)
volatile unsigned long compteurImpulsions = 0; // Stocke le nombre de tours de la turbine
float  debitActuel     = 0.0f;                 // Vitesse de l'eau en Litres par minute (L/min)
float  volumeJour      = 0.0f;                 // Cumul de l'eau consommée dans la journée (Litres)
String dateCourante    = "";                   // Stocke la date d'aujourd'hui (ex: "2026-06-25")

// Chronomètres pour gérer les tâches sans bloquer le programme
unsigned long dernierEnvoi  = 0;
unsigned long dernierCalcul = 0;

// Statistiques de performance réseau (pour le débogage)
int totalTransmissions = 0;
int succesTransmissions = 0;


/**
 * FONCTION D'INTERRUPTION (ISR)
 * Déclenchée matériellement à chaque fois que la turbine du capteur fait tourner son aimant.
 * IRAM_ATTR force la fonction à se charger dans la RAM rapide pour ne rater aucune impulsion.
 */
void IRAM_ATTR compterImpulsion() {
  compteurImpulsions++;
}


/**
 * FONCTION : Obtenir la date actuelle
 * Interroge l'horloge interne (précédemment synchronisée via internet) 
 * et formate la date au standard "Année-Mois-Jour".
 */
String obtenirDate() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) {
    return "Erreur_Date"; // Retourné si l'horloge n'est pas encore synchronisée
  }
  char timeStringBuff[11];
  strftime(timeStringBuff, sizeof(timeStringBuff), "%Y-%m-%d", &timeinfo);
  return String(timeStringBuff);
}


/**
 * FONCTION : Sauvegarder l'historique d'une journée finalisée dans Firebase
 * Crée une archive immuable du volume total consommé à la fin de chaque journée.
 */
void sauvegarderHistoriqueJournalier(String dateHistorique, float volumeLitres) {
  if (dateHistorique == "" || dateHistorique == "Erreur_Date" || !Firebase.ready()) return;

  // Création du chemin spécifique dans l'arborescence Firebase
  String cheminHisto = "/compteurs/" + String(UTILISATEUR_UID) + "/historique_journalier/" + dateHistorique;
  
  // Envoi des différentes informations (Date, volume, et timestamp pour classer chronologiquement)
  Firebase.RTDB.setString(&donneesFirebase, cheminHisto + "/date", dateHistorique);
  Firebase.RTDB.setFloat(&donneesFirebase, cheminHisto + "/volume_litres", volumeLitres);
  Firebase.RTDB.setFloat(&donneesFirebase, cheminHisto + "/volume", volumeLitres);
  Firebase.RTDB.setInt(&donneesFirebase, cheminHisto + "/timestamp_unix", time(nullptr));
}


/**
 * FONCTION D'INITIALISATION (Exécutée une seule fois au démarrage)
 */
void setup() {
  Serial.begin(115200); // Initialise la console pour voir les messages sur l'ordinateur
  Serial.println("=== AquaFlow Management - Demarrage ===");

  // 1. Initialisation du capteur
  pinMode(PIN_CAPTEUR, INPUT_PULLUP); // Active la résistance interne pour éviter les faux signaux électriques
  attachInterrupt(digitalPinToInterrupt(PIN_CAPTEUR), compterImpulsion, RISING); // Détecte chaque front montant (impulsion)

  // 2. Connexion au réseau Wi-Fi
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Connexion Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWi-Fi connecte ! IP : " + WiFi.localIP().toString());

  // 3. Mise à l'heure du microcontrôleur via Internet
  Serial.print("Synchronisation de l'horloge réseau (NTP)");
  configTime(gmtOffset_sec, daylightOffset_sec, ntpServer);
  struct tm timeinfo;
  while (!getLocalTime(&timeinfo)) {
    delay(500);
    Serial.print(".");
  }
  dateCourante = obtenirDate();
  Serial.println("\nHeure synchronisée ! Date actuelle : " + dateCourante);

  // 4. Configuration et connexion à Firebase
  configFirebase.api_key      = FIREBASE_API_KEY;
  configFirebase.database_url = FIREBASE_DATABASE_URL;
  authFirebase.user.email     = FIREBASE_EMAIL;
  authFirebase.user.password  = FIREBASE_PASSWORD;
  configFirebase.token_status_callback = tokenStatusCallback;

  Firebase.begin(&configFirebase, &authFirebase);
  Firebase.reconnectWiFi(true); // Autorise Firebase à se reconnecter automatiquement si le Wi-Fi coupe

  Serial.println("Firebase pret ! Le système est opérationnel.");
  dernierCalcul = millis();
  dernierEnvoi  = millis();
}


/**
 * BOUCLE PRINCIPALE (Exécutée en continu à l'infini)
 */
void loop() {
  unsigned long maintenant = millis(); // Chronomètre interne (temps écoulé depuis l'allumage)

  // ========================================================================
  // TÂCHE 1 : CALCUL DU DÉBIT ET DU VOLUME (Exécutée toutes les 1 seconde)
  // ========================================================================
  if (maintenant - dernierCalcul >= 1000) {
    
    // Désactive temporairement les interruptions pour lire le compteur sans qu'il change pendant la lecture
    noInterrupts();
    unsigned long impulsions = compteurImpulsions;
    compteurImpulsions = 0; // Remise à zéro pour la seconde suivante
    interrupts();

    // Calcul mathématique des fluides
    debitActuel = (float)impulsions / FACTEUR_CALIBRATION; // Résultat en L/min
    volumeJour += debitActuel / 60.0f;                     // Conversion du débit en volume (1 minute = 60 secondes)
    
    dernierCalcul = maintenant; // Mise à jour du chronomètre

    Serial.printf("Debit: %.2f L/min | Volume cumulé: %.3f L\n", debitActuel, volumeJour);

    // Vérification du changement de journée (Passage à minuit)
    String dateDuJour = obtenirDate();
    if (dateDuJour != "Erreur_Date" && dateCourante != "") {
      
      if (dateDuJour != dateCourante) {
        // La date a changé ! On sauvegarde l'historique d'hier avant de remettre le compteur à zéro
        sauvegarderHistoriqueJournalier(dateCourante, volumeJour);

        Serial.println("--- NOUVEAU JOUR : Historique sauvegardé ---");
        volumeJour = 0.0f;          // Remise à zéro du volume pour la nouvelle journée
        dateCourante = dateDuJour;  // Mise à jour de la date
      }
    }
  }

  // ========================================================================
  // TÂCHE 2 : TRANSMISSION DES DONNÉES CLOUD (Exécutée toutes les 10 secondes)
  // ========================================================================
  if (maintenant - dernierEnvoi >= INTERVALLE_ENVOI_MS && Firebase.ready()) {
    
    // Chemin ciblant l'affichage "en direct" sur l'application mobile
    String chemin = "/compteurs/" + String(UTILISATEUR_UID) + "/temps_reel";

    long tempsDebut = millis();
    totalTransmissions++; 

    // Envoi effectif du débit instantané et du volume journalier accumulé
    bool succesDebit = Firebase.RTDB.setFloat(&donneesFirebase, chemin + "/debit", debitActuel);
    bool succesVolume = Firebase.RTDB.setFloat(&donneesFirebase, chemin + "/volume_jour", volumeJour);
    
    // Enregistrement de l'heure exacte du serveur pour synchroniser l'application mobile
    Firebase.RTDB.setInt(&donneesFirebase, chemin + "/timestamp_unix", time(nullptr));
    
    // Sauvegarde préventive de l'historique du jour en cours
    sauvegarderHistoriqueJournalier(dateCourante, volumeJour);

    // Analyse des performances réseau
    if (succesDebit && succesVolume) {
      long tempsEcoule = millis() - tempsDebut; // Calcul de la latence de transmission
      succesTransmissions++; 
      Serial.printf("[SUCCÈS] Données Firebase mises à jour. Latence : %ld ms\n", tempsEcoule);
    } else {
      Serial.println("[ERREUR] Échec de l'envoi Firebase. Raison : " + donneesFirebase.errorReason());
    }

    dernierEnvoi = maintenant; // Mise à jour du chronomètre d'envoi
  }
}