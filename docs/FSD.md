# Zeropense: Functional Specification (native Android rebuild)

| | |
|---|---|
| Status | Draft 1, written from the code on `main` at commit `964254d` (10 Nov 2026) |
| Source app | React Native / Expo SDK 57, Expo Router, expo-sqlite, local Kotlin module `modules/sms-reader` |
| Target app | Native Android, Kotlin |
| Companion | [`TODO.md`](TODO.md): the ordered, feature-by-feature build plan with a coverage check |

This document describes **what the current app does**, precisely enough to rebuild it without reading the
TypeScript. Every rule below was taken from the code, not from the README. Where the code behaves in a way
that looks unintended, it is listed under [§13 Known quirks](#13-known-quirks-and-decisions-for-the-rebuild)
so the rebuild can decide to keep or fix it on purpose.

---

## Contents

1. [Product summary](#1-product-summary)
2. [Platform, permissions and app identity](#2-platform-permissions-and-app-identity)
3. [Recommended native architecture](#3-recommended-native-architecture)
4. [Data model](#4-data-model)
5. [Core helpers: money, dates, ids](#5-core-helpers-money-dates-ids)
6. [SMS engine (pure logic)](#6-sms-engine-pure-logic)
7. [Import pipeline](#7-import-pipeline)
8. [Categories and learning](#8-categories-and-learning)
9. [Accounts and cards](#9-accounts-and-cards)
10. [Transactions](#10-transactions)
11. [Dashboard calculations](#11-dashboard-calculations)
12. [Screens](#12-screens)
13. [Known quirks and decisions for the rebuild](#13-known-quirks-and-decisions-for-the-rebuild)
14. [Test fixtures to port](#14-test-fixtures-to-port)
15. [Glossary](#15-glossary)

---

## 1. Product summary

Zeropense is a personal expense tracker that turns **bank SMS** into transactions, on the phone only.

- **Local-first.** One SQLite database on the device. No account, no server, no network calls, no ads, no paid tier.
- **Two currencies: AED and INR.** Amounts are integers in minor units (fils / paise). The two currencies are
  **never added together**; every total is per currency.
- **Only configured SMS are imported.** A guided setup asks which senders are banks and which kinds of their
  messages are transactions. Everything else (OTPs, promos, other senders) is ignored.
- **Review queue.** New SMS transactions arrive as *pending*; the user accepts, edits or rejects them.
- **Cards by last 4 digits.** Card/account numbers in SMS are matched to cards the user names.
- **Categories that learn.** Choosing a category for a merchant remembers it for the next SMS from that merchant.
- **Refunds reduce spending** instead of counting as income.
- **Duplicate detection.** Same amount + currency + direction within 15 minutes is flagged, never auto-deleted.
- **Overview.** Money in / out / net per currency, spending-by-category donut, spent-per-day bar chart.
- **Start over.** Delete all data and re-run setup like a new install.

### 1.1 Actors and main flows

There is one actor, the phone owner.

1. **First run:** Overview shows *Set up your bank SMS* → SMS setup wizard (period → senders → kinds → cards →
   confirm → import). Imported messages go straight into totals as *accepted*; possible duplicates go to Review.
2. **Every app open / return to foreground:** the inbox is scanned for SMS newer than the last scan. Configured
   kinds become *pending* transactions in Review (tab badge shows the count).
3. **Review:** accept / edit / reject each, or *Approve all* (with or without possible duplicates).
4. **Manual entry:** add or edit a transaction by hand from Overview or Transactions.
5. **Maintenance (More tab):** SMS configuration (custom formats, unread SMS), cards & accounts, paste an SMS,
   categories and remembered merchants, delete everything.

---

## 2. Platform, permissions and app identity

| Item | Current value | Notes for rebuild |
|---|---|---|
| Application id | `com.najeemfasil.expense` | Keep it (and the signing key) if existing installs must upgrade in place and keep data. See §13 D1. |
| App name | `Zeropense` | |
| Version | `1.0.0` | |
| Orientation | Portrait only | |
| Theme | Follows system light/dark (`userInterfaceStyle: automatic`) | |
| Icon | Adaptive icon: background `#E6F4FE`, foreground, background and monochrome PNGs in `assets/images/` | Reuse the PNGs. |
| Splash | Background `#208AEF`, `splash-icon.png`, 76 dp wide | Use the Android 12+ SplashScreen API. |
| Predictive back | Disabled (`predictiveBackGestureEnabled: false`) | Can be enabled in native if navigation handles it. |
| ABIs | `arm64-v8a` only; R8 minify and resource shrinking on in release | No native C++ in the rebuild, so ABI filters only matter if a native lib is added. |
| Permission | `android.permission.READ_SMS` (runtime, dangerous) | The only permission. No INTERNET needed. |
| Database file | `expense.db` (expo-sqlite stores it under the app's files dir, `SQLite/expense.db`) | |
| Distribution | Sideloaded APK in `apk/Zeropense.apk` | |

### 2.1 READ_SMS permission

- Requested the first time the user picks a period in SMS setup (§12.9), not at app start.
- Rationale dialog text: title **"Read bank SMS"**, message *"Zeropense reads your bank SMS to find transactions.
  Messages are processed on this phone and never uploaded."*, buttons **Allow** / **Not now**.
- If denied, setup shows the error *"SMS permission is needed to read your bank messages."* and stays on the
  period step.
- Automatic scans run only if the permission is already granted; they never prompt.
- The native reader throws `READ_SMS permission has not been granted` if called without permission.

---

## 3. Recommended native architecture

This is a recommendation; the functional rules in §4–§12 do not depend on it.

| Concern | Expo app | Native recommendation |
|---|---|---|
| UI | React Native components in `src/components/ui.tsx` | Jetpack Compose + Material 3 |
| Navigation | Expo Router: root stack + 4 native tabs | Navigation Compose: `NavHost` with a bottom `NavigationBar` (4 tabs) and stack destinations |
| Storage | expo-sqlite, raw SQL, `PRAGMA user_version` migrations | Room (or SQLDelight) over the **same schema**; see §13 D1 for opening an existing `expense.db` |
| Reactivity | `notifyDbChanged()` + `useDbQuery(fn, key)` re-runs queries after every write | Room `Flow` queries collected in ViewModels (`stateIn`). Writes no longer need a manual notify. |
| Async | Promises; chunks yield to UI with `setTimeout(0)` | Coroutines on `Dispatchers.IO`; long imports emit progress through a `StateFlow` |
| SMS reading | Kotlin Expo module using `ContentResolver` on `Telephony.Sms.Inbox` | The same `ContentResolver` code, as a plain repository class |
| Foreground scan | `AppState` "active" listener | `ProcessLifecycleOwner` `ON_START` observer (or `Lifecycle.repeatOnLifecycle`) |
| Charts | `react-native-svg` donut; `View` bars | Compose `Canvas` |
| Pure logic tests | Jest | JUnit 5 / kotlin.test on the JVM; Robolectric or instrumented tests for Room |

Suggested package layout, mirroring the feature folders:

```text
app/src/main/java/<pkg>/
  ui/            theme, shared components, navigation
  core/          money, dates, ids (pure)
  data/db/       Room database, entities, DAOs, migrations, settings, reset
  feature/
    sms/         engine (pure), import pipeline, inbox reader, setup wizard, custom formats
    transactions/ review, list, edit, dedupe
    categories/   categorizer (pure), screen
    accounts/     screens
    dashboard/    summary + daily (pure), overview, donut, bar chart
```

Keep the existing split: **pure functions** for parsing, dedupe, categorization and summaries (unit-tested on the
JVM), and **repositories/DAOs** as the only code that touches SQLite.

---

## 4. Data model

All timestamps are **epoch milliseconds** (INTEGER). Money is `amount_minor` INTEGER (positive) plus
`currency` TEXT (`AED` | `INR`) and `direction` TEXT (`debit` = money out, `credit` = money in).
Ids are TEXT (see §5.3). Foreign keys are **on** (`PRAGMA foreign_keys = ON`) and the journal is WAL.

### 4.1 Tables (final shape after migration 6)

#### `accounts`
| Column | Type | Rules |
|---|---|---|
| id | TEXT PK | |
| name | TEXT NOT NULL | trimmed |
| currency | TEXT NOT NULL | `AED` \| `INR` |
| sms_hint | TEXT NULL | last 4 digits as they appear in SMS |
| created_at | INTEGER NOT NULL | |
| kind | TEXT NOT NULL DEFAULT `'bank'` | `bank` \| `credit_card` \| `debit_card` (added in V2) |

#### `categories`
| Column | Type | Rules |
|---|---|---|
| id | TEXT PK | seeded ids are readable slugs (`groceries`); user ones use `newId()` |
| name | TEXT NOT NULL | |
| kind | TEXT NOT NULL | `expense` \| `income` |
| sort | INTEGER NOT NULL DEFAULT 0 | list order within kind |

#### `transactions`
| Column | Type | Rules |
|---|---|---|
| id | TEXT PK | |
| status | TEXT NOT NULL | `pending` \| `accepted` \| `rejected` |
| source | TEXT NOT NULL | `sms` \| `manual` |
| amount_minor | INTEGER NOT NULL | > 0 |
| currency | TEXT NOT NULL | |
| direction | TEXT NOT NULL | `debit` \| `credit` |
| merchant | TEXT NULL | counterparty name (merchant for debits, sender for credits) |
| note | TEXT NULL | manual only |
| occurred_at | INTEGER NOT NULL | |
| account_id | TEXT NULL → accounts(id) **ON DELETE SET NULL** | |
| category_id | TEXT NULL → categories(id) **ON DELETE SET NULL** | |
| duplicate_of_id | TEXT NULL | id of the transaction this may duplicate (no FK) |
| sms_hash | TEXT **UNIQUE** NULL | prevents re-importing the same SMS, including rejected ones |
| sms_sender | TEXT NULL | raw sender address |
| sms_body | TEXT NULL | full SMS text |
| parser_id | TEXT NULL | `rule:<id>`, `custom:<id>`, `bank:<id>` or `generic` |
| created_at, updated_at | INTEGER NOT NULL | |
| account_hint | TEXT NULL | last digits seen in the SMS, kept even when no account matched (V2) |

Indexes: `transactions_status_time (status, occurred_at)`, `transactions_account_hint (account_hint)`.

#### `merchant_rules` (learned categorization)
| Column | Type | Rules |
|---|---|---|
| merchant_key | TEXT PK | normalized merchant (§8.2) |
| category_id | TEXT NOT NULL → categories(id) **ON DELETE CASCADE** | |
| updated_at | INTEGER NOT NULL | |

#### `custom_parsers` (user-built SMS formats, §6.8)
| Column | Type | Rules |
|---|---|---|
| id | TEXT PK | |
| name | TEXT NOT NULL | |
| sender_contains | TEXT NULL | case-insensitive substring of the sender |
| pattern | TEXT NOT NULL | regex source produced by `buildPattern` |
| fields | TEXT NOT NULL | JSON array of field names in capture-group order, e.g. `["amount","account"]` |
| currency, direction | TEXT NOT NULL | |
| sample | TEXT NOT NULL | the SMS it was built from |
| created_at | INTEGER NOT NULL | list order: newest first |

#### `unread_sms` (V2): SMS from configured senders that have an amount but matched no kind
| Column | Type |
|---|---|
| sms_hash | TEXT PK |
| sender | TEXT NOT NULL |
| body | TEXT NOT NULL |
| received_at | INTEGER NOT NULL |

#### `settings` (V2): key/value
| key | value |
|---|---|
| `lastSmsScanAt` | receive time (ms, as string) of the newest SMS the scanner has read; next scan reads strictly after it |
| `lastSmsScanRunAt` | wall-clock time (ms) when a scan or setup import last finished; shown as "Last checked …" |

#### `sms_rules` (V3): message kinds configured in setup
| Column | Type | Rules |
|---|---|---|
| id | TEXT PK | |
| sender_key | TEXT NOT NULL | sender without operator prefix/suffix (§6.6) |
| template_key | TEXT NOT NULL | message "shape" (§6.6). **Recomputed from `sample` every time rules are loaded**, so stored values may be stale. |
| action | TEXT NOT NULL | `import` \| `ignore` |
| amount_index | INTEGER NOT NULL | which amount candidate (0-based, §6.2) is the transaction |
| direction, currency | TEXT NOT NULL | |
| fixed_account_id | TEXT NULL → accounts(id) **ON DELETE SET NULL** | account for kinds that never name a card |
| sample | TEXT NOT NULL | newest message of that kind at setup time |
| created_at | INTEGER NOT NULL | |
| | | `UNIQUE (sender_key, template_key)` |

### 4.2 Migrations (must be reproduced exactly; never edit a shipped step)

On every app start: set WAL + foreign keys, read `PRAGMA user_version`; if below 6, run the missing steps **in
one transaction**, then set `user_version = 6`.

| Version | Change |
|---|---|
| 1 | Create `accounts`, `categories`, `transactions` (+ index), `merchant_rules`, `custom_parsers`. Seed default categories (table below) with `sort` 0..15 in that order. |
| 2 | `accounts.kind` (default `bank`); `transactions.account_hint` + index; tables `unread_sms`, `settings`. |
| 3 | Table `sms_rules`. |
| 4 | `INSERT OR IGNORE` category `transfer-in` "Money received", income, sort 100. |
| 5 | `DELETE FROM merchant_rules WHERE category_id = 'refund'`. |
| 6 | `INSERT OR IGNORE` category `remittance` "Remittance", expense, sort 11; `INSERT OR IGNORE` merchant rule `ASPORA → remittance` (updated_at 0). |

### 4.3 Seed categories

| id | name | kind | sort | icon | colour slot |
|---|---|---|---|---|---|
| groceries | Groceries | expense | 0 | 🛒 | green |
| restaurant | Restaurant | expense | 1 | 🍽️ | orange |
| transport | Transport | expense | 2 | 🚕 | blue |
| fuel | Fuel | expense | 3 | ⛽ | yellow |
| shopping | Shopping | expense | 4 | 🛍️ | magenta |
| bills | Bills & Utilities | expense | 5 | 💡 | aqua |
| rent | Rent | expense | 6 | 🏠 | violet |
| health | Health | expense | 7 | 💊 | red |
| entertainment | Entertainment | expense | 8 | 🎬 | magenta |
| subscriptions | Subscriptions | expense | 9 | 🔁 | red |
| travel | Travel | expense | 10 | ✈️ | blue |
| remittance | Remittance | expense | 11 (V6) | 💸 | orange |
| transfers | Transfers | expense | 11 | ↔️ | aqua |
| other | Other | expense | 12 | 🏷️ | neutral grey |
| salary | Salary | income | 13 | 💼 | green |
| refund | Refund | income | 14 | ↩️ | aqua |
| other-income | Other income | income | 15 | 💰 | violet |
| transfer-in | Money received | income | 100 (V4) | 📥 | blue |

Categories are listed `ORDER BY kind, sort, name`. Seed rule: `ASPORA → remittance`.

### 4.4 Reset ("Delete all data and start over")

Drop **every** non-`sqlite_%` table (foreign keys switched off first, drops inside a transaction), set
`user_version = 0`, re-run the migrations (so seeds come back), notify observers. SMS in the phone inbox are
untouched. Any running scan is awaited first.

---

## 5. Core helpers: money, dates, ids

### 5.1 Money
- `CURRENCIES = [AED, INR]`.
- **normalizeCurrency(token)**: trim, upper-case, drop one trailing `.`; `INR`, `RS`, `₹` → INR; `AED`, `DHS`, `DH` → AED; else null.
- **parseAmountToMinor(raw)**: remove commas and whitespace; must match `^\d+(\.\d+)?$`; minor = whole×100 +
  first two fraction digits (padded, **truncated, not rounded**: `1.999` → 199). Returns null if not > 0.
- **formatMoney(minor, currency, signed?)**: absolute value / 100 with exactly 2 decimals, grouped by locale
  (`en-AE` for AED → `1,234.50`; `en-IN` for INR → lakh grouping `1,23,456.78`). Sign: `−` (U+2212) when negative,
  `+` when `signed` and positive, else none. INR: `₹1,234.50` (no space). AED: `AED 1,234.50` (space).
- **minorToInput(minor)**: `(minor/100).toFixed(2)` for edit fields.
- **Amount display component:** debits are shown negative (`−AED 12.00`) in the normal text colour; credits are
  shown with `+` in the income colour. Tabular figures.

### 5.2 Dates (all in the device's local time zone)
- **parseSmsDate(raw)**, trimmed; first matching format wins; returns epoch ms or null:
  1. `YYYY-M-D` optionally followed by space/`T` and `H:MM` or `H:MM:SS`.
  2. `D-M-YY(YY)` with `-`, `/` or `.` separators (**day first**), optional `[ ,]+H:MM(:SS)`.
  3. `D[-space]Mon[letters][-space,]YY(YY)` e.g. `11-Oct-26`, `11 Oct 2026`, `11Oct26`, optional time.
  - Two-digit years mean 20YY. Month must be 1–12, day 1–31, and the date must not roll over (`31-02-26` → null).
- **startOfMonth**, **addMonths(ts, n)** (returns the 1st of the target month), **startOfDay**, **dayAt(from, i)**
  (calendar-day arithmetic, DST-safe).
- Display formats (en-GB): month `November 2026`; date `10 Nov 2026`; date-time `10 Nov, 14:05` (24 h);
  date input `2026-11-10`; chart day label `10 Nov`.

### 5.3 Ids and hashing
- **newId()**: base-36 of current ms + 8 random base-36 chars. Any unique string id is acceptable in native (UUID is fine) unless data must stay compatible (it doesn't need to: ids are opaque).
- **hashString(input)**: 32-bit **FNV-1a** over UTF-16 code units (offset `0x811c9dc5`, prime `0x01000193`), output as
  8-char lower-case hex. **smsHash(sms) = hashString(sender + "|" + receivedAt + "|" + body)** where `receivedAt` is
  the integer ms. Must be bit-for-bit identical if an existing database is carried over (otherwise old SMS re-import).
  In Kotlin iterate `String` chars (UTF-16), multiply with `Int` overflow, and format as unsigned.

---

## 6. SMS engine (pure logic)

Everything in this section is pure (no I/O) and must be unit-tested. Regexes below are written JS-style; when
porting, see §13 Q9 for regex-dialect differences.

### 6.1 Types
- **RawSms** `{ sender, body, receivedAt }`.
- **ParsedSms** `{ amountMinor, currency, direction, merchant?, accountHint? (last 4), occurredAt?, parserId }`.

### 6.2 Amount candidates (`findAmountCandidates`): used by setup, rules and the format builder
Returns every amount in the body, **sorted by position**:
1. **With currency**: `(INR|Rs\.?|₹|AED|Dhs?)\s*(NUM)` or `(NUM)\s*(INR|AED)\b`, case-insensitive, where
   `NUM = \d[\d,]*(?:\.\d{1,2})?`. `start/end` cover only the digits. Currency from the token.
2. **Bare decimals** (no currency, e.g. SBI `debited by 150.0`): `(?<![\d.,/-])(\d[\d,]*\.\d{1,2})(?![\d/-])`,
   skipped if inside an amount already found. Currency null. Whole numbers without a currency are never candidates
   (they are usually refs/dates).
- Each candidate has `minor`, `currency`, `context` (24 chars before to 16 after, whitespace collapsed, `…` when cut)
  and `looksLikeBalance` = the 20 chars before match `(bal(ance)?|limit|avl|available|outstanding|due)[\s.:a-z]{0,12}$`.

### 6.3 Card digits candidates (`findDigitsCandidates`)
`(?:[x*]+|ending\s*(?:with|in)?\s*[:\-]?\s*|(?:card|a\/c|acct|account|ac)\.?\s*(?:no\.?\s*)?)(\d{3,6})\b`, case-insensitive;
returns `{start, end, digits}` per distinct start.

### 6.4 Generic extractor (`extract.ts`)
- **Amount:** first with-currency match whose preceding 20 chars are not balance wording; else bank-profile amount
  patterns with the profile's default currency (INR if none).
- **Direction (`findDirection`):** if refund words `refund(ed)?|reversed|reversal` → credit. Else find first
  debit word (`debited|spent|withdrawn|paid|purchase|sent|deducted|debit`) and first credit word
  (`credited|received|deposited|refund(ed)?|reversed|reversal|cashback|credit` not followed by ` card`). Neither → null;
  only one → that; both → **whichever appears first**.
- **Merchant (`findMerchant`)** tries, in order, bank-profile patterns, then:
  - labelled field: `(merchant( name)?|location|payee|beneficiary|at)\s*[:\-]\s*VALUE` up to `,;\n`, `. ` or end;
  - debit: `at|@ NAME`, `to|towards [VPA] NAME`, `Info:? …`;
  - credit: `from|by [VPA] NAME`, `Info:? …`.
  - NAME = `[A-Za-z][A-Za-z0-9 &.'@_\-*/]{1,40}?` stopped before ` on|via|ref|refno|txn|using|for|from|is|was|dated|avl|upi|not|(`, `,;\n`, `.` + space/end, or end.
  - **cleanMerchant:** collapse whitespace, strip trailing `.,;:-*/` and spaces; reject if < 2 chars or starts with
    `your|you|a/c|ac|acct|account|card|xx` (own account, not a counterparty).
- **Account (`findAccount`):** `(a/c|acct|account|ac|card)[.] [no.] [ending [with|in]] [:-] [x*]* (\d{3,6})` or
  `[x*]{2,}(\d{3,6})`; returns the **last 4** digits.
- **Date (`findDate`):** first date-looking token (ISO, numeric d-m-y with optional time, or `d Mon yy`) → `parseSmsDate`.
- **smsSnippet(body):** label for rows without a merchant: a debit- or credit-style merchant if either is found,
  else the first 6 words after removing amounts/numbers.
- **incomeCategory(body):** `salary|payroll|wps` → `salary`; refund words → `refund`;
  `transfer(red)?|remittance|received|aani|imps|neft|rtgs|upi|ipi` → `transfer-in`; else null.
- **incomeLabel(body):** salary words → `"Salary"`; refund words → `"Refund"`; else null.
- **extract(body, parserId, hints)** returns ParsedSms only when both amount and direction are found.

### 6.5 Bank profiles and filters
Built-in profiles (`banks.ts`), matched by sender regex, all default INR:

| id | Name | Sender | Extra patterns |
|---|---|---|---|
| hdfc | HDFC Bank | `/HDFC/i` | merchant: a line `To <name>` |
| icici | ICICI Bank | `/ICICI/i` | merchant: `; <name> credited` |
| sbi | State Bank of India | `/SBI/i` | amount: `(debited\|credited) by <num>`; merchant: `trf to <name> Ref`, `transfer from <name> Ref` |
| axis | Axis Bank | `/AXIS/i` | merchant: `UPI/P2A\|P2M/<digits>/<name>` |
| kotak | Kotak Mahindra Bank | `/KOTAK\|KMBL/i` | merchant: `to <vpa>@<handle>` |

`parseSms(sms, customParsers)` = first custom parser that matches → bank profile → generic.
**Important:** at import time this chain is *not* used (§7.2). It is used only to **suggest** kinds in setup and,
for the bank list, to pre-tick senders and name new cards.

**skipReason(body, sender)** (first match wins):
| Reason | Pattern (case-insensitive) |
|---|---|
| Personal message | sender (spaces/dashes removed) matches `^\+?\d{8,}$` |
| OTP / verification code | `otp`, `one time password`, `verification code`, `security code` |
| Payment request, not a payment | `has requested`, `collect request`, `requested money`, `payment request` |
| Upcoming debit reminder | `will be debited`, `is due`, `due on`, `due date`, `auto debit … scheduled` |
| Promotional | `pre-approved`, `offer`, `apply now`, `click here to avail`, `cashback up to`, `win` |
| Failed or declined | `declined`, `failed`, `unsuccessful`, `could not be processed` |

### 6.6 Sender key and template key (`templates.ts`)
- **senderKey(sender):** trim, upper-case, remove a leading `XX-` (two letters + dash) and a trailing `-X`
  (dash + one letter). `VM-HDFCBK`, `AD-HDFCBK-S`, `HDFCBK` → `HDFCBK`.
- **isPersonalSender:** 8+ digit phone number (as above).
- **templateKey(body):** the "kind" of a message:
  1. Replace every number `\d[\d,]*(\.\d+)?` with `#`; split on anything that isn't a letter or `#`; drop empties.
  2. A word "is caps" if it has 2+ consecutive capitals and equals its upper-case form. If **fewer than half** of the
     lettered words are caps (a mixed-case message), caps words are masked as `*` (merchant names), **except** the
     keep-list: `AED INR RS UPI ATM POS OTP NEFT IMPS RTGS EMI SMS ID PIN IBAN VPA AC CR DR UAE ENBD ADCB FAB HDFC ICICI SBI`.
     All-caps messages are not masked.
  3. Other words are lower-cased; consecutive `*` collapse to one; keep the **first 8** tokens; join with spaces.
  - Example: `Purchase amount of AED 12.00 at GLOBALFASHION.AE on…` → `purchase amount of aed # at * on`.
- **ruleFor(sms, rules):** rule with equal senderKey and templateKey.
- **applyRule(rule, sms)** (only for `import` rules): amount = `findAmountCandidates(body)[amountIndex]` (null if
  missing); currency = candidate's currency or the rule's; direction = rule's; merchant = `findMerchant(body,
  direction)`; accountHint = `findAccount` or first digits candidate's last 4; date = `findDate`; parserId `rule:<id>`.

### 6.7 Kind suggestion and grouping (setup)
- **Kind labels:** transaction "Transaction", otp "OTP", promo "Promotional", reminder "Reminder", request
  "Payment request", failed "Failed / declined", money "Has an amount", other "Other".
- **suggestKind(sample):** amounts = candidates; `firstReal` = index of first non-balance candidate (0 if none);
  fallback = {label: `money` if any amount else `other`, amountIndex firstReal, direction = findDirection or debit,
  currency = that candidate's currency or AED}. If `skipReason(body)` (no sender) → fallback with the mapped label.
  Else run `parseSms(sample, [])`; if it parses → label `transaction`, amountIndex = candidate whose minor equals the
  parsed amount (else firstReal), parsed direction and currency.
- **groupMessages(messages):** group by senderKey (collect raw addresses), then by templateKey. For each kind: its
  messages, `suggestion = suggestKind(newest message)`, `digits` = distinct last-4 (findAccount or first digits
  candidate) across its messages. Kinds sorted by message count, largest first.

### 6.8 Custom parser ("SMS format")
- **Fields:** `amount`, `merchant`, `date`, `account`. A **FieldMark** is `{field, start, end}` (end exclusive) on the sample.
- **buildPattern(sample, marks):** errors (exact texts): no amount → `Mark the amount.`; bad span → `A marked span is
  empty.`; same field twice → `<field> is marked twice.`; overlap → `Marked spans overlap.`. Otherwise sort marks by
  start and build: literal text between marks is **generalized** (each number → `\d[\d,]*(?:\.\d+)?`, each whitespace
  run → `\s+`, every other char regex-escaped) and each mark becomes a capture:
  amount `(\d[\d,]*(?:\.\d+)?)`, merchant `(.+?)`, date `(.+?)`, account `([A-Za-z*]*\d+)`. Returns pattern + field order.
- **applyCustomParser(parser, sms):** if `senderContains` set, sender must contain it (case-insensitive). Regex is
  `^\s*` + pattern + `\s*$` with flags **case-insensitive + dot-matches-newline**; invalid regex → no match. Amount must
  parse > 0. Merchant → cleanMerchant; account → digits only, last 4; date → parseSmsDate. parserId `custom:<id>`.

---

## 7. Import pipeline

### 7.1 Ingest context
Loaded once per batch: all `sms_rules`, all custom parsers, all accounts (`id, currency, smsHint`), all merchant
rules, and a **DedupeIndex** of non-rejected transactions with `occurred_at` between (batch min receivedAt − 1 day)
and (batch max + 1 day).

### 7.2 `processSms(sms, ctx)`: the decision for one SMS
1. **Rule:** `ruleFor`. If its action is `ignore` → *ignored* ("You chose to ignore this kind of message").
   If `import` → `applyRule`; remember the rule's `fixedAccountId`.
2. **Custom parsers:** if nothing parsed yet, try each custom parser (newest first) until one matches.
3. **Nothing parsed:** the sender is *configured* if its senderKey has any rule, or any custom parser's
   `senderContains` is a substring of it. If configured **and** `skipReason(body)` is null **and** the body has at
   least one amount candidate → *unparsed* (goes to `unread_sms`). Otherwise *ignored* with reason
   "Not a transaction" (configured) or "Not a configured bank SMS".
4. **occurredAt:** the parsed date if it exists **and is a different calendar day** from the receive time; otherwise the
   SMS receive time (SMS dates rarely carry a time; same-day duplicates depend on precise times).
5. **Account:** if the SMS has digits → the account whose `smsHint` last-4 equals them (unknown digits → **none**,
   never guessed). If no match by digits → the rule's fixed account. If the SMS had no digits and no fixed account →
   the only account in that currency, if exactly one exists.
6. **Duplicate:** `dedupe.find(...)` (§10.4).
7. **Merchant:** parsed merchant, else for credits `incomeLabel(body)`.
8. **Category:** `suggestCategory(merchant, merchantRules)`, else for credits `incomeCategory(body)`.
9. Result *draft* with all fields + `smsHash`, sender, body, parserId.

### 7.3 `importBatch(messages, {autoAccept, onProgress})`
- Messages are processed in **chunks of 200**; between chunks the UI must stay responsive and progress is reported
  (`read` so far etc.).
- Per chunk: decide every SMS in memory; each draft gets a new id and status `accepted` if `autoAccept` and not a
  possible duplicate, else `pending`; add every draft to the DedupeIndex immediately (so a later SMS in the same batch
  is flagged against it). Then in **one DB transaction**: `INSERT OR IGNORE` drafts (source `sms`) with a prepared
  statement, `INSERT OR IGNORE` unparsed SMS into `unread_sms`, and delete from `unread_sms` every hash that produced a draft.
- A draft whose insert was ignored (its `sms_hash` already exists) counts as *already imported* (summary `ignored`).
- **ImportSummary** `{read, added, accepted, duplicates, ignored, unread}`: `added` = inserted; of those, duplicates
  counted in `duplicates`, the rest with status accepted in `accepted`.
- Performance target from the tests: **5,000 SMS in under 15 s** with correct duplicate flags.

### 7.4 Entry points
| Function | Used by | Behaviour |
|---|---|---|
| `importSms(sms)` | Paste an SMS | One message, no auto-accept → pending. Returns the outcome (draft / ignored / unparsed / already-imported). |
| `importBatch(messages, autoAccept: true)` | SMS setup | Accepted straight into totals; duplicates pending. |
| `scanInbox()` | App open/foreground, *Check now* | §7.5 |
| `recheckUnread()` | After saving a custom format | Re-runs all `unread_sms` oldest first through `importBatch` (pending). Matched ones leave `unread_sms`. |
| `clearSmsTransactions()` | SMS setup, before importing | Deletes **all** `source='sms'` transactions (any status, any sender) and all `unread_sms`, in one transaction. |

### 7.5 Inbox scan (`scanInbox`)
- Does nothing (returns an empty summary) without READ_SMS permission or before anything is configured
  (`sms_rules` or `custom_parsers` non-empty).
- Concurrent calls share **one run** (a second call returns the running one).
- Reads from `lastSmsScanAt`; if missing, from **now − 24 h** (never the whole inbox).
- Loop: read up to **500** inbox messages strictly after `after`, oldest first, from **all senders**; if empty stop;
  `importBatch` (pending); `after` = last message's date; save `lastSmsScanAt = after`; report progress; stop when a
  page has fewer than 500.
- At the end set `lastSmsScanRunAt = now` and notify.
- `waitForScan()` lets setup and reset wait for a running scan.
- **Triggers:** once when the tab shell first appears, and every time the app returns to the foreground (only if
  permission granted). Errors are logged, not shown. Manual *Check now* shows progress and errors (§12.12).

### 7.6 Native inbox reader (port of `SmsReaderModule.kt`)
- **hasPermission()**: READ_SMS granted.
- **listSenders(sinceMs)** → `[{sender, count, moneyCount, lastDate}]`: query `Telephony.Sms.Inbox.CONTENT_URI`
  (`address, body, date`) where `date > sinceMs`; aggregate per address; `moneyCount` counts bodies matching
  `(?i)(INR|Rs\.?|₹|AED|Dhs?)\s*\d|\d\s*(INR|AED)\b`. Bodies never leave this function.
- **readInbox(afterMs, limit, senders?)** → `[{sender, body, date}]`: `date > afterMs`, optional `address IN (…)`,
  `ORDER BY date ASC`, at most `limit`. Callers page by passing the last returned date.
- `readMessages(addresses, sinceMs)` pages with 500 per page until a short page, reporting the count read.

---

## 8. Categories and learning

### 8.1 Rules
- A category has a kind (`expense` / `income`). Users can add categories (name + kind; appended with `sort = MAX(sort)+1`)
  and delete **any** category, including seeded ones (see §13 Q4). Deleting sets its transactions' category to null
  and cascades its merchant rules.
- **Refund** (`refund`) is special: it is income-kind but **lowers spending** in totals (§11.1) and is **never
  learned** as a merchant's category.

### 8.2 Merchant key
`merchantKey(m)`: upper-case; drop `@` and everything after it; replace runs of non A–Z with a space; drop words of
length ≤ 1 and the noise words `PVT PRIVATE LTD LIMITED LLC INC CO FZ FZE FZCO FZLLC INDIA IN UAE AE DUBAI ABU DHABI
SHARJAH UPI POS THE`; join with single spaces. `swiggy@ybl`, `SWIGGY  PVT LTD`, `Swiggy*Order 123` → `SWIGGY`.
(Note: digits are removed, so `Swiggy*Order 123` → `SWIGGY ORDER`; tests assert the first two only.)

### 8.3 Suggest
`suggestCategory(merchant, rules)`: empty key → null. Exact key match wins immediately. Otherwise among rules whose
key is ≥ 3 chars and appears as **whole words** inside the merchant key (`" KEY "` contained in `" MERCHANT "`), take
the **longest** key. `GAZEBO` matches `GAZEBO KARAMA`.

### 8.4 Learn
`learnRule(merchant, categoryId)`: skip if either is empty or the category is `refund`; upsert
`merchant_key → category_id` with `updated_at = now`. Learning happens when:
- a transaction is saved from the edit form (create or update);
- transactions are accepted one by one (`setStatus accepted`): learns from each accepted row that has merchant and category;
- *Approve all*: learns from every row being approved (before the update).
Learning on accept in setup's auto-accept import does **not** happen (rows are inserted already accepted).

### 8.5 Appearance
- Fixed icon + colour slot per seeded category (table §4.3). Unknown (user) categories: icon 🏷️, colour slot =
  `abs(id.hashCode()) % 8` (the JS code computes `h = h*31 + charCode` in 32 bits, which is exactly Kotlin/Java `String.hashCode()`). Null category: ❔ in neutral grey.
- Palette (8 hues: blue, orange, aqua, yellow, magenta, green, violet, red):
  - light `#2a78d6 #eb6834 #1baf7a #eda100 #e87ba4 #008300 #6250d6 #e34948`, neutral `#8a8984`
  - dark `#3987e5 #d95926 #199e70 #c98500 #d55181 #008300 #9085e9 #e66767`, neutral `#8f8e88`
- Tint = colour at alpha `0x26` (~15%) for icon circles and pills. Single-series chart colour = blue.
- **CategoryIcon:** circle (default 36 dp) in tint with the emoji at half size. **CategoryPill:** small rounded pill,
  emoji + name (12 sp, semibold).

---

## 9. Accounts and cards

- **Kinds and labels:** `credit_card` "Credit card", `debit_card` "Debit card", `bank` "Bank account".
- **Label:** `"<name> ••<last4>"` when it has a hint, else name.
- **List order:** `kind, currency, name` (screen groups as Credit cards → Debit cards → Bank accounts).
- **Create:** name trimmed; hint = last 4 of the trimmed digits or null. In the same transaction, link every
  transaction with `account_id IS NULL` and `substr(account_hint, -4) = hint`; return the number linked.
- **Rename:** ignored when the trimmed name is empty.
- **Delete:** transactions keep their data, `account_id` → null (FK). Rules' `fixed_account_id` → null.
- **Detected cards** ("Found in your SMS"): group non-rejected transactions with `account_hint` set and
  `account_id` null, whose last 4 isn't any account's `sms_hint`, by (last 4, currency): count, latest
  occurred_at, sender of the latest such transaction. Ordered by count desc.
- **setAccountHint(id, hint)** (setup "use existing card"): set the account's hint to the last 4 and link unlinked
  transactions with those digits.
- One account represents **one** card number: in setup an account is offered for a number only if it has no hint
  or the same hint.

---

## 10. Transactions

### 10.1 Status lifecycle
```text
SMS import ──► pending ──accept / save──► accepted
                  │                          │
                  └──reject──► rejected ◄────┘ (delete of an SMS transaction)
setup import ──► accepted (or pending if possible duplicate)
manual create ──► accepted
```
- **Rejected** SMS transactions stay in the table (hidden everywhere) so their `sms_hash` stops re-import.
- **Delete** of a manual transaction removes the row. **Delete** of an SMS transaction sets it to rejected.
- Saving the edit form always sets status `accepted` (that is how "Save and accept" works).

### 10.2 Queries
| Query | Definition |
|---|---|
| Pending list | `status='pending'` ordered by `occurred_at DESC` |
| Pending count | for the Review tab badge and the Overview banner |
| Accepted in range | `status='accepted'` and `from ≤ occurred_at < to`, newest first, **LIMIT (default 1000)** |
| Summary inputs | accepted in range, no limit: amount, currency, direction, category, occurred_at |
| Dedupe window | non-rejected with occurred_at in [from − 1 day, to + 1 day] |

### 10.3 Bulk actions
- `setStatus(ids, status)`: in one transaction, chunks of 500 ids; updates `status` and `updated_at`; when accepting,
  learns categories from those rows.
- `approveAllPending(includeDuplicates)`: where `status='pending'` (and `duplicate_of_id IS NULL` unless including
  duplicates); learn first, then update; returns the count.

### 10.4 Duplicate detection
- Window **15 minutes**. A candidate duplicates an existing transaction when amount, currency and direction are equal,
  the accounts don't conflict (either side null counts as compatible), and |Δt| ≤ 15 min. The **closest in time** wins.
- `DedupeIndex` buckets by `amount|currency|direction` for speed and grows as drafts are created.
- A match only **flags** (`duplicate_of_id`); nothing is deleted. Duplicates never auto-accept.

### 10.5 Title of a row
Merchant if present (bold); else `smsSnippet(sms_body)` (smaller, italic, muted); else "Money in" / "Money out".
Card name: the account label, else `"Card ••<last4>"` from `account_hint`, else nothing.

---

## 11. Dashboard calculations

### 11.1 `summarize(transactions)` per currency (sorted by currency code)
- credit **and** category `refund` → `refund` total;
- other credit → `in`;
- debit → `out`, and added to its category's spending (null = uncategorised).
- Output: `inMinor = in`, `outMinor = out − refund`, `refundMinor`, `netMinor = in − (out − refund)`,
  `topSpending` = categories by spending desc. (Category spending is **not** reduced by refunds.)

### 11.2 `dailySpending(txs, currency, from, count, today)`
- `days[i]` = sum of **debits** in that currency on calendar day `from + i` (DST-safe rounding).
- `elapsed = clamp(round((startOfDay(today) − startOfDay(from)) / 1 day) + 1, 1, count)`.
- `averageMinor = round(sum(days[0..elapsed)) / elapsed)` so a running month averages over days so far.
- `peak` = first day with the highest positive value, or null if no spending.
- Range: month view = every day of the selected month; all-time view = the **last 30 days** ending today.

### 11.3 Donut slices
At most 5 slices: if more than 5 categories, show the top 4 and fold the rest into a grey `Other (n)` slice (icon
`•••`). Arcs drawn end to end from 12 o'clock clockwise with a 2 px gap between slices when there is more than one.
Size 168 dp, stroke 22 dp.

---

## 12. Screens

Navigation: a root stack holding the **tab shell** (no header) and these stack screens (with header and back):

| Route (Expo) | Header title | Presentation |
|---|---|---|
| `(tabs)` | none | Bottom tabs: **Overview**, **Review** (badge = pending count when > 0), **Transactions**, **More**. Material icons `donut_large`, `inbox`, `receipt_long`, `more_horiz`. |
| `transaction/[id]` | Transaction | Modal. `id = "new"` creates. |
| `import-sms` | Paste an SMS | |
| `sms-setup` | Set up bank SMS | |
| `accounts/index` | Cards & accounts | |
| `accounts/new` | Add card or account | Optional params `digits`, `currency`, `sender` |
| `categories` | Categories | |
| `parsers/index` | SMS configuration | |
| `parsers/new` | Set up SMS format | Optional params `sample`, `sender` |

Shared UI building blocks (from `components/ui.tsx`): Screen (scrolling page, optional large 32 sp title for tabs),
Card (rounded 16, hairline border, 16 padding), Label (13 sp, semibold, upper-case, muted), Body (16 sp), Button
(primary / secondary / danger / ghost, min height 44, radius 12), Chip (pill, selected = primary), ChipRow (wrapping),
Field (label + text input, multiline min height 110), Amount, Row (title/subtitle/right, hairline divider), Empty
(centred card with title, message, actions), MonthSwitcher (‹ label ›). Theme tokens (§12.14). Lists react to every
database change automatically.

### 12.1 Overview tab
- Title **Overview**. Chips **Month** / **All time** (default Month). In Month mode a MonthSwitcher (default current
  month, e.g. "November 2026", unbounded both ways).
- **ScanCard** (§12.12).
- If pending > 0: card *"N transaction(s) waiting for review"* + **Review now** (switches to Review tab).
- If there are no accepted transactions in range: Empty *"Nothing this month"* (Month) or *"No transactions yet"*
  (All time), message *"Import a bank SMS or add a transaction to get started."*, buttons **Import SMS** (→ Paste an
  SMS) and **Add manually** (→ new transaction).
- Otherwise, one **summary card per currency** (AED before INR): currency label; **Money in** (credit style),
  **Money out** (debit style), **Net** (signed, red if negative else green); if refunds > 0: *"Money out is after
  <refunds> of refunds."*; the donut (§12.13) and daily chart (§12.13).
- **Recent** card: 5 newest accepted transactions in range (TransactionRow) + **See all** (→ Transactions tab).

### 12.2 Review tab
- Empty: title **Review**, Empty *"All caught up"* / *"New transactions from your bank SMS show up here for you to
  check."* + **Paste an SMS**.
- Otherwise a virtualized list. Header: title, help text *"Accept what looks right, edit what doesn't, and reject
  double entries. Your category choices are remembered for next time."*, button **Approve all (N)**.
- **Approve all** confirmation: title *"Approve N transaction(s)?"*. If D duplicates: message *"D of them may be
  duplicates of another entry. Approving those counts the payment twice."*, buttons Cancel, **All except D
  duplicate(s)**, **All N**. Without duplicates: *"They will be added to your totals."*, buttons Cancel, **Approve**.
- **Review card** per pending transaction:
  - category icon (40), title (§10.5), category pill (or "Uncategorised"), amount (18 sp);
  - meta line: date-time · card name or "No card";
  - if `account_hint` and no account: *"Card ••1234 isn't in your cards yet."* + **Add card** → Add card screen
    prefilled with digits, currency, sender;
  - if a possible duplicate: warning box *"Possible duplicate: same amount as the entry from <date-time> within a few
    minutes. Reject it if this is the same payment."* (or *"…as an existing transaction…"* if the other row isn't pending);
  - if it has an SMS body: toggle **Show SMS from <sender>** / **Hide SMS** revealing the raw text;
  - buttons **Reject** (danger), **Edit** (→ transaction screen), **Accept**.

### 12.3 Transactions tab
- Title **Transactions**; **Add transaction** button; MonthSwitcher (default current month).
- Accepted transactions of the month (max 1000, §13 Q3) grouped by day (`10 Nov 2026`), one card per day, newest
  first. Each row: TransactionRow. Empty: *"No transactions"* / *"Accepted transactions for this month appear here."*
- **TransactionRow:** category icon, title (§10.5), category pill, meta (card name · date), amount; tap → transaction screen.

### 12.4 More tab
- Title **More**; ScanCard; a card with rows (title, subtitle, ›):
  - **SMS configuration**: "Choose which amount to take from a bank SMS" → parsers list
  - **Cards & accounts**: "Credit and debit cards, found from SMS by last 4 digits"
  - **Paste an SMS**: "Test how a single message is read"
  - **Categories**: "Categories and remembered merchants"
- Danger button **Delete all data and start over** → confirm *"Delete all data and start over?"* / *"All
  transactions, cards, categories, SMS setup and learned merchants are deleted, like a new install. Your SMS stay on
  the phone, so you can import them again. This can't be undone."* Cancel / **Delete everything** (destructive):
  wait for scan → reset (§4.4) → open SMS setup.
- Footer: *"Your data stays on this device. SMS are read on the phone and never uploaded."*

### 12.5 Transaction screen (add / edit), modal
- Loads the transaction (`id`) or starts empty (`new`: expense, amount empty, currency AED, date today, no account,
  no category, no note).
- **Kind chips:** Expense / Income / Refund. Kind = refund if credit + category refund; income if credit; else expense.
  Switching to Refund sets credit + category refund; to Income/Expense sets direction and **clears the category**.
- **Amount** (decimal keyboard, placeholder `0.00`).
- **Card / account** chips: None + every account as `"<label> (<currency>)"`. Picking an account also sets currency.
- **Currency** chips (AED / INR): only shown when no account is chosen. On save the account's currency wins.
- Refund note: *"A refund is money back from a shop. It lowers your spending and doesn't count as income."*
- **Merchant** field, labelled **From** for income; placeholder "e.g. Gazebo".
- **Category** chips (hidden for refunds): categories of the current kind except Refund, shown with icon; tapping the
  selected one clears it. Hint when merchant and category are set: *"Future transactions from "<merchant>" will get
  this category."*
- **Date** field `YYYY-MM-DD` (free text; see §13 Q6). **Note** field (optional).
- If from SMS: card **SMS from <sender>** with the body.
- **Save** (or **Save and accept** when pending): validation, in order: amount must parse → alert *"Enter an amount"*
  / *"Use a number like 45.50."*; date must parse → *"Check the date"* / *"Use the format YYYY-MM-DD."*. If editing and
  the date is unchanged, keep the original time; otherwise use **12:00 local** of that day. Merchant/note trimmed,
  empty → null. Saves (status accepted), learns merchant→category, goes back.
- **Reject** (danger) when pending: sets rejected, goes back.
- **Delete** (danger) when accepted: confirm *"Delete transaction?"* (message *"The SMS will not be imported again."*
  for SMS ones), Cancel / Delete → remove (§10.1), go back.

### 12.6 Cards & accounts
- **Found in your SMS** card (when detected cards exist): help text *"These card or account numbers appear in your
  bank messages. Add them so you can see which card each payment was made with."*; rows *"Ending ••1234"* with subtitle
  *"N transaction(s) · AED · <sender> · last <date>"* and **Add** → Add card (prefilled).
- One card per kind with accounts (Credit cards, Debit cards, Bank accounts): row label + currency, **Rename** (inline
  field *"Name for card ••1234"* or *"Name"*, placeholder "e.g. ENBD Platinum", Save disabled if empty / Cancel; Enter
  saves) and **Delete** (confirm *"Delete <label>?"* / *"Its transactions are kept but no longer linked to it."*).
- Empty text: *"No cards or accounts yet. Add one, or scan your SMS and the cards will be found for you."*
- Button **Add card or account**.

### 12.7 Add card or account
- If opened with digits: *"Found in your SMS from <bank>: a card or account ending ••1234."* where bank = the bank
  profile name matching the sender, else the sender.
- **"Is this a credit card or a debit card?"** chips Credit card / Debit card / Bank account; **no default**; saving
  without one shows *"Choose one to continue."*
- **Name** (prefilled with bank; placeholder "e.g. ENBD Platinum"); **Last 4 digits** (number pad, non-digits removed,
  keeps last 4); hint *"SMS mentioning these digits (like "card ending 1234") are linked to this <kind>."*; **Currency**
  chips (prefilled from param, default AED).
- **Save:** name falls back to the kind label; creates (§9); if any transactions were linked, alert *"Added"* /
  *"N earlier transaction(s) was/were linked to this <kind>."*; go back.

### 12.8 Categories
- **Remembered merchants**: rows key + category name + **Forget** (deletes the rule). Empty: *"When you accept or edit
  a transaction with a category, the merchant is remembered here."*
- **Expense categories** and **Income categories**: rows `<icon>  <name>` + **Delete** (confirm *"Delete <name>?"* /
  *"Its transactions become uncategorised."*).
- **Add category**: Name (placeholder "e.g. Kids"), chips Expense/Income (default Expense), button disabled when name
  is empty; clears the field after adding.

### 12.9 SMS setup wizard
Steps: `period → senders → kinds → cards → confirm → importing → done`. While busy, a spinner card with a status text
replaces the step. Errors show in a card at the top (on the importing step with a **Back** to confirm).

1. **Period**: *"How far back should we read?"* / *"Only bank messages from this period are imported. You can run the
   setup again later."* Buttons **1 month, 3 months, 6 months, 1 year**. On tap: ensure permission (§2.1); start =
   now minus N calendar months; busy *"Counting your messages…"*; list senders (§7.6) and merge them.
   - **mergeSenders:** drop personal numbers; merge by senderKey (addresses, summed counts); **suggested** = a bank
     profile matches the key, or `moneyCount ≥ 2` and `moneyCount / count ≥ 0.3`; sort by moneyCount desc, then count
     desc. Suggested senders start ticked.
2. **Senders**: *"Which senders are your banks?"* / *"Personal numbers are hidden. Tick only the banks and card
   providers you want to track."* Checkbox rows for senders with amounts (title = key, subtitle *"N messages · M with an
   amount"*, badge *"bank?"* when suggested); empty: *"No messages with amounts in this period."* Collapsible
   *"Show N senders without amounts"* / *"Hide other senders"*. Button *"Next: message types (N selected)"*, disabled
   when none. Next: busy *"Reading messages from the selected banks…"* then *"Reading messages… N"*; read all messages
   from the selected addresses since start; record **readAt = time reading started**; group (§6.7); initial choices:
   include = suggestion is a transaction, plus its amountIndex/direction/currency.
3. **Kinds**: intro *"Each bank sends several kinds of messages. Tick only the ones that are real payments or money
   received. OTPs and offers are left out."* One card per sender. "Main" kinds = suggested transactions or included;
   others behind *"Show N other kinds (OTP, offers…)"* / *"Hide other messages"*. Each kind: checkbox row
   *"<label> · N message(s)"* with the newest sample as subtitle; it can be ticked only if the sample has an amount.
   When included, an editor: *"Which amount is the transaction?"* with one selectable box per amount candidate
   (formatted amount or bare number, *"(looks like a balance)"*, and its context); picking one also sets currency from
   it; chips **Money out / Money in** and **AED / INR**. If a sender has no main kinds: *"No transaction messages
   recognised."* Button *"Next: cards (N transactions)"* (N = messages in included kinds), disabled at 0. On next,
   detected digits that already equal an account's hint are pre-chosen as that account.
4. **Cards**: for every distinct card number in included kinds (sorted by message count): card *"Card ending ••1234"*,
   *"N messages · SENDER1, SENDER2"*, chips: each usable account, **+ New card** (opens a form: kind chips with *"Choose
   one."* until picked, Name prefilled with the first sender key, placeholder "e.g. ENBD Platinum", currency chips
   defaulting to the kind's currency), **Skip**. Then *"Messages without a card number"* / *"Optionally pick the
   account these belong to."*: for each included kind without digits, sender + sample and chips None / accounts
   (becomes the rule's fixed account). Empty: *"No card numbers in these messages."* Continue requires a choice for
   every number (new needs kind + non-empty name): hint *"Choose a card, "New card" or "Skip" for every number above."*
   Button *"Next: review and import"*.
5. **Confirm**: *"Ready to import"* / *"N transaction message(s) from G bank(s) will be added to your totals.
   Possible duplicates go to Review."*; if SMS transactions exist: *"The K transaction(s) imported from SMS earlier will
   be deleted first. Transactions you added by hand stay."*; *"From now on, only these kinds of messages are read. New
   ones wait in Review."*; **Import**, **Back**.
6. **Importing** (in this order): wait for any scan → create new cards / set hints on existing ones (so imports link
   by digits) → `clearSmsTransactions` → replace rules for the senders in this setup (delete their old rules, insert one
   rule per kind: included → import, others → ignore, sample = newest message) → `importBatch(messages, autoAccept)`
   with busy *"Importing… X of N messages checked"* → `lastSmsScanAt = readAt`, `lastSmsScanRunAt = now`.
7. **Done**: *"N transaction(s) added to your totals."* + *" D possible duplicate(s) in Review."*; **Go to Overview**
   (closes the stack back to the tabs).

### 12.10 Paste an SMS
- Text *"Paste a bank SMS to see how it's read. On the Android build, your inbox is scanned automatically."*
- **Try an example** chips fill sender + body:
  - *ENBD card*: `EmiratesNBD` / `Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00`
  - *HDFC UPI*: `VM-HDFCBK` / `Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123456789\nNot You? Call 18002586161`
  - *OTP*: `VM-HDFCBK` / `482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share.`
- Fields **Sender** (caps, placeholder "e.g. VM-HDFCBK") and **Message** (multiline). **Read this SMS** (disabled when
  empty or busy) → `importSms` with `receivedAt = now`.
- Result card:
  - draft: *"Added to review"*, amount (22 sp), merchant or *"Merchant not found"*, *"Read by: <parserId>"*, warning
    *"Flagged as a possible duplicate."* if so, **Go to review**;
  - ignored: *"Ignored"* / *"<reason>. Only the kinds of bank SMS you set up are imported."* + **Set up this SMS format**;
  - already imported: *"Already imported"* / *"This exact SMS was imported before."*;
  - unparsed: *"Couldn't read this SMS"* / *"It's from one of your banks but doesn't match a kind you set up. You can
    teach the app this format."* + **Set up this SMS format**.
  - "Set up this SMS format" opens the format builder with `sample` and `sender`.

### 12.11 SMS configuration (parsers list) and format builder
**List:** button **Set up a new SMS format**; if unread SMS exist: *"Messages we couldn't read (N)"* / *"These have
an amount but no format matched. Set one up and similar messages are read from then on."*, the **newest 20** with
*"<sender> · <date-time>"*, 3-line body, **Set up this format** (→ builder with sample/sender) and **Ignore**
(delete from unread). **Your formats**: rows name + *"Sender contains "X""* or *"Any sender"* · currency · Money
out/in, **Delete** (confirm *"Delete <name>?"*); empty *"None yet. Yours are tried before the built-in ones."*
**Built in**: rows for the 5 bank profiles (*"INR · matched by sender"*) and *Generic* (*"Any bank, AED or INR, using
common wording"*); see §13 Q1.

**Builder** (`parsers/new`):
- **Bank SMS** multiline field (prefilled from `sample`). Editing it resets all picks and re-guesses direction.
- **1. Which amount is the transaction?**: one box per amount candidate (amount, *"looks like a balance"*, *"✓ This
  one"* when picked, context). Empty: *"No amounts found in this message."* Picking sets currency if the candidate has one.
- The following appear once an amount is picked:
- **2. Money in or out?**: chips *"Money out (spent)"* / *"Money in (received)"* (default from `findDirection`, else
  out) and AED/INR (default AED).
- **3. Which card or account?**: chips *"Not in this SMS"* (default) and each digits candidate as `••1234`.
- **4. Merchant and date (optional)**: *"Pick one, then tap its first and last word."* Chips Merchant/Date (✓ when
  set). The sample is shown as tappable tokens (`\d[\d,]*(\.\d+)?` | letters | runs of other non-space chars). Tapping
  extends/sets the active field's token range: none → that token; same single token → clear; before → extend start;
  after → extend end; inside → reset to that token. Merchant tokens green `#13804B`, date tokens amber `#A35F00`, white text.
- **5. Save**: *"Only for senders containing (optional)"* (caps, prefilled from `sender`, placeholder "e.g. ENBD") and
  **Name** (placeholder "e.g. ENBD credit card"). Live: build errors in red; if built but the pattern doesn't read the
  sample back: *"This setup doesn't read the message back. Try marking fewer words."*; otherwise a preview box
  (*"Spent/Received <amount>"*, *"Card: ••1234 / —"*, *"Merchant: … / —"*, *"Date: <date> / when the SMS arrived"*).
- **Save and use for future SMS** (enabled only with a working preview): name = name, else sender, else *"My SMS
  format"*; store parser; `recheckUnread`; if any were added alert *"Saved"* / *"N more message(s) could be read and
  went to Review."*; go back.

### 12.12 ScanCard (on Overview and More)
- Before anything is configured: *"Set up your bank SMS"* / *"Pick how far back to read, which senders are your
  banks, which of their messages are transactions, and which card each one is. Only those messages are imported.
  Everything stays on this phone."* + **Set up bank SMS**.
- After: label *"Bank SMS"*; text: while scanning *"Checking new SMS… <progress>"*; after a manual scan the summary
  *"Checked N new SMS · A transaction(s) to review [· D possible duplicate(s)] [· U not understood]"*; otherwise
  *"Last checked <date-time>. New bank SMS of the kinds you set up go to Review."* (or without the first sentence if
  never run). Error: *"Check failed: <message>"*. Buttons **Check now** (*"Checking…"* and disabled while running),
  **Edit SMS setup** (→ setup).
- (The Expo-Go-only "needs the Android app build" variant is dropped in native.)

### 12.13 Charts
- **Spending by category donut** (per currency card): heading *"SPENDING BY CATEGORY"*; ring per §11.3; centre shows
  *"Spent"* + total, or for the selected slice *"<icon> <label>"*, amount and *"NN% of spending"*. Tapping a slice or a
  legend row selects it (others dim to 30% / 50%); tapping again deselects. Legend rows: colour dot, icon, label,
  amount, rounded percentage. Hidden when total is 0. Accessibility labels on ring and rows.
- **Spent per day** bar chart: heading *"SPENT PER DAY"*; big number = average (or the selected day's amount);
  caption *"Average per day · highest <amount> on <day>"* or *"Spent on <day>"*. Bars height 120 dp scaled to the peak
  (min 2 dp for non-zero), top corners rounded, series blue; tap a bar to select (others dim to 35%), tap again to
  clear. Four date ticks at 0, ⅓, ⅔, last. Hidden when there is no spending.

### 12.14 Theme tokens
| Token | Light | Dark |
|---|---|---|
| background | `#F6F7F9` | `#0E1012` |
| surface | `#FFFFFF` | `#181B1F` |
| surfaceAlt | `#EEF0F3` | `#22262B` |
| border | `#E1E4E8` | `#2D3238` |
| text | `#11181C` | `#ECEDEE` |
| textMuted | `#687076` | `#9BA1A6` |
| primary | `#1B6EF3` | `#5B9BFF` |
| onPrimary | `#FFFFFF` | `#0E1012` |
| income | `#13804B` | `#4CC38A` |
| expense | `#C9372C` | `#FF6B5E` |
| warning | `#A35F00` | `#F5B342` |
| warningBg | `#FFF4E0` | `#3A2A0E` |

Spacing 4/8/12/16/24; radius 8/12/16/pill.

---

## 13. Known quirks and decisions for the rebuild

**Decisions to take before building**

- **D1. Carry over existing data?** If the native app keeps the application id `com.najeemfasil.expense` and the same
  signing key, it can be installed over the Expo build and open the existing `files/SQLite/expense.db` (user_version
  6). That needs: identical schema (or a Room migration 6→7 that tolerates the existing schema), identical `smsHash`
  (§5.3), and the same `settings` keys. If not needed, a fresh install plus re-running SMS setup recovers all SMS
  transactions (manual ones and learned merchants would be lost). Recommended: support it; it is cheap if the schema is
  copied as-is.
- **D2. Background scanning.** Today SMS are only scanned when the app opens or returns to the foreground. Native makes
  an `SMS_RECEIVED` broadcast receiver or WorkManager job easy, but that is a **new feature** (and RECEIVE_SMS is another
  permission). Parity = foreground only.
- **D3. Reactivity.** Replace the global `notifyDbChanged` with Room Flows; make sure every screen that lists data
  updates after writes made elsewhere (e.g. accepting in Review updates the tab badge and Overview).

**Quirks found in the code (keep or fix deliberately)**

- **Q1.** Built-in bank profiles and the generic parser are **not used at import time**; only SMS rules and custom
  formats import. The SMS configuration screen still lists them under "Built in" and says *"Yours are tried before the
  built-in ones"*, which is now misleading.
- **Q2.** Running setup deletes **all** SMS transactions (including ones from senders not in this run, and rejected
  ones). Rejected SMS from the setup period can therefore come back (as accepted) on a re-run. Rules for senders not in
  this run are kept.
- **Q3.** The Transactions tab loads at most **1000** accepted transactions per month, silently truncated.
- **Q4.** Any category can be deleted, including `refund`, `salary`, `transfer-in`; deleting `refund` breaks refund
  handling in the transaction form and totals. Consider protecting seeded categories.
- **Q5.** Pasted SMS use `receivedAt = now`, so pasting the same text twice creates two hashes; "Already imported"
  only appears for identical sender+time+body.
- **Q6.** The date field is free text `YYYY-MM-DD` (it also accepts any `parseSmsDate` format). A native date picker is
  the natural replacement; keep "unchanged date keeps time, new date = 12:00".
- **Q7.** Inbox paging uses `date > last date`; messages sharing the exact millisecond at a 500-row page boundary can
  be skipped. Low risk; native can page with `date >= ?` plus hash de-dup, or by `_id`.
- **Q8.** The setup's "readAt" is when reading started, so SMS that arrive during setup are picked up by the next scan
  (intended). The 24 h fallback applies only if `lastSmsScanAt` is missing.
- **Q9. Regex porting:** JS and Java regex differ. Use `RegexOption.IGNORE_CASE` (ASCII is enough), and
  `DOT_MATCHES_ALL` for custom parsers (JS `s` flag). JS `\s` matches Unicode spaces (NBSP etc.); Java's does not
  unless `(?U)`/`UNICODE_CHARACTER_CLASS`. Bank SMS can contain NBSP, so test with it. Stored custom `pattern`s are
  JS-flavoured but only use escapes, `\d`, `\s+`, `(.+?)`, `[A-Za-z*]` and non-capturing groups, which Java reads the
  same; re-escape via the same rules (`[.*+?^${}()|[\]\\]`). Use `Locale.ROOT` for upper/lower-casing.
- **Q10.** `merchantKey` removes digits (`7-ELEVEN` → `ELEVEN`). Keep for compatibility with stored rules.
- **Q11.** Review's duplicate message can only name the other entry's date if that entry is also pending.
- **Q12.** Account delete leaves `account_hint`s, so the card reappears under "Found in your SMS". Intended.
- **Q13.** `iOS`/`web`/Expo Go branches (`inboxAvailable` false, "needs the Android app build" texts) are dropped.

---

## 14. Test fixtures to port

The Jest suites hold the behaviour contract (73 test blocks in 8 files). Port them as JVM unit tests with the same inputs and
expected outputs: `src/features/__tests__/logic.test.ts` (money, dates, categorizer, dedupe, summary, refunds, donut,
daily), `src/features/sms/__tests__/*.test.ts` (parse, candidates, custom parser, templates, ingest, real samples) and
`src/db/__tests__/repositories.test.ts` (migrations, import, approve, clear, detected cards, reset, learning, unread
recheck, 5,000-SMS performance). The real UAE samples that must always parse end to end:

```text
Purchase of AED 12.00 with Credit Card ending 9157 at GLOBALFASHION.AE, +971521253808. Avl Cr. Limit is AED 6,988.22
Purchase amount of AED 12.00 at GLOBALFASHION.AE on your Credit Card ending 9157 has been refunded to your card account. Avl Limit is AED 7,000.22.
 Credit Card Purchase\nCard Ending: 1122\nAt: ADNOC AL BUHAIRA 176, SHARJAH\nAmount: AED 124.52\nDate: 15/10/2026, 19:12\nAvailable Limit: AED 6,754.45
 Salary Deposited \nAmount: AED 1234.00\nAccount: 370XXX08XXX01\nDate: 26/09/2026 11:39 \nAvailable Balance: AED 1234.60
AED 500.00 has been credited to your account XXX4567 from JOHN SMITH via Aani. Avl Bal AED 2,000.00
```

---

## 15. Glossary

| Term | Meaning |
|---|---|
| Minor units | Amount × 100 (fils, paise), stored as an integer |
| Debit / credit | Money out / money in |
| Sender key | Bank sender id without operator prefix/suffix (`HDFCBK`) |
| Template key / kind | The masked opening words of a message; one sender's messages of the same shape |
| SMS rule | A configured kind: import (with amount position, direction, currency, fixed account) or ignore |
| Custom parser / SMS format | A user-built regex from a sample with marked amount/card/merchant/date |
| Unread SMS | From a configured sender, has an amount, not a skip type, but matched no rule or format |
| Account hint | Last 4 digits of the card/account named in an SMS |
| Possible duplicate | Same amount/currency/direction within 15 minutes of another, compatible account |
