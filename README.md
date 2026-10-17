# Zeropense

[![Built with Claude Code](https://img.shields.io/badge/Built%20with-Claude%20Code-D97757?logo=claude&logoColor=white)](https://claude.com/claude-code)
![Expo SDK 57](https://img.shields.io/badge/Expo-SDK%2057-000020?logo=expo&logoColor=white)
![Android](https://img.shields.io/badge/Android-only-3DDC84?logo=android&logoColor=white)

A personal expense tracker that reads your bank SMS and turns them into transactions — on the phone,
with no account, no cloud and no ads. Built for AED and INR.

## Download

**[Download the latest APK](https://github.com/najeem99/Zeropense/raw/main/apk/Zeropense.apk)** (Android, ~37 MB)

Open the file on your phone and allow "Install unknown apps" for your browser or file manager when asked.
Installing over an older version keeps your data.

## Features

- **Reads bank SMS** — a guided setup picks the period (1, 3, 6 or 12 months), your bank senders and
  the message kinds that are real transactions. OTPs and promotions are left out.
- **Only what you configured** — new SMS of a known kind land in **Review** to approve (or *Approve all*).
- **Cards by last 4 digits** — every card number in your SMS is matched to a credit or debit card you name.
- **Categories that learn** — pick a category once and the next payment at that shop gets it too.
  Salary, refunds, money received and remittances (e.g. Aspora) are sorted automatically.
- **Refunds** lower your spending instead of counting as income.
- **Duplicate detection** — the same payment sent twice (SMS + email alert, two banks) is flagged.
- **Overview** — money in, money out and net per currency, a spending-by-category donut and a spent-per-day chart.
  AED and INR are never added together.
- **SMS configuration** — paste any message and tap which amount, card and merchant to take.
- **Start over** — delete all data and run the setup again like a new install.

Your data stays in a SQLite database on the device. SMS are read on the phone and never uploaded.

## Build it yourself

Needs Node 22.13+, JDK 17 and the Android SDK (Android Studio is not required).

```bash
npm install
npx expo prebuild --platform android   # generates the android/ folder
cd android
./gradlew assembleRelease              # → android/app/build/outputs/apk/release/app-release.apk
```

For development on a phone with USB debugging: `npx expo run:android`.
Expo Go can't read SMS, so the inbox features need a development or release build.

To publish a new APK here, copy `android/app/build/outputs/apk/release/app-release.apk` to `apk/Zeropense.apk` and push.

## Checks

```bash
npm test          # Jest: parsers, dedupe, summaries, and the SQL on node:sqlite
npx expo lint
npx tsc --noEmit
```

## How it's built

- Expo SDK 57, React Native, Expo Router, TypeScript, React Compiler
- `expo-sqlite` with versioned migrations (`src/db/migrations.ts`)
- A local Kotlin Expo module (`modules/sms-reader`) that reads the Android SMS inbox
- Pure logic (parsing, dedupe, categorising, summaries) kept apart from the SQLite repositories, with Jest tests

See [CLAUDE.md](CLAUDE.md) for the architecture and the SMS pipeline.
