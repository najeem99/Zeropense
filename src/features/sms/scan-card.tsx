import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useEffect, useRef, useState } from 'react';
import { Alert, AppState } from 'react-native';

import { Body, Button, Card, Label } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { formatDateTime } from '@/lib/dates';

import {
  declineHistoryImport,
  getHistoryImportState,
  getLastScanRunAt,
  hasInboxPermission,
  importHistory,
  inboxAvailable,
  requestInboxPermission,
  scanInbox,
} from './inbox';
import type { ImportSummary } from './repository';

function describe(s: ImportSummary): string {
  const parts = [`Read ${s.read} SMS`];
  if (s.accepted) {
    parts.push(`${s.accepted} added to your totals`);
    const toReview = s.added - s.accepted;
    if (toReview) parts.push(`${toReview} to review`);
  } else {
    parts.push(`${s.added} new transaction${s.added === 1 ? '' : 's'}`);
  }
  if (s.duplicates) parts.push(`${s.duplicates} possible duplicate${s.duplicates === 1 ? '' : 's'}`);
  if (s.unread) parts.push(`${s.unread} not understood`);
  return parts.join(' · ');
}

/** Scans new SMS when the app opens or comes back to the foreground. Mount once. */
export function useAutoInboxScan() {
  const db = useSQLiteContext();
  useEffect(() => {
    const run = () => {
      if (hasInboxPermission()) scanInbox(db).catch((e) => console.warn('SMS scan failed', e));
    };
    run();
    const sub = AppState.addEventListener('change', (state) => state === 'active' && run());
    return () => sub.remove();
  }, [db]);
}

export function ScanCard() {
  const db = useSQLiteContext();
  const lastRun = useDbQuery(getLastScanRunAt);
  const history = useDbQuery(getHistoryImportState);
  const [granted, setGranted] = useState(hasInboxPermission);
  const [progress, setProgress] = useState<ImportSummary | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const asked = useRef(false);

  const run = async (job: typeof scanInbox) => {
    setScanning(true);
    setError(null);
    try {
      setProgress(await job(db, setProgress));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  };
  const scan = () => run(scanInbox);

  /** Asks for SMS access if needed, then imports the whole inbox. */
  const startHistoryImport = async () => {
    if (!hasInboxPermission()) {
      if (!(await requestInboxPermission())) return false;
      setGranted(true);
    }
    await run(importHistory);
    return true;
  };

  // First launch: ask once whether to read the SMS already on the phone.
  useEffect(() => {
    if (!inboxAvailable || history !== null || asked.current) return;
    asked.current = true;
    Alert.alert(
      'Import your old bank SMS?',
      'Zeropense can read the bank SMS already on this phone and add those transactions to your dashboard. ' +
        'Messages are read on this phone only and never uploaded. Possible duplicates go to Review.',
      [
        { text: 'Not now', style: 'cancel', onPress: () => declineHistoryImport(db) },
        {
          text: 'Import',
          onPress: async () => {
            if (!(await startHistoryImport())) await declineHistoryImport(db);
          },
        },
      ],
      { cancelable: false },
    );
    // startHistoryImport only closes over db and state setters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [db, history]);

  if (!inboxAvailable) {
    return (
      <Card>
        <Label>Bank SMS</Label>
        <Body muted style={{ fontSize: 14 }}>
          Reading your SMS inbox needs the Android app build (it isn&apos;t available in Expo Go). You can still paste
          messages.
        </Body>
        <Button title="Paste an SMS" variant="secondary" onPress={() => router.push('/import-sms')} />
      </Card>
    );
  }

  if (!granted) {
    return (
      <Card>
        <Label>Read your bank SMS</Label>
        <Body muted style={{ fontSize: 14 }}>
          Allow SMS access to add every transaction already in your inbox to your dashboard. Messages are read on this
          phone only.
        </Body>
        <Button title="Allow and import old SMS" onPress={startHistoryImport} />
      </Card>
    );
  }

  return (
    <Card>
      <Label>Bank SMS</Label>
      <Body muted style={{ fontSize: 14 }}>
        {scanning
          ? `${history === 'running' ? 'Importing your old SMS' : 'Scanning your inbox'}…${progress ? ` ${describe(progress)}` : ''}`
          : progress
            ? describe(progress)
            : lastRun
              ? `Last scanned ${formatDateTime(lastRun)}. New SMS are picked up when you open the app.`
              : 'Not scanned yet.'}
      </Body>
      {error ? <Body style={{ fontSize: 14 }}>Scan failed: {error}</Body> : null}
      {history !== 'done' && !scanning ? (
        <Button
          title={history === 'running' ? 'Resume importing old SMS' : 'Import old SMS'}
          onPress={() => run(importHistory)}
        />
      ) : null}
      <Button title={scanning ? 'Scanning…' : 'Scan now'} variant="secondary" disabled={scanning} onPress={scan} />
    </Card>
  );
}
