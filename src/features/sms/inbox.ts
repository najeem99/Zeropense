import type { SQLiteDatabase } from 'expo-sqlite';
import { PermissionsAndroid, Platform } from 'react-native';

import SmsReader from '../../../modules/sms-reader';

import { notifyDbChanged } from '@/db/live';
import { getSetting, setSetting } from '@/db/settings';

import { importBatch, type ImportSummary } from './repository';

const PAGE_SIZE = 500;

/** False in Expo Go, iOS and web: the native reader only exists in the Android dev/release build. */
export const inboxAvailable = Platform.OS === 'android' && SmsReader !== null;

export function hasInboxPermission(): boolean {
  return inboxAvailable && SmsReader!.hasPermission();
}

export async function requestInboxPermission(): Promise<boolean> {
  if (!inboxAvailable) return false;
  const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.READ_SMS, {
    title: 'Read bank SMS',
    message:
      'Zeropense reads your bank SMS to find transactions. Messages are processed on this phone and never uploaded.',
    buttonPositive: 'Allow',
    buttonNegative: 'Not now',
  });
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

/** When a scan last finished, for display. */
export async function getLastScanRunAt(db: SQLiteDatabase): Promise<number | null> {
  const v = await getSetting(db, 'lastSmsScanRunAt');
  return v ? Number(v) : null;
}

let running: Promise<ImportSummary> | null = null;

export type HistoryImportState = 'running' | 'done' | 'declined' | null;

/** null = the user hasn't been asked yet whether to import their old SMS. */
export async function getHistoryImportState(db: SQLiteDatabase): Promise<HistoryImportState> {
  return (await getSetting(db, 'smsHistoryImport')) as HistoryImportState;
}

export async function declineHistoryImport(db: SQLiteDatabase): Promise<void> {
  await setSetting(db, 'smsHistoryImport', 'declined');
  notifyDbChanged();
}

/**
 * Imports the whole inbox from the beginning, straight into totals (possible duplicates
 * still go to Review). Picks up where it left off if the app was closed mid-import.
 */
export async function importHistory(db: SQLiteDatabase, onProgress?: (s: ImportSummary) => void): Promise<ImportSummary> {
  // A normal scan already in flight would otherwise be shared and run without auto-accept.
  if (running) await running.catch(() => {});
  if ((await getHistoryImportState(db)) !== 'running') {
    await setSetting(db, 'smsHistoryImport', 'running');
    await setSetting(db, 'lastSmsScanAt', '0');
    notifyDbChanged();
  }
  return scanInbox(db, onProgress);
}

/**
 * Imports every inbox SMS received after the last scan (the whole inbox the first time).
 * While a history import is running, transactions are accepted directly. Concurrent calls share one run.
 */
export function scanInbox(db: SQLiteDatabase, onProgress?: (s: ImportSummary) => void): Promise<ImportSummary> {
  if (!running) {
    running = doScan(db, onProgress).finally(() => {
      running = null;
    });
  }
  return running;
}

async function doScan(db: SQLiteDatabase, onProgress?: (s: ImportSummary) => void): Promise<ImportSummary> {
  if (!hasInboxPermission()) throw new Error('SMS permission not granted');
  const autoAccept = (await getHistoryImportState(db)) === 'running';
  const total: ImportSummary = { read: 0, added: 0, accepted: 0, duplicates: 0, skipped: 0, unread: 0 };
  let after = Number((await getSetting(db, 'lastSmsScanAt')) ?? 0);

  for (;;) {
    const page = await SmsReader!.readInbox(after, PAGE_SIZE);
    if (page.length === 0) break;
    const s = await importBatch(
      db,
      page.map((m) => ({ sender: m.sender, body: m.body, receivedAt: m.date })),
      { autoAccept },
    );
    total.read += s.read;
    total.added += s.added;
    total.accepted += s.accepted;
    total.duplicates += s.duplicates;
    total.skipped += s.skipped;
    total.unread += s.unread;
    after = page[page.length - 1].date;
    // Saved per page so an interrupted first scan resumes instead of restarting.
    await setSetting(db, 'lastSmsScanAt', String(after));
    onProgress?.({ ...total });
    if (page.length < PAGE_SIZE) break;
  }
  if (autoAccept) await setSetting(db, 'smsHistoryImport', 'done');
  await setSetting(db, 'lastSmsScanRunAt', String(Date.now()));
  notifyDbChanged();
  return total;
}
