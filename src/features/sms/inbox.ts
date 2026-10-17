import type { SQLiteDatabase } from 'expo-sqlite';
import { PermissionsAndroid, Platform } from 'react-native';

import SmsReader, { type InboxSender } from '../../../modules/sms-reader';

import { notifyDbChanged } from '@/db/live';
import { getSetting, setSetting } from '@/db/settings';

import { addSummary, emptySummary, importBatch, listCustomParsers, type ImportSummary } from './repository';
import { listSmsRules } from './rules-repository';
import type { RawSms } from './types';

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

/** True once SMS setup (or a custom format) has configured something to import. */
export async function isSmsConfigured(db: SQLiteDatabase): Promise<boolean> {
  const [rules, parsers] = await Promise.all([listSmsRules(db), listCustomParsers(db)]);
  return rules.length > 0 || parsers.length > 0;
}

/** Senders with message counts since `sinceMs`, counted on the native side. */
export async function listInboxSenders(sinceMs: number): Promise<InboxSender[]> {
  if (!hasInboxPermission()) throw new Error('SMS permission not granted');
  return SmsReader!.listSenders(sinceMs);
}

/** Reads messages from the given addresses since `sinceMs`, oldest first, page by page. */
export async function readMessages(
  addresses: string[] | null,
  sinceMs: number,
  onProgress?: (read: number) => void,
): Promise<RawSms[]> {
  if (!hasInboxPermission()) throw new Error('SMS permission not granted');
  const out: RawSms[] = [];
  let after = sinceMs;
  for (;;) {
    const page = await SmsReader!.readInbox(after, PAGE_SIZE, addresses);
    for (const m of page) out.push({ sender: m.sender, body: m.body, receivedAt: m.date });
    onProgress?.(out.length);
    if (page.length < PAGE_SIZE) break;
    after = page[page.length - 1].date;
  }
  return out;
}

/** After SMS setup, ongoing scans start from here instead of re-reading the inbox. */
export async function markScannedUpTo(db: SQLiteDatabase, ts: number): Promise<void> {
  await setSetting(db, 'lastSmsScanAt', String(ts));
  await setSetting(db, 'lastSmsScanRunAt', String(Date.now()));
  notifyDbChanged();
}

let running: Promise<ImportSummary> | null = null;

/**
 * Imports SMS received since the last scan. Only configured kinds become transactions, and
 * they wait in Review. Does nothing until SMS setup has run. Concurrent calls share one run.
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
  const total = emptySummary();
  if (!hasInboxPermission() || !(await isSmsConfigured(db))) return total;
  const lastAt = await getSetting(db, 'lastSmsScanAt');
  // Never fall back to the whole inbox: without a setup timestamp, only look at today onwards.
  let after = lastAt ? Number(lastAt) : Date.now() - 24 * 60 * 60 * 1000;

  for (;;) {
    const page = await SmsReader!.readInbox(after, PAGE_SIZE, null);
    if (page.length === 0) break;
    const s = await importBatch(
      db,
      page.map((m) => ({ sender: m.sender, body: m.body, receivedAt: m.date })),
    );
    addSummary(total, s);
    after = page[page.length - 1].date;
    await setSetting(db, 'lastSmsScanAt', String(after));
    onProgress?.({ ...total });
    if (page.length < PAGE_SIZE) break;
  }
  await setSetting(db, 'lastSmsScanRunAt', String(Date.now()));
  notifyDbChanged();
  return total;
}

/** For the setup: waits for any scan in progress so it doesn't race the fresh import. */
export async function waitForScan(): Promise<void> {
  if (running) await running.catch(() => {});
}
