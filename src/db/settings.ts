import type { SQLiteDatabase } from 'expo-sqlite';

/**
 * lastSmsScanAt: receive time of the newest imported SMS. lastSmsScanRunAt: when a scan last finished.
 */
export type SettingKey = 'lastSmsScanAt' | 'lastSmsScanRunAt';

export async function getSetting(db: SQLiteDatabase, key: SettingKey): Promise<string | null> {
  const row = await db.getFirstAsync<{ value: string }>('SELECT value FROM settings WHERE key = ?', key);
  return row?.value ?? null;
}

export async function setSetting(db: SQLiteDatabase, key: SettingKey, value: string): Promise<void> {
  await db.runAsync(
    'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value',
    key,
    value,
  );
}
