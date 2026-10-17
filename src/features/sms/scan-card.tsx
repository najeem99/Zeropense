import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useEffect, useState } from 'react';
import { AppState } from 'react-native';

import { Body, Button, Card, Label } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { formatDateTime } from '@/lib/dates';

import { getLastScanRunAt, hasInboxPermission, inboxAvailable, isSmsConfigured, scanInbox } from './inbox';
import type { ImportSummary } from './repository';

function describe(s: ImportSummary): string {
  const parts = [`Checked ${s.read} new SMS`, `${s.added} transaction${s.added === 1 ? '' : 's'} to review`];
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
  const configured = useDbQuery(isSmsConfigured);
  const [progress, setProgress] = useState<ImportSummary | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
  if (configured === undefined) return null;

  if (!configured) {
    return (
      <Card>
        <Label>Set up your bank SMS</Label>
        <Body muted style={{ fontSize: 14 }}>
          Pick how far back to read, which senders are your banks, which of their messages are transactions, and which
          card each one is. Only those messages are imported. Everything stays on this phone.
        </Body>
        <Button title="Set up bank SMS" onPress={() => router.push('/sms-setup')} />
      </Card>
    );
  }

  const scan = async () => {
    setScanning(true);
    setError(null);
    try {
      setProgress(await scanInbox(db, setProgress));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  };

  return (
    <Card>
      <Label>Bank SMS</Label>
      <Body muted style={{ fontSize: 14 }}>
        {scanning
          ? `Checking new SMS…${progress ? ` ${describe(progress)}` : ''}`
          : progress
            ? describe(progress)
            : lastRun
              ? `Last checked ${formatDateTime(lastRun)}. New bank SMS of the kinds you set up go to Review.`
              : 'New bank SMS of the kinds you set up go to Review.'}
      </Body>
      {error ? <Body style={{ fontSize: 14 }}>Check failed: {error}</Body> : null}
      <Button title={scanning ? 'Checking…' : 'Check now'} variant="secondary" disabled={scanning} onPress={scan} />
      <Button title="Edit SMS setup" variant="ghost" onPress={() => router.push('/sms-setup')} />
    </Card>
  );
}
