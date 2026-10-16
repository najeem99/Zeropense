import type { SQLiteDatabase } from 'expo-sqlite';

const DEFAULT_CATEGORIES: [id: string, name: string, kind: 'expense' | 'income'][] = [
  ['groceries', 'Groceries', 'expense'],
  ['restaurant', 'Restaurant', 'expense'],
  ['transport', 'Transport', 'expense'],
  ['fuel', 'Fuel', 'expense'],
  ['shopping', 'Shopping', 'expense'],
  ['bills', 'Bills & Utilities', 'expense'],
  ['rent', 'Rent', 'expense'],
  ['health', 'Health', 'expense'],
  ['entertainment', 'Entertainment', 'expense'],
  ['subscriptions', 'Subscriptions', 'expense'],
  ['travel', 'Travel', 'expense'],
  ['transfers', 'Transfers', 'expense'],
  ['other', 'Other', 'expense'],
  ['salary', 'Salary', 'income'],
  ['refund', 'Refund', 'income'],
  ['other-income', 'Other income', 'income'],
];

const V1 = `
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
`;

const V2 = `
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
`;

const LATEST = 2;

/** Runs on every app start via SQLiteProvider onInit. */
export async function migrate(db: SQLiteDatabase): Promise<void> {
  await db.execAsync('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');
  const row = await db.getFirstAsync<{ user_version: number }>('PRAGMA user_version');
  const version = row?.user_version ?? 0;
  if (version >= LATEST) return;

  await db.withTransactionAsync(async () => {
    if (version < 1) {
      await db.execAsync(V1);
      let sort = 0;
      for (const [id, name, kind] of DEFAULT_CATEGORIES) {
        await db.runAsync('INSERT INTO categories (id, name, kind, sort) VALUES (?, ?, ?, ?)', id, name, kind, sort++);
      }
    }
    if (version < 2) {
      await db.execAsync(V2);
    }
  });
  await db.execAsync(`PRAGMA user_version = ${LATEST}`);
}
