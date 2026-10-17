import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useMemo, useState } from 'react';
import { ActivityIndicator } from 'react-native';

import { Body, Button, Card, Label, Screen } from '@/components/ui';
import { notifyDbChanged, useDbQuery } from '@/db/live';
import { createAccount, listAccounts, setAccountHint } from '@/features/accounts/repository';
import {
  hasInboxPermission,
  inboxAvailable,
  listInboxSenders,
  markScannedUpTo,
  readMessages,
  requestInboxPermission,
  waitForScan,
} from '@/features/sms/inbox';
import { clearSmsTransactions, countSmsTransactions, importBatch, type ImportSummary } from '@/features/sms/repository';
import { replaceSmsRules } from '@/features/sms/rules-repository';
import {
  buildRules,
  cardChoiceComplete,
  collectDigits,
  countIncluded,
  initialChoices,
  kindsWithoutDigits,
  mergeSenders,
  periodStart,
  type CardChoice,
  type KindChoice,
  type SenderOption,
} from '@/features/sms/setup/state';
import { CardsStep, KindsStep, PeriodStep, SendersStep } from '@/features/sms/setup/steps';
import { groupMessages, type SenderGroup } from '@/features/sms/templates';
import type { RawSms } from '@/features/sms/types';

type Step = 'period' | 'senders' | 'kinds' | 'cards' | 'confirm' | 'importing' | 'done';

export default function SmsSetup() {
  const db = useSQLiteContext();
  const accounts = useDbQuery(listAccounts) ?? [];
  const oldCount = useDbQuery(countSmsTransactions);

  const [step, setStep] = useState<Step>('period');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [since, setSince] = useState(0);
  const [senders, setSenders] = useState<SenderOption[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [readAt, setReadAt] = useState(0);
  const [messages, setMessages] = useState<RawSms[]>([]);
  const [groups, setGroups] = useState<SenderGroup[]>([]);
  const [choices, setChoices] = useState<Record<string, KindChoice>>({});
  const [cardChoices, setCardChoices] = useState<Record<string, CardChoice>>({});
  const [fixedAccounts, setFixedAccounts] = useState<Record<string, string | null>>({});
  const [result, setResult] = useState<ImportSummary | null>(null);

  const digits = useMemo(() => collectDigits(groups, choices), [groups, choices]);
  const noDigitKinds = useMemo(() => kindsWithoutDigits(groups, choices), [groups, choices]);
  const includedCount = useMemo(() => countIncluded(groups, choices), [groups, choices]);

  const guard = async (label: string, job: () => Promise<void>) => {
    setBusy(label);
    setError(null);
    try {
      await job();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const pickPeriod = (months: number) =>
    guard('Counting your messages…', async () => {
      if (!hasInboxPermission() && !(await requestInboxPermission())) {
        throw new Error('SMS permission is needed to read your bank messages.');
      }
      const start = periodStart(months);
      const list = mergeSenders(await listInboxSenders(start));
      setSince(start);
      setSenders(list);
      setSelected(new Set(list.filter((s) => s.suggested).map((s) => s.key)));
      setStep('senders');
    });

  const loadKinds = () =>
    guard('Reading messages from the selected banks…', async () => {
      const addresses = senders.filter((s) => selected.has(s.key)).flatMap((s) => s.addresses);
      const started = Date.now();
      const msgs = await readMessages(addresses, since, (n) => setBusy(`Reading messages… ${n}`));
      const g = groupMessages(msgs);
      setReadAt(started);
      setMessages(msgs);
      setGroups(g);
      setChoices(initialChoices(g));
      setStep('kinds');
    });

  const goToCards = () => {
    // Pre-pick accounts that already carry a detected card number.
    const next = { ...cardChoices };
    for (const d of digits) {
      if (next[d.digits]) continue;
      const existing = accounts.find((a) => a.smsHint === d.digits);
      if (existing) next[d.digits] = { mode: 'existing', accountId: existing.id };
    }
    setCardChoices(next);
    setStep('cards');
  };

  const runImport = async () => {
    setStep('importing');
    await guard('Importing…', async () => {
      await waitForScan();
      // Cards first, so imported messages link to them by their last 4 digits.
      for (const d of digits) {
        const c = cardChoices[d.digits];
        if (c?.mode === 'new' && c.kind) {
          await createAccount(db, { name: c.name.trim(), kind: c.kind, currency: c.currency, smsHint: d.digits });
        } else if (c?.mode === 'existing') {
          await setAccountHint(db, c.accountId, d.digits);
        }
      }
      await clearSmsTransactions(db);
      await replaceSmsRules(db, groups.map((g) => g.senderKey), buildRules(groups, choices, fixedAccounts));
      const summary = await importBatch(db, messages, {
        autoAccept: true,
        onProgress: (s) => setBusy(`Importing… ${s.read} of ${messages.length} messages checked`),
      });
      await markScannedUpTo(db, readAt);
      notifyDbChanged();
      setResult(summary);
      setStep('done');
    });
  };

  if (!inboxAvailable) {
    return (
      <Screen>
        <Body>SMS setup needs the Android app build. It isn&apos;t available in Expo Go.</Body>
      </Screen>
    );
  }

  return (
    <Screen>
      {error ? (
        <Card>
          <Body>{error}</Body>
          {step === 'importing' ? <Button title="Back" variant="secondary" onPress={() => setStep('confirm')} /> : null}
        </Card>
      ) : null}

      {busy ? (
        <Card style={{ alignItems: 'center' }}>
          <ActivityIndicator />
          <Body muted>{busy}</Body>
        </Card>
      ) : step === 'period' ? (
        <PeriodStep onPick={pickPeriod} />
      ) : step === 'senders' ? (
        <SendersStep
          senders={senders}
          selected={selected}
          onToggle={(key) => {
            const next = new Set(selected);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            setSelected(next);
          }}
          onNext={loadKinds}
        />
      ) : step === 'kinds' ? (
        <KindsStep
          groups={groups}
          choices={choices}
          onChange={(id, c) => setChoices({ ...choices, [id]: c })}
          includedCount={includedCount}
          onNext={goToCards}
        />
      ) : step === 'cards' ? (
        <CardsStep
          digits={digits}
          noDigitKinds={noDigitKinds}
          accounts={accounts}
          cardChoices={cardChoices}
          fixedAccounts={fixedAccounts}
          onCard={(d, c) => setCardChoices({ ...cardChoices, [d]: c })}
          onFixed={(id, accountId) => setFixedAccounts({ ...fixedAccounts, [id]: accountId })}
          canContinue={digits.every((d) => cardChoiceComplete(cardChoices[d.digits]))}
          onNext={() => setStep('confirm')}
        />
      ) : step === 'confirm' ? (
        <Card>
          <Label>Ready to import</Label>
          <Body>
            {includedCount} transaction message{includedCount === 1 ? '' : 's'} from {groups.length} bank
            {groups.length === 1 ? '' : 's'} will be added to your totals. Possible duplicates go to Review.
          </Body>
          {oldCount ? (
            <Body>
              The {oldCount} transaction{oldCount === 1 ? '' : 's'} imported from SMS earlier will be deleted first.
              Transactions you added by hand stay.
            </Body>
          ) : null}
          <Body muted style={{ fontSize: 14 }}>
            From now on, only these kinds of messages are read. New ones wait in Review.
          </Body>
          <Button title="Import" onPress={runImport} />
          <Button title="Back" variant="ghost" onPress={() => setStep('cards')} />
        </Card>
      ) : step === 'done' && result ? (
        <Card>
          <Label>Done</Label>
          <Body>
            {result.accepted} transaction{result.accepted === 1 ? '' : 's'} added to your totals.
            {result.duplicates ? ` ${result.duplicates} possible duplicate${result.duplicates === 1 ? '' : 's'} in Review.` : ''}
          </Body>
          <Button title="Go to Overview" onPress={() => router.dismissTo('/')} />
        </Card>
      ) : null}
    </Screen>
  );
}
