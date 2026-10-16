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
- **SMS pipeline**: `scanInbox` (sms/inbox.ts, pages of 500 from the native reader, resumes from
  `lastSmsScanAt`) or `importSms` → `importBatch` → `processSms` (skip personal numbers/OTP/promos →
  custom parsers → bank profile by sender → generic) → match card by last 4 digits (unknown digits stay
  unlinked and show up as "detected cards") → suggest category → flag duplicates → insert as `pending`.
  Financial-looking SMS nothing could read go to `unread_sms` for SMS configuration. Nothing reaches
  totals until accepted in Review — except the one-time history import (`importHistory`, offered on first
  launch, setting `smsHistoryImport`), which inserts as `accepted` and only sends possible duplicates to Review.
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
