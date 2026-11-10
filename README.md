# Zeropense

A personal expense tracker that reads your bank SMS and turns them into transactions — on the phone,
with no account, no cloud and no ads. Built for AED and INR.

Native Android app: Kotlin + Jetpack Compose. The behaviour is specified in [`docs/FSD.md`](docs/FSD.md) and was
built feature by feature following [`docs/TODO.md`](docs/TODO.md). The original React Native / Expo app is kept on
the [`backup/expo-react-native`](https://github.com/najeem99/Zeropense/tree/backup/expo-react-native) branch.

## Install

Download the latest build from [Releases](https://github.com/najeem99/Zeropense/releases/latest/download/Zeropense.apk)
(built by GitHub Actions on every push to `main`; [`apk/Zeropense.apk`](apk/Zeropense.apk) is a committed copy) and open it on an Android phone (Android 7.0+). It uses the same
package name and signing key as the Expo build, so it installs over it and keeps your data.

## Stack

- `:core` — pure Kotlin/JVM: SMS engine (candidates, bank profiles, custom formats, templates and rules), import
  pipeline, dedupe, dashboard maths, SQLite repositories (bundled SQLite driver, same schema as the Expo app).
- `:app` — Compose + Material 3 UI, navigation, SMS inbox reader (content resolver, READ_SMS), automatic scan on
  app start and return to foreground.

## Build and check

Requires JDK 17 and the Android SDK (`local.properties` with `sdk.dir`).

```sh
./gradlew check            # ktlint, Android lint, core unit tests, Robolectric + Roborazzi screen tests
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Screen-test screenshots are written to `app/build/outputs/roborazzi/`.
