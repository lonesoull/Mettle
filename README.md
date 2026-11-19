# Mettle

A personal training tracker for running, walking, hiking and stairs — built for a small invite-only group on Android.

- Product plan: [docs/BLUEPRINT.md](docs/BLUEPRINT.md)
- Design system: [docs/DESIGN.md](docs/DESIGN.md)
- Database: [docs/DATABASE.md](docs/DATABASE.md)

## Getting the app
Every push to `main` builds a signed APK and publishes it under **Releases**. Install it on your phone (allow "Install unknown apps" once); later versions install over it.

## Building locally
Requires JDK 17+ and the Android SDK (platform 35).
```
./gradlew assembleDebug
```
