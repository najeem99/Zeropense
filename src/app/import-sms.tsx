import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useState } from 'react';

import { Amount, Body, Button, Card, Chip, ChipRow, Field, Label, Screen } from '@/components/ui';
import { importSms, type ImportOutcome } from '@/features/sms/repository';
import { useColors } from '@/theme';

const EXAMPLES = [
  {
    label: 'ENBD card',
    sender: 'EmiratesNBD',
    body: 'Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00',
  },
  {
    label: 'HDFC UPI',
    sender: 'VM-HDFCBK',
    body: 'Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123456789\nNot You? Call 18002586161',
  },
  { label: 'OTP', sender: 'VM-HDFCBK', body: '482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share.' },
];

export default function ImportSms() {
  const db = useSQLiteContext();
  const c = useColors();
  const [sender, setSender] = useState('');
  const [body, setBody] = useState('');
  const [result, setResult] = useState<ImportOutcome | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    try {
      setResult(await importSms(db, { sender: sender.trim(), body: body.trim(), receivedAt: Date.now() }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen>
      <Body muted>
        Paste a bank SMS to see how it&apos;s read. On the Android build, your inbox is scanned automatically.
      </Body>
      <Label>Try an example</Label>
      <ChipRow>
        {EXAMPLES.map((e) => (
          <Chip
            key={e.label}
            label={e.label}
            onPress={() => {
              setSender(e.sender);
              setBody(e.body);
              setResult(null);
            }}
          />
        ))}
      </ChipRow>
      <Field label="Sender" value={sender} onChangeText={setSender} placeholder="e.g. VM-HDFCBK" autoCapitalize="characters" />
      <Field label="Message" value={body} onChangeText={setBody} placeholder="Paste the SMS text" multiline />
      <Button title="Read this SMS" onPress={run} disabled={!body.trim() || busy} />

      {result ? (
        <Card>
          {result.kind === 'draft' ? (
            <>
              <Label>Added to review</Label>
              <Amount minor={result.draft.amountMinor} currency={result.draft.currency} direction={result.draft.direction} style={{ fontSize: 22 }} />
              <Body>{result.draft.merchant ?? 'Merchant not found'}</Body>
              <Body muted style={{ fontSize: 13 }}>Read by: {result.draft.parserId}</Body>
              {result.draft.duplicateOfId ? (
                <Body style={{ color: c.warning }}>Flagged as a possible duplicate.</Body>
              ) : null}
              <Button title="Go to review" variant="secondary" onPress={() => router.navigate('/review')} />
            </>
          ) : result.kind === 'ignored' ? (
            <>
              <Label>Ignored</Label>
              <Body>{result.reason}. Only the kinds of bank SMS you set up are imported.</Body>
              <Button
                title="Set up this SMS format"
                variant="secondary"
                onPress={() => router.push({ pathname: '/parsers/new', params: { sample: body, sender } })}
              />
            </>
          ) : result.kind === 'already-imported' ? (
            <>
              <Label>Already imported</Label>
              <Body>This exact SMS was imported before.</Body>
            </>
          ) : (
            <>
              <Label>Couldn&apos;t read this SMS</Label>
              <Body>It&apos;s from one of your banks but doesn&apos;t match a kind you set up. You can teach the app this format.</Body>
              <Button
                title="Set up this SMS format"
                variant="secondary"
                onPress={() => router.push({ pathname: '/parsers/new', params: { sample: body, sender } })}
              />
            </>
          )}
        </Card>
      ) : null}
    </Screen>
  );
}
