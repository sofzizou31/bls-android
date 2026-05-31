# BLS Visa Selfie — Guide de déploiement

## Étape 1 : Firebase (5 min)

1. Va sur https://console.firebase.google.com
2. **Nouveau projet** → "bls-visa-selfie"
3. **Ajouter Android** → package : `com.sofzizou.bls`
4. Télécharger **google-services.json** → coller dans `app/`
5. Aller dans **Project Settings → Service accounts**
6. Cliquer **Generate new private key** → télécharger le JSON

---

## Étape 2 : Cloudflare Worker (5 min)

```bash
cd C:\BLS-Sources\worker\cloudflare

# Installer wrangler si pas encore fait
npm install -g wrangler

# Se connecter
wrangler login

# Ajouter les secrets (coller les valeurs quand demandé)
wrangler secret put GITHUB_TOKEN
# → coller votre token GitHub (ghp_...)

wrangler secret put FIREBASE_SERVICE_ACCOUNT
# → coller le contenu COMPLET du fichier JSON du service account Firebase

# Déployer
wrangler deploy
# → Retourne l'URL: https://bls-fcm.XXXX.workers.dev
```

3. Copier l'URL du worker et remplacer `YOUR_SUBDOMAIN` dans :
   - `Constants.kt` → `WORKER_URL`
   - `popup.js` → `WORKER_URL` (ligne 1 de notifyClient)

---

## Étape 3 : Compiler l'APK (Android Studio)

1. Ouvrir `C:\BLS-Sources\android-app` dans Android Studio
2. **File → Sync Project with Gradle Files**
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. APK généré dans `app/build/outputs/apk/debug/`

---

## Étape 4 : Installer sur le téléphone client

```bash
adb install app-debug.apk
```
Ou partager l'APK directement (WhatsApp, Telegram).

---

## Flux complet après installation

```
Client installe l'APK
  → Ouvre l'app → Entre son numéro (+213XXXXXXXXX)
  → App écrit clients/+213XXXXXXXXX.json sur GitHub
  → Écran "En attente de notifications"

Opérateur (extension Chrome) :
  → Entre les données sur la page livenessrequest
  → Envoie vers GitHub (bouton vert)
  → Saisit le numéro du client dans "Notifier le client"
  → Clique "Envoyer l'alarme"
  → Extension → Cloudflare Worker → FCM → Téléphone client

Client :
  → Alarme sonore + notification plein écran
  → Tape la notification → App s'ouvre
  → Selfie OzForensics (WebView, proxy actif)
  → Liveness ID → GitHub → Telegram opérateur
  → Plaque opérateur passe à 100% → formulaire soumis
```

---

## Dépannage

| Problème | Solution |
|---|---|
| Worker retourne 404 | Le client n'a pas encore installé l'app ou le numéro est différent |
| Worker retourne 500 FCM | Vérifier FIREBASE_SERVICE_ACCOUNT (JSON complet, pas tronqué) |
| App ne reçoit pas la notif | Vérifier que les notifications sont autorisées dans les paramètres Android |
| Jscrambler bloque le selfie | Vérifier que le proxy_url est correct et que le User-Agent est bien injecté |
| SHA 409 sur GitHub | Ne jamais faire deux PUT sans le bon SHA — markViewed() retourne sha2, obligatoire |
