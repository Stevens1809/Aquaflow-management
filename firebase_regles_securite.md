# Règles de sécurité Firebase — AquaFlow Management

## Realtime Database (coller dans Console Firebase → Realtime DB → Règles)

```json
{
  "rules": {
    "compteurs": {
      "$uid": {
        ".read":  "$uid === auth.uid",
        ".write": "$uid === auth.uid || auth != null"
      }
    }
  }
}
```

## Firestore (coller dans Console Firebase → Firestore → Règles)

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Un utilisateur ne peut lire/écrire que SES propres données
    match /utilisateurs/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;

      // Sous-collections : historique et jours
      match /historique/{mois} {
        allow read, write: if request.auth != null && request.auth.uid == uid;

        match /jours/{jour} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
      }
    }
  }
}
```

## AndroidManifest.xml — Permission Internet à vérifier

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```
