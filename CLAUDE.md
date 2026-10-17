@AGENTS.md

## Project: Zeropense

Personal finance app that tracks expenses automatically from bank SMS, multi-currency (AED, INR).
Local-first, no ads, no paid tier. Android-first (SMS reading needs a development build, not Expo Go).
Full plan: Notion "Expense App Brainstorm" (Najeem Home / Claude thoughts).

### Folder structure

```text
src/
  app/                 routes only (Expo Router)
  features/
    sms/               SMS reading, built-in bank parsers, user-defined custom parser
    transactions/      review queue (accept/edit/reject), duplicate detection
    categories/        categorization that learns from user choices
    accounts/          credit/debit cards and bank accounts (AED, INR), matched by last 4 digits
    dashboard/         money in vs money out
  db/                  local storage: schema, migrations, queries
  components/          shared UI
  lib/                 pure helpers (money, dates, ids)
  theme/               colors, spacing, typography
modules/sms-reader/    local Expo module (Kotlin): reads the Android SMS inbox
```

Feature code stays inside its feature folder; only promote to `components/` or `lib/` when a second feature needs it.

### How it fits together

- **Pure logic vs. storage**: parsing, dedupe, categorization and summaries are pure functions
  (`sms/extract.ts`, `sms/custom-parser.ts`, `sms/ingest.ts`, `transactions/dedupe.ts`,
  `categories/categorizer.ts`, `dashboard/summary.ts`) with Jest tests in `__tests__/`.
  `repository.ts` files are the only code that touches SQLite.
- **Only configured SMS are imported.** SMS setup (`app/sms-setup.tsx`, steps in `sms/setup/`) asks for a
  period, the bank senders (`listSenders` counts them natively), then groups their messages into kinds by
  `templateKey` (sms/templates.ts) and the user ticks the transaction kinds, picks the amount by position
  and assigns each card number to a card. Every kind becomes an `sms_rules` row (`import` or `ignore`).
  Running setup deletes earlier SMS transactions (`clearSmsTransactions`) and imports as `accepted`.
- **SMS pipeline**: `scanInbox` (new SMS since `lastSmsScanAt`; does nothing before setup) or `importSms`
  → `importBatch` (chunks of 200, yields to the UI, prepared inserts, `DedupeIndex`) → `processSms`
  (SMS rules → custom parsers → otherwise ignored) → match card by last 4 digits → suggest category →
  flag duplicates → insert as `pending` for Review. Unknown kinds with amounts from configured senders go
  to `unread_sms`. The generic/bank parsers are only used to suggest kinds during setup.
- **SMS configuration** (`app/parsers/new.tsx`): the user picks the transaction amount from
  `findAmountCandidates`, the card from `findDigitsCandidates`, optionally merchant/date; that becomes a
  custom parser (`buildPattern`), and `recheckUnread` re-runs the unread messages.
- **Money** is stored as integer minor units (`amount_minor`) with a currency; AED and INR are never summed together.
- **Reactivity**: every repository write calls `notifyDbChanged()`; screens read with `useDbQuery(fn, key)`.
- **Schema changes**: add a new step in `db/migrations.ts` and bump `LATEST`; never edit V1 once shipped.
- **Native code**: `SmsReader` is loaded with `requireOptionalNativeModule`, so it is `null` in Expo Go;
  check `inboxAvailable`. After changing Kotlin, rebuild with `npx expo run:android`.

Run `npm test` alongside lint and typecheck. `src/db/__tests__` runs the real SQL on `node:sqlite`
(Node ≥ 22.13; skipped on older Node).
