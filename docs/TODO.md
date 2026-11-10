# Zeropense native Android rebuild: waterfall TODO

Build **one feature at a time, in this order**. A feature is finished only when every box under it is ticked and
its **Done when** line holds; then move on. Section numbers (§) point to [`FSD.md`](FSD.md), which holds the exact
rules, texts and numbers.

Order rationale: foundations → data → a fully usable **manual** expense tracker (F1–F8) → the SMS engine as pure,
tested logic (F9–F11) → SMS features on top, from the simplest entry point (paste) to the full setup and automatic
scan (F12–F18) → release (F19–F20). Every feature only depends on the ones above it.

Legend: `[ ]` todo · **Done when** = acceptance gate · *Port tests* = Jest cases to re-create on the JVM.

---

## F0. Decisions (before any code)
- [x] D1: carry over existing data from the Expo app: **yes**. Same application id `com.najeemfasil.expense`, the same
  signing key (the published APK is signed with the public React Native template `debug.keystore`, SHA-256
  `FA:C6:17:45…03:3B:9C`, which is checked in as `app/debug.keystore`), the same database file
  `files/SQLite/expense.db` with the same schema, and a bit-identical `smsHash`. (§13 D1)
- [x] D2: **parity**. SMS are scanned when the app opens or returns to the foreground only; no background scanning. (§13 D2)
- [x] Quirks Q1–Q13: **all kept as they are** ("exact app"). Notes:
  - Q6: the date stays a free-text `YYYY-MM-DD` field.
  - Q7: inbox paging stays `date > last date`.
  - Q9: regexes are ported through one helper that makes `\s` and `\d` behave as in JavaScript on every Android version.
  - Q13: the Expo Go / iOS / web branches are dropped (they cannot occur in a native Android app).
- [x] Stack: Kotlin, Jetpack Compose + Material 3, Navigation Compose, Coroutines/Flow, manual DI.
  Storage is **raw SQL on `androidx.sqlite` with the bundled SQLite driver** instead of Room: the Expo app's SQL
  (upserts, `INSERT OR IGNORE`, migrations) is reused word for word, the existing `expense.db` opens as is, and the
  same SQL runs in JVM unit tests. Reactivity is a change counter exposed as a `Flow` (the port of `notifyDbChanged`).
- [x] Two Gradle modules: `:core` (pure Kotlin/JVM: helpers, SMS engine, repositories, all ported tests) and
  `:app` (Android: Compose UI, SMS inbox reader).
- [x] Min SDK 24, target/compile SDK 36 (same as the Expo build), JDK 17+ (built with 21), Gradle 8.14, AGP 8.13.
- **Done when:** decisions are written down here.

## F1. Project foundation
- [x] New Android Studio/Gradle project (Kotlin DSL), application id, app name `Zeropense`, version 1.0.0. (§2)
- [x] Portrait only; edge-to-edge; follows system light/dark.
- [x] Adaptive icon from `assets/images/android-icon-*.png` (background `#E6F4FE`, monochrome icon). (§2)
- [x] Splash (SplashScreen API): background `#208AEF`, `splash-icon.png`, 76 dp. (§2)
- [x] Release build: R8 minify + resource shrinking; signing config; `arm64-v8a` only if any native lib appears.
- [x] Gradle speed settings: build cache on, `-Xmx4096m -XX:MaxMetaspaceSize=1024m` (port of `plugins/with-gradle-speed.js`).
- [x] Lint, ktlint/detekt, unit-test task; a one-command check (`./gradlew check`).
- **Done when:** an empty app installs, shows the splash and icon, and `./gradlew check` passes.

## F2. Design system and app shell
- [x] Theme tokens light/dark, spacing, radius (§12.14).
- [x] Shared composables: Screen (scroll + large title), Card, Label, Body, Button (primary/secondary/danger/ghost), Chip, ChipRow, Field (single/multiline), Amount, Row, Empty, MonthSwitcher, CheckRow (checkbox row from setup), confirm-dialog helper, spinner card. (§12)
- [x] Navigation: bottom bar with Overview / Review / Transactions / More (Material icons `donut_large`, `inbox`, `receipt_long`, `more_horiz`), badge slot on Review; stack destinations: Transaction (modal-style, `id` or `new`), Paste an SMS, Set up bank SMS, Cards & accounts, Add card or account (args digits/currency/sender), Categories, SMS configuration, Set up SMS format (args sample/sender), with the header titles from §12.
- [x] Placeholder content in each destination.
- **Done when:** every route opens with the right title and back behaviour, in light and dark.

## F3. Core helpers (pure)
- [x] Money: `Currency` (AED, INR), `Direction`, `normalizeCurrency`, `parseAmountToMinor` (truncating), `formatMoney` (en-AE / en-IN grouping, `−` and `+`, INR `₹` no space, `AED ` with space), `minorToInput`. (§5.1)
- [x] Dates: `parseSmsDate` (3 formats, day first, no roll-over), `startOfMonth`, `addMonths`, `startOfDay`, `dayAt`, and the display formats. (§5.2)
- [x] Ids: `newId()`; `hashString` = FNV-1a 32-bit over UTF-16 chars, 8-char hex; must match JS output bit for bit. (§5.3)
- *Port tests:* `logic.test.ts` › money, parseSmsDate. Add golden tests for `hashString` against values produced by the JS version.
- **Done when:** all helper tests pass on the JVM.

## F4. Database
- [x] Entities/tables exactly as §4.1 (`accounts`, `categories`, `transactions`, `merchant_rules`, `custom_parsers`, `unread_sms`, `settings`, `sms_rules`), with FKs (SET NULL / CASCADE), UNIQUE `sms_hash`, UNIQUE (sender_key, template_key), both indexes.
- [x] WAL + `foreign_keys = ON`.
- [x] Migrations 1→6 with seeds (§4.2, §4.3) in one transaction; `user_version = 6`. If D1 = yes: open an existing Expo `expense.db` without data loss.
- [x] Settings get/set (`lastSmsScanAt`, `lastSmsScanRunAt`). (§4.1)
- [x] Reset: drop all tables, re-migrate, re-seed. (§4.4)
- [x] Reactive reads (Flow) so every screen refreshes after any write. (§13 D3)
- *Port tests:* `repositories.test.ts` › migrates idempotently, resets to a fresh database.
- **Done when:** a fresh install has 18 categories and the `ASPORA` rule; running migrations twice changes nothing; reset restores that state.

## F5. Categories
- [x] Repository: list (`kind, sort, name`), create (sort = max+1), delete (transactions → uncategorised, rules cascade), list/learn/delete merchant rules; never learn `refund`. (§8.1, §8.4)
- [x] Categorizer (pure): `merchantKey` with noise words, `suggestCategory` (exact, then longest whole-word key ≥ 3). (§8.2, §8.3)
- [x] Appearance: icon + colour slot per seeded id, palette light/dark, neutral, `hashCode` slot for user categories, tint, series colour. (§8.5)
- [x] CategoryIcon and CategoryPill composables. (§8.5)
- [x] **Categories screen**: Remembered merchants (Forget), Expense / Income lists (Delete + confirm), Add category (name + kind). (§12.8)
- *Port tests:* `logic.test.ts` › categorizer.
- **Done when:** adding/deleting categories and forgetting merchants works and the lists update live.

## F6. Cards & accounts
- [x] Repository: list (`kind, currency, name`), create (+ link past transactions by last 4, return count), rename (ignore empty), delete, detected cards query, `setAccountHint`. (§9)
- [x] `accountLabel` (`name ••1234`), kind labels.
- [x] **Cards & accounts screen**: Found in your SMS (empty until SMS exist), groups by kind, inline Rename, Delete + confirm, empty text, Add button. (§12.6)
- [x] **Add card or account screen**: kind chips with no default + error, name, last 4 digits (digits only, last 4), currency, prefill from args, "N earlier transactions linked" alert. (§12.7)
- *Port tests:* `repositories.test.ts` › detects unknown cards and links past transactions (finish in F13 once SMS import exists).
- **Done when:** cards can be added, renamed and deleted.

## F7. Manual transactions
- [x] Transaction model, status lifecycle, queries: get, pending list/count, accepted in range (limit, see Q3), summary inputs, dedupe window. (§10.1, §10.2)
- [x] Save (create manual = accepted / update = accepted) + learn rule; remove (manual → delete, SMS → rejected); `setStatus` in chunks of 500 with learning; `approveAllPending(includeDuplicates)`. (§10.3)
- [x] Lookups: account/category names, card name (`Card ••1234` fallback). Row title rule (merchant / SMS snippet / Money in-out); the snippet needs `smsSnippet` from F9, so use merchant or Money in/out until then. (§10.5)
- [x] TransactionRow composable. (§12.3)
- [x] **Transaction screen**: Expense/Income/Refund chips and their rules, amount, card chips (sets currency), currency chips when no card, refund note, Merchant/From, category chips (toggle, hidden for refunds), learn hint, date (keep time if unchanged, else 12:00; picker per Q6), note, SMS card, Save / Save and accept, Reject (pending), Delete + confirm (accepted), validation alerts. (§12.5)
- **Done when:** a transaction can be added, edited, re-categorised (merchant remembered) and deleted.

## F8. Transactions tab and Overview (without SMS)
- [x] **Transactions tab**: Add transaction, MonthSwitcher, accepted grouped by day, empty state. (§12.3)
- [x] Summary (pure): per currency in/out/refund/net and category spending; refunds lower spending. (§11.1)
- [x] Daily spending (pure): per-day debits, elapsed-days average, peak; month vs last-30-days ranges. (§11.2)
- [x] Donut (pure part): `buildSlices` (max 5, top 4 + "Other (n)"), `layoutArcs` with 2 px gaps. (§11.3)
- [x] **Spending donut** composable (Canvas): centre text, tap slice/legend to select, legend with amount and %, accessibility. (§12.13)
- [x] **Spent per day** bar chart (Canvas): average/selected header, bars, tap to select, 4 ticks. (§12.13)
- [x] **Overview tab**: Month / All time chips, MonthSwitcher, pending banner + Review now, empty state with Import SMS / Add manually, per-currency summary cards (in, out, net, refunds note, donut, daily chart), Recent (5) + See all. Leave a slot for the ScanCard (F17). (§12.1)
- *Port tests:* `logic.test.ts` › summarize, refunds, spending donut, dailySpending.
- **Done when:** with only manual entries, Overview and Transactions show correct per-currency totals and both charts, AED and INR never mixed. *(The app is now a usable manual expense tracker.)*

## F9. SMS engine: extraction (pure)
- [x] Types `RawSms`, `ParsedSms`. (§6.1)
- [x] `findAmountCandidates` (with currency + bare decimals, context, looksLikeBalance) and `findDigitsCandidates`. (§6.2, §6.3)
- [x] Generic extractor: amount, `findDirection`, `findMerchant` + `cleanMerchant`, `findAccount`, `findDate`, `smsSnippet`, `incomeCategory`, `incomeLabel`, `extract`. (§6.4)
- [x] Bank profiles (HDFC, ICICI, SBI, Axis, Kotak), `profileForSender`, `parseSms` chain. (§6.5)
- [x] `skipReason` filters and `PERSONAL_SENDER`. (§6.5)
- [x] Regex dialect checks: case-insensitive, NBSP/Unicode spaces, `Locale.ROOT`. (§13 Q9)
- [x] Switch the row title to use `smsSnippet` (F7 follow-up).
- *Port tests:* `parse.test.ts` (all), `candidates.test.ts` › findAmountCandidates, findDigitsCandidates.
- **Done when:** every ported parse/candidate test passes.

## F10. SMS engine: kinds and custom formats (pure)
- [x] `senderKey`, `isPersonalSender`, `templateKey` (8 words, caps masking with keep-list). (§6.6)
- [x] `SmsRule`, `ruleFor`, `applyRule` (by amount position). (§6.6)
- [x] Kind labels, `suggestKind`, `groupMessages` (by sender, then kind, biggest first, digits). (§6.7)
- [x] Custom parser: field marks, `buildPattern` (generalize literals, captures, 4 error texts), `applyCustomParser` (sender filter, anchored, case-insensitive + dotall, last-4 account). (§6.8)
- *Port tests:* `templates.test.ts` › senderKey/templateKey, suggestKind, groupMessages, applyRule; `custom-parser.test.ts` (all); `candidates.test.ts` › SMS configuration flow.
- **Done when:** all ported tests pass, including the regex-escaping case.

## F11. Duplicate detection (pure)
- [x] `findDuplicate` (15 min, same amount/currency/direction, compatible accounts, closest wins) and `DedupeIndex` buckets. (§10.4)
- *Port tests:* `logic.test.ts` › findDuplicate.
- **Done when:** tests pass.

## F12. Import pipeline
- [x] `smsHash`, `matchAccount` (digits first, never guess unknown digits, single-account-in-currency fallback). (§7.2)
- [x] `processSms`: rule → custom parsers → unparsed/ignored; occurredAt same-day rule; account resolution with fixed account; duplicate; merchant/income label; category/income category. (§7.2)
- [x] Ingest context loader (rules, parsers, accounts, merchant rules, dedupe window ±1 day). (§7.1)
- [x] `importBatch`: chunks of 200, progress, auto-accept except duplicates, in-batch dedupe, one transaction per chunk, `INSERT OR IGNORE`, unread insert/resolve, ImportSummary counting. (§7.3)
- [x] `importSms`, `recheckUnread`, `clearSmsTransactions`, `countSmsTransactions`, unread list/dismiss; custom parser list/create/delete; SMS rules list (template key recomputed from sample)/replace for senders/delete. (§7.4, §4.1)
- *Port tests:* `ingest.test.ts` (all), `real-samples.test.ts` (all), `repositories.test.ts` › imports nothing until configured, imports configured kinds + duplicates + never re-imports, setup imports accepted but duplicates pending, approve all with/without duplicates, clears SMS but keeps manual, learns categories on accept, keeps unknown kinds and re-reads after a format, **5,000 SMS < 15 s**.
- **Done when:** all ported pipeline tests pass on a real SQLite (Robolectric or instrumented).

## F13. Paste an SMS
- [ ] **Paste an SMS screen**: intro, 3 example chips, Sender/Message fields, Read this SMS, four result cards (added to review / ignored / already imported / couldn't read) with Go to review and Set up this SMS format. (§12.10)
- [ ] Wire Overview's empty-state "Import SMS" and the More row to it.
- [ ] Finish F6's detected-cards test now that SMS transactions exist.
- **Done when:** pasting a message for a configured kind (created in a test DB) lands in Review; others show the right reason.

## F14. Review queue
- [ ] **Review tab**: empty state, header + Approve all (N) with the two confirm variants, virtualized list. (§12.2)
- [ ] Review card: icon, title, pill, amount, meta, unknown-card banner + Add card (prefilled args), duplicate warning, Show/Hide SMS, Reject / Edit / Accept. (§12.2)
- [ ] Review tab badge = pending count; Overview pending banner. (§12, §12.1)
- **Done when:** accept, reject, edit-then-accept and approve-all work, learning categories, and the badge updates live.

## F15. Native SMS inbox reader
- [ ] `READ_SMS` in the manifest; runtime request with the rationale texts; denied handling. (§2.1)
- [ ] `hasPermission`, `listSenders(sinceMs)` (aggregated natively, money regex), `readInbox(afterMs, limit, senders?)` ascending, paging helper `readMessages` (500 per page, progress). (§7.6)
- [ ] Decide paging edge (Q7).
- **Done when:** on a real phone, senders and messages for a period are listed correctly and nothing runs without permission.

## F16. SMS setup wizard
- [ ] Setup state (pure): `PERIODS`, `periodStart`, `mergeSenders` (drop personal, merge by key, suggested rule, sort), `kindId`, `initialChoices`, `collectDigits`, `kindsWithoutDigits`, `cardChoiceComplete`, `buildRules`, `countIncluded`. (§12.9)
- [ ] Step 1 Period (permission, counting). Step 2 Senders (with amounts / collapsible others, bank? badge, Next count). Step 3 Kinds (main vs other kinds, tick only with amount, amount picker with balance hint, direction and currency chips). Step 4 Cards (per number: existing / + New card form / Skip; no-digit kinds fixed account; continue rule). Step 5 Confirm (counts, earlier SMS deletion warning). Step 6 Importing (exact order: wait scan → cards/hints → clear SMS → replace rules → import auto-accept with progress → scan markers). Step 7 Done (counts, Go to Overview). Busy and error cards. (§12.9)
- *Port tests:* `templates.test.ts` › setup state (both cases).
- **Done when:** on a real phone, setup with real bank SMS imports the expected transactions into totals, duplicates into Review, and creates the cards.

## F17. Automatic inbox scan and ScanCard
- [ ] `isSmsConfigured`, `getLastScanRunAt`, `scanInbox` (single shared run, from `lastSmsScanAt` or now − 24 h, pages of 500 from all senders, save marker per page, progress, `lastSmsScanRunAt`), `waitForScan`, `markScannedUpTo`. (§7.5)
- [ ] Trigger on app start and on every return to foreground, only with permission; log errors. (§7.5)
- [ ] **ScanCard** on Overview and More: not-configured variant → Set up bank SMS; configured variant with last-checked text, Check now with progress summary and error, Edit SMS setup. (§12.12)
- **Done when:** a new bank SMS appears in Review after reopening the app, without duplicates across repeated scans.

## F18. SMS configuration (custom formats)
- [ ] **SMS configuration screen**: Set up a new SMS format, unread messages (newest 20, Set up this format / Ignore), Your formats (Delete + confirm), Built in list (per Q1 decision). (§12.11)
- [ ] **Set up SMS format screen**: sample field (reset on edit), 1 amount picker, 2 direction + currency, 3 card digits, 4 merchant/date token tapping with colours, 5 sender filter + name, live errors / read-back failure / preview, Save (name fallback) → recheck unread → alert → back. (§12.11)
- **Done when:** an unread message can be turned into a format, and similar unread messages move to Review.

## F19. More tab and Start over
- [ ] **More tab**: ScanCard, four rows with subtitles, Delete all data and start over (confirm texts, wait scan → reset → open setup), privacy footer. (§12.4)
- **Done when:** start over leaves a fresh database and opens setup.

## F20. Release
- [ ] If D1 = yes: install the native build over the Expo build on a test phone and confirm transactions, cards, rules, merchants and the last-scan marker survive and nothing re-imports.
- [ ] Full regression pass against [§12](FSD.md#12-screens) in light and dark, small and large screens, TalkBack labels on charts.
- [ ] Performance check: setup import of a year of SMS, 5,000 SMS batch, smooth scrolling in Review.
- [ ] Release APK, replace `apk/Zeropense.apk`, update README (build steps, stack, checks).
- **Done when:** the release APK passes the regression pass on a real phone.

---

## Coverage check: every existing file → TODO item

Every source file in the Expo repo is listed once. Nothing in `src/`, `modules/`, `plugins/` or the config is left
unmapped.

### Routes / screens (`src/app/`)
| File | What it is | TODO |
|---|---|---|
| `src/app/_layout.tsx` | Root stack, DB provider + migrate, theme | F2, F4 |
| `src/app/(tabs)/_layout.tsx` | Bottom tabs, Review badge, auto scan hook | F2, F14, F17 |
| `src/app/(tabs)/index.tsx` | Overview tab | F8 (+ ScanCard F17, banner F14) |
| `src/app/(tabs)/review.tsx` | Review tab + Review card | F14 |
| `src/app/(tabs)/transactions.tsx` | Transactions tab | F8 |
| `src/app/(tabs)/more.tsx` | More tab + reset | F19 |
| `src/app/transaction/[id].tsx` | Add/edit transaction | F7 |
| `src/app/accounts/index.tsx` | Cards & accounts | F6 |
| `src/app/accounts/new.tsx` | Add card or account | F6 |
| `src/app/categories.tsx` | Categories & remembered merchants | F5 |
| `src/app/import-sms.tsx` | Paste an SMS | F13 |
| `src/app/sms-setup.tsx` | SMS setup wizard | F16 |
| `src/app/parsers/index.tsx` | SMS configuration list | F18 |
| `src/app/parsers/new.tsx` | Set up SMS format builder | F18 |

### Feature modules (`src/features/`)
| File | TODO |
|---|---|
| `accounts/repository.ts` | F6 |
| `categories/appearance.ts` | F5 |
| `categories/categorizer.ts` | F5 |
| `categories/category-icon.tsx` | F5 |
| `categories/repository.ts` | F5 |
| `dashboard/summary.ts` | F8 |
| `dashboard/daily.ts` | F8 |
| `dashboard/daily-chart.tsx` | F8 |
| `dashboard/spending-donut.tsx` | F8 |
| `transactions/repository.ts` | F7 |
| `transactions/components.tsx` (lookups, title, row) | F7 (snippet title in F9) |
| `transactions/dedupe.ts` | F11 |
| `sms/types.ts` | F9 |
| `sms/candidates.ts` | F9 |
| `sms/extract.ts` | F9 |
| `sms/banks.ts` | F9 |
| `sms/parse.ts` | F9 |
| `sms/filters.ts` | F9 |
| `sms/templates.ts` | F10 |
| `sms/custom-parser.ts` | F10 |
| `sms/ingest.ts` | F12 |
| `sms/repository.ts` (parsers CRUD, importBatch, unread, clear) | F12 |
| `sms/rules-repository.ts` | F12 |
| `sms/inbox.ts` (permission, read, scan) | F15, F17 |
| `sms/scan-card.tsx` (ScanCard, auto-scan hook) | F17 |
| `sms/setup/state.ts` | F16 |
| `sms/setup/steps.tsx` | F16 |

### Data, helpers, UI, theme
| File | TODO |
|---|---|
| `src/db/migrations.ts` | F4 |
| `src/db/live.ts` (notify + useDbQuery) | F4 (replaced by Flow) |
| `src/db/settings.ts` | F4 |
| `src/db/reset.ts` | F4, F19 |
| `src/lib/money.ts` | F3 |
| `src/lib/dates.ts` | F3 |
| `src/lib/id.ts` | F3 |
| `src/components/ui.tsx` | F2 |
| `src/theme/index.ts` | F2 |

### Native module, config, assets, build
| File | TODO |
|---|---|
| `modules/sms-reader/android/src/main/java/.../SmsReaderModule.kt` | F15 |
| `modules/sms-reader/android/src/main/AndroidManifest.xml` (READ_SMS) | F15 |
| `modules/sms-reader/src/*.ts`, `index.ts`, `expo-module.config.json`, `android/build.gradle` | F15 (bridge code not needed natively) |
| `app.json` (name, package, icon, splash, orientation, theme, build properties) | F1 |
| `plugins/with-gradle-speed.js` | F1 |
| `assets/images/*` | F1 |
| `package.json` scripts (test, lint) | F1 |
| `apk/Zeropense.apk`, `README.md` | F20 |
| `CLAUDE.md`, `AGENTS.md` | F20 (rewrite for the native project) |
| `eslint.config.js`, `tsconfig.json`, `.vscode/*` | F1 (replaced by Gradle lint/ktlint) |

### Tests (`__tests__/`) → TODO
| Suite | Test blocks | TODO |
|---|---|---|
| `features/__tests__/logic.test.ts` › money, parseSmsDate | 4 | F3 |
| › categorizer | 2 | F5 |
| › findDuplicate | 2 | F11 |
| › summarize, refunds, spending donut, dailySpending | 6 | F8 |
| `sms/__tests__/parse.test.ts` › parseSms, skipReason, merchant names | 11 | F9 |
| `sms/__tests__/candidates.test.ts` › findAmountCandidates, findDigitsCandidates | 4 | F9 |
| › SMS configuration flow | 1 | F10 |
| `sms/__tests__/custom-parser.test.ts` | 5 | F10 |
| `sms/__tests__/templates.test.ts` › senderKey/templateKey, suggestKind, groupMessages, applyRule | 7 | F10 |
| › setup state | 2 | F16 |
| `sms/__tests__/ingest.test.ts` › processSms, matchAccount | 11 | F12 |
| `sms/__tests__/real-samples.test.ts` | 7 | F12 |
| `db/__tests__/repositories.test.ts` › migrates idempotently, resets | 2 | F4 |
| › detects unknown cards and links past transactions | 1 | F6 / F13 |
| › nothing until configured, configured kinds + duplicates, setup accept, approve all, clear SMS, learning, unread recheck, 5,000 SMS | 8 | F12 |

Total: 73 test blocks (`it` and `it.each`) across 8 files. Port every `expect`, including each `it.each` row.

### Business rules → TODO (spot check that nothing is orphaned)
| Rule | FSD | TODO |
|---|---|---|
| AED and INR never summed | §11.1 | F8 |
| Only configured SMS imported | §7.2 | F12 |
| Refunds lower spending, never learned | §8.1, §8.4, §11.1 | F5, F7, F8 |
| Unknown card digits never guessed | §7.2 | F12 |
| Duplicates flagged, never deleted, never auto-accepted | §10.4, §7.3 | F11, F12 |
| Rejected SMS never re-imported (hash kept) | §10.1 | F7, F12 |
| Setup deletes earlier SMS transactions, keeps manual | §7.4, §12.9 | F12, F16 |
| Scan never reads the whole inbox | §7.5 | F17 |
| Category learning on save / accept / approve all | §8.4 | F5, F7, F14 |
| One account = one card number | §9 | F6, F16 |
| Edited date keeps time; new date = 12:00 | §12.5 | F7 |
| Reset keeps phone SMS, re-seeds | §4.4 | F4, F19 |
