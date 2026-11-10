## Project: Zeropense (native Android)

Personal finance app that tracks expenses automatically from bank SMS, multi-currency (AED, INR).
Local-first, no ads, no paid tier. Native Android: Kotlin + Jetpack Compose.

- `docs/FSD.md` is the functional spec; `docs/TODO.md` is the feature-by-feature plan (F0–F20) and the decisions taken.
- Modules: `:core` (pure JVM logic and SQLite repositories, JUnit tests) and `:app` (Compose UI, Robolectric/Roborazzi tests).
- Keep data compatibility with the Expo build: same package, signing key (`app/debug.keystore`), DB file and schema.
- Run `./gradlew ktlintFormat check` before committing. Release APK goes to `apk/Zeropense.apk`.
- The original Expo / React Native code lives on the `backup/expo-react-native` branch.
