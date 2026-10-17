import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from './live';
import { migrate } from './migrations';

/**
 * Deletes everything (transactions, cards, categories, rules, SMS setup, settings) and
 * recreates the empty database, as on a fresh install. SMS stay in the phone's inbox.
 */
export async function resetAllData(db: SQLiteDatabase): Promise<void> {
  const tables = await db.getAllAsync<{ name: string }>(
    `SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`,
  );
  // foreign_keys can't change inside a transaction, so switch it off first.
  await db.execAsync('PRAGMA foreign_keys = OFF;');
  await db.withTransactionAsync(async () => {
    for (const t of tables) await db.execAsync(`DROP TABLE IF EXISTS "${t.name}"`);
  });
  await db.execAsync('PRAGMA user_version = 0;');
  await migrate(db);
  notifyDbChanged();
}
