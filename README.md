# Dictée (Android)

L'app Dictée d'Hadrien Rose : **la page web de Prisme** (`voice.hadrien-rose.com`, protégée par Cloudflare Access) dans
une WebView, plus un **enregistreur natif** — un service de premier plan de type microphone, qui enregistre écran
verrouillé et pendant un appel, là où une page web se fait couper le micro par Android.

Ce dépôt est public et ne contient **aucun secret** : pas de clé d'API, pas de jeton, pas de clé de signature (elle vit
dans les secrets GitHub). L'app n'ouvre rien sans le login de son propriétaire.

## Ce que fait l'app

- `MainActivity` : la WebView, limitée à `voice.hadrien-rose.com` et au login Cloudflare ; tout autre lien s'ouvre dans
  le navigateur ; le pont JS `window.DicteeNative` n'existe que sur cette origine ; l'audio d'une dictée native est servi
  à la page sur `/__native/rec/<id>` (fichiers de l'app seulement, `Rules.partFile`).
- `RecorderService` : `MediaRecorder` (micro brut, Ogg/Opus 32 kbit/s mono), notification Pause / Reprendre / Arrêter,
  **aucune pause automatique** ; si le système arrête l'encodeur, une nouvelle partie repart aussitôt ; journal natif
  (battement toutes les 15 s : niveau, mode appel, écran, batterie) envoyé avec la dictée.
- `Updater` : lit `version.json` de la dernière release ; « Mettre à jour » télécharge l'APK, vérifie son SHA-256 et sa
  signature, puis l'installeur système demande confirmation.

## Construire

```
export ANDROID_HOME=~/android-sdk
./gradlew test assembleRelease      # clé de débogage sans DICTEE_KEYSTORE*, clé de release avec
```

La CI (`.github/workflows/release.yml`) fait la même chose à chaque push sur `main`, après un scan de secrets, et publie
la release que l'app installée propose d'installer.
