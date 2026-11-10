package com.najeemfasil.expense.data.db

/**
 * The schema, copied from the Expo app's `src/db/migrations.ts` so a database created by that
 * app opens unchanged. Add a new step and bump [LATEST_VERSION] for schema changes; never edit
 * a step once shipped.
 */
private val DEFAULT_CATEGORIES = listOf(
    Triple("groceries", "Groceries", "expense"),
    Triple("restaurant", "Restaurant", "expense"),
    Triple("transport", "Transport", "expense"),
    Triple("fuel", "Fuel", "expense"),
    Triple("shopping", "Shopping", "expense"),
    Triple("bills", "Bills & Utilities", "expense"),
    Triple("rent", "Rent", "expense"),
    Triple("health", "Health", "expense"),
    Triple("entertainment", "Entertainment", "expense"),
    Triple("subscriptions", "Subscriptions", "expense"),
    Triple("travel", "Travel", "expense"),
    Triple("transfers", "Transfers", "expense"),
    Triple("other", "Other", "expense"),
    Triple("salary", "Salary", "income"),
    Triple("refund", "Refund", "income"),
    Triple("other-income", "Other income", "income")
)

private const val V1 = """
CREATE TABLE accounts (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  currency TEXT NOT NULL,
  sms_hint TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE categories (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  sort INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE transactions (
  id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL,            -- pending | accepted | rejected
  source TEXT NOT NULL,            -- sms | manual
  amount_minor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  direction TEXT NOT NULL,         -- debit | credit
  merchant TEXT,
  note TEXT,
  occurred_at INTEGER NOT NULL,
  account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
  category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
  duplicate_of_id TEXT,
  sms_hash TEXT UNIQUE,
  sms_sender TEXT,
  sms_body TEXT,
  parser_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX transactions_status_time ON transactions (status, occurred_at);
CREATE TABLE merchant_rules (
  merchant_key TEXT PRIMARY KEY NOT NULL,
  category_id TEXT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  updated_at INTEGER NOT NULL
);
CREATE TABLE custom_parsers (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  sender_contains TEXT,
  pattern TEXT NOT NULL,
  fields TEXT NOT NULL,            -- JSON array of field names, in capture-group order
  currency TEXT NOT NULL,
  direction TEXT NOT NULL,
  sample TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
"""

private const val V2 = """
ALTER TABLE accounts ADD COLUMN kind TEXT NOT NULL DEFAULT 'bank';  -- bank | credit_card | debit_card
ALTER TABLE transactions ADD COLUMN account_hint TEXT;               -- last digits seen in the SMS
CREATE INDEX transactions_account_hint ON transactions (account_hint);
CREATE TABLE unread_sms (                                            -- financial-looking SMS no parser could read
  sms_hash TEXT PRIMARY KEY NOT NULL,
  sender TEXT NOT NULL,
  body TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
CREATE TABLE settings (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
);
"""

private const val V3 = """
CREATE TABLE sms_rules (                       -- message kinds configured in SMS setup
  id TEXT PRIMARY KEY NOT NULL,
  sender_key TEXT NOT NULL,                    -- sender without operator prefix/suffix, e.g. HDFCBK
  template_key TEXT NOT NULL,                  -- opening words with numbers masked
  action TEXT NOT NULL,                        -- import | ignore
  amount_index INTEGER NOT NULL,
  direction TEXT NOT NULL,
  currency TEXT NOT NULL,
  fixed_account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
  sample TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (sender_key, template_key)
);
"""

/** Income category for money other people send. */
private const val V4 = """
INSERT OR IGNORE INTO categories (id, name, kind, sort) VALUES ('transfer-in', 'Money received', 'income', 100);
"""

/** Refunds are never a shop's category: drop any that were learned before that rule. */
private const val V5 = """
DELETE FROM merchant_rules WHERE category_id = 'refund';
"""

/** Money sent home (e.g. Aspora to India); Aspora is categorised automatically. */
private const val V6 = """
INSERT OR IGNORE INTO categories (id, name, kind, sort) VALUES ('remittance', 'Remittance', 'expense', 11);
INSERT OR IGNORE INTO merchant_rules (merchant_key, category_id, updated_at) VALUES ('ASPORA', 'remittance', 0);
"""

const val LATEST_VERSION = 6

fun userVersion(db: Conn): Int = db.first("PRAGMA user_version") { it.int(0) } ?: 0

/** Runs on every app start. Brings a new or older database up to [upTo]. */
fun migrate(db: Conn, upTo: Int = LATEST_VERSION) {
    db.query("PRAGMA journal_mode = WAL") { }
    db.run("PRAGMA foreign_keys = ON")
    val version = userVersion(db)
    if (version >= upTo) return

    db.transaction {
        if (version < 1 && upTo >= 1) {
            db.script(V1)
            DEFAULT_CATEGORIES.forEachIndexed { sort, (id, name, kind) ->
                db.run("INSERT INTO categories (id, name, kind, sort) VALUES (?, ?, ?, ?)", id, name, kind, sort)
            }
        }
        if (version < 2 && upTo >= 2) db.script(V2)
        if (version < 3 && upTo >= 3) db.script(V3)
        if (version < 4 && upTo >= 4) db.script(V4)
        if (version < 5 && upTo >= 5) db.script(V5)
        if (version < 6 && upTo >= 6) db.script(V6)
    }
    db.run("PRAGMA user_version = $upTo")
}

/**
 * Deletes everything (transactions, cards, categories, rules, SMS setup, settings) and
 * recreates the empty database, as on a fresh install. SMS stay in the phone's inbox.
 */
fun resetAllData(db: Conn) {
    val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") {
        it.text(0)
    }
    // foreign_keys can't change inside a transaction, so switch it off first.
    db.run("PRAGMA foreign_keys = OFF")
    db.transaction {
        for (t in tables) db.run("DROP TABLE IF EXISTS \"$t\"")
    }
    db.run("PRAGMA user_version = 0")
    migrate(db)
}
