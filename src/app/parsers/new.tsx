import { router, useLocalSearchParams } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useMemo, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';

import { Body, Button, Card, Chip, ChipRow, Field, Label, Screen } from '@/components/ui';
import { findAmountCandidates, findDigitsCandidates } from '@/features/sms/candidates';
import { applyCustomParser, buildPattern, type FieldMark } from '@/features/sms/custom-parser';
import { findDirection } from '@/features/sms/extract';
import { createCustomParser, recheckUnread } from '@/features/sms/repository';
import { formatDate } from '@/lib/dates';
import { CURRENCIES, formatMoney, type Currency, type Direction } from '@/lib/money';
import { radius, spacing, useColors } from '@/theme';

type Token = { text: string; start: number; end: number };
type Range = { from: number; to: number };
type WordField = 'merchant' | 'date';

const WORD_FIELD_COLORS: Record<WordField, string> = { merchant: '#13804B', date: '#A35F00' };

/** Splits so "Rs.500.00" gives "Rs", ".", "500.00" and each piece can be tapped. */
function tokenize(text: string): Token[] {
  return [...text.matchAll(/\d[\d,]*(?:\.\d+)?|[A-Za-z]+|[^\sA-Za-z\d]+/g)].map((m) => ({
    text: m[0],
    start: m.index,
    end: m.index + m[0].length,
  }));
}

export default function SmsConfiguration() {
  const params = useLocalSearchParams<{ sample?: string; sender?: string }>();
  const db = useSQLiteContext();
  const c = useColors();

  const [sample, setSample] = useState(params.sample ?? '');
  const [sender, setSender] = useState(params.sender ?? '');
  const [name, setName] = useState('');
  const [amountPick, setAmountPick] = useState<number | null>(null);
  const [digitsPick, setDigitsPick] = useState<number | null>(null);
  const [currency, setCurrency] = useState<Currency>('AED');
  const [direction, setDirection] = useState<Direction>(() => findDirection(params.sample ?? '') ?? 'debit');
  const [wordField, setWordField] = useState<WordField>('merchant');
  const [ranges, setRanges] = useState<Partial<Record<WordField, Range>>>({});

  const amounts = useMemo(() => findAmountCandidates(sample), [sample]);
  const digits = useMemo(() => findDigitsCandidates(sample), [sample]);
  const tokens = useMemo(() => tokenize(sample), [sample]);

  const changeSample = (text: string) => {
    setSample(text);
    setAmountPick(null);
    setDigitsPick(null);
    setRanges({});
    setDirection(findDirection(text) ?? 'debit');
  };

  const pickAmount = (i: number) => {
    setAmountPick(i);
    const cur = amounts[i].currency;
    if (cur) setCurrency(cur);
  };

  const marks: FieldMark[] = [];
  if (amountPick !== null && amounts[amountPick]) {
    marks.push({ field: 'amount', start: amounts[amountPick].start, end: amounts[amountPick].end });
  }
  if (digitsPick !== null && digits[digitsPick]) {
    marks.push({ field: 'account', start: digits[digitsPick].start, end: digits[digitsPick].end });
  }
  for (const f of ['merchant', 'date'] as const) {
    const r = ranges[f];
    if (r && tokens[r.to]) marks.push({ field: f, start: tokens[r.from].start, end: tokens[r.to].end });
  }

  const built = amountPick !== null ? buildPattern(sample, marks) : null;
  const preview =
    built?.ok === true
      ? applyCustomParser(
          { id: 'preview', name, senderContains: null, pattern: built.pattern, fields: built.fields, currency, direction },
          { sender, body: sample, receivedAt: 0 },
        )
      : null;

  const wordFieldAt = (i: number) =>
    (['merchant', 'date'] as const).find((f) => ranges[f] && i >= ranges[f]!.from && i <= ranges[f]!.to);

  // Tap the first and last word of the field; tap a lone word again to clear it.
  const tapWord = (i: number) => {
    const r = ranges[wordField];
    let next: Range | undefined;
    if (!r) next = { from: i, to: i };
    else if (r.from === i && r.to === i) next = undefined;
    else if (i < r.from) next = { from: i, to: r.to };
    else if (i > r.to) next = { from: r.from, to: i };
    else next = { from: i, to: i };
    setRanges({ ...ranges, [wordField]: next });
  };

  const save = async () => {
    if (built?.ok !== true || !preview) return;
    await createCustomParser(db, {
      name: name.trim() || sender.trim() || 'My SMS format',
      senderContains: sender.trim() || null,
      pattern: built.pattern,
      fields: built.fields,
      currency,
      direction,
      sample,
    });
    const recheck = await recheckUnread(db);
    if (recheck.added > 0) {
      Alert.alert('Saved', `${recheck.added} more message${recheck.added === 1 ? '' : 's'} could be read and went to Review.`);
    }
    router.back();
  };

  return (
    <Screen>
      <Field label="Bank SMS" value={sample} onChangeText={changeSample} placeholder="Paste one message from your bank" multiline />

      {sample.trim() ? (
        <Card>
          <Label>1. Which amount is the transaction?</Label>
          {amounts.length === 0 ? (
            <Body muted>No amounts found in this message.</Body>
          ) : (
            amounts.map((a, i) => {
              const selected = amountPick === i;
              return (
                <Pressable
                  key={a.start}
                  onPress={() => pickAmount(i)}
                  style={{
                    borderRadius: radius.md,
                    borderWidth: 1.5,
                    borderColor: selected ? c.primary : c.border,
                    backgroundColor: selected ? c.surfaceAlt : 'transparent',
                    padding: spacing.md,
                    gap: 2,
                  }}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                    <Body style={{ fontWeight: '600' }}>
                      {a.currency ? formatMoney(a.minor, a.currency) : (a.minor / 100).toFixed(2)}
                    </Body>
                    {a.looksLikeBalance ? <Body muted style={{ fontSize: 13 }}>looks like a balance</Body> : null}
                    {selected ? <Body style={{ color: c.primary, fontSize: 13 }}>✓ This one</Body> : null}
                  </View>
                  <Body muted style={{ fontSize: 13 }}>{a.context}</Body>
                </Pressable>
              );
            })
          )}
        </Card>
      ) : null}

      {amountPick !== null ? (
        <>
          <Card>
            <Label>2. Money in or out?</Label>
            <ChipRow>
              <Chip label="Money out (spent)" selected={direction === 'debit'} onPress={() => setDirection('debit')} />
              <Chip label="Money in (received)" selected={direction === 'credit'} onPress={() => setDirection('credit')} />
            </ChipRow>
            <ChipRow>
              {CURRENCIES.map((cur) => (
                <Chip key={cur} label={cur} selected={currency === cur} onPress={() => setCurrency(cur)} />
              ))}
            </ChipRow>
          </Card>

          <Card>
            <Label>3. Which card or account?</Label>
            <ChipRow>
              <Chip label="Not in this SMS" selected={digitsPick === null} onPress={() => setDigitsPick(null)} />
              {digits.map((d, i) => (
                <Chip key={d.start} label={`••${d.digits.slice(-4)}`} selected={digitsPick === i} onPress={() => setDigitsPick(i)} />
              ))}
            </ChipRow>
          </Card>

          <Card>
            <Label>4. Merchant and date (optional)</Label>
            <Body muted style={{ fontSize: 14 }}>
              Pick one, then tap its first and last word.
            </Body>
            <ChipRow>
              <Chip label={`Merchant${ranges.merchant ? ' ✓' : ''}`} selected={wordField === 'merchant'} onPress={() => setWordField('merchant')} />
              <Chip label={`Date${ranges.date ? ' ✓' : ''}`} selected={wordField === 'date'} onPress={() => setWordField('date')} />
            </ChipRow>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4 }}>
              {tokens.map((t, i) => {
                const field = wordFieldAt(i);
                return (
                  <Pressable
                    key={t.start}
                    onPress={() => tapWord(i)}
                    style={{
                      paddingHorizontal: 6,
                      paddingVertical: 4,
                      borderRadius: radius.sm,
                      backgroundColor: field ? WORD_FIELD_COLORS[field] : c.surfaceAlt,
                    }}>
                    <Text style={{ color: field ? '#FFFFFF' : c.text, fontSize: 15 }}>{t.text}</Text>
                  </Pressable>
                );
              })}
            </View>
          </Card>

          <Card>
            <Label>5. Save</Label>
            <Field label="Only for senders containing (optional)" value={sender} onChangeText={setSender} placeholder="e.g. ENBD" autoCapitalize="characters" />
            <Field label="Name" value={name} onChangeText={setName} placeholder="e.g. ENBD credit card" />

            {built?.ok === false ? <Body style={{ color: c.expense }}>{built.error}</Body> : null}
            {built?.ok && !preview ? <Body style={{ color: c.expense }}>This setup doesn&apos;t read the message back. Try marking fewer words.</Body> : null}
            {preview ? (
              <View style={{ gap: spacing.xs, backgroundColor: c.surfaceAlt, borderRadius: radius.md, padding: spacing.md }}>
                <Body style={{ fontWeight: '600' }}>
                  {direction === 'debit' ? 'Spent' : 'Received'} {formatMoney(preview.amountMinor, preview.currency)}
                </Body>
                <Body muted>Card: {preview.accountHint ? `••${preview.accountHint}` : '—'}</Body>
                <Body muted>Merchant: {preview.merchant ?? '—'}</Body>
                <Body muted>Date: {preview.occurredAt ? formatDate(preview.occurredAt) : 'when the SMS arrived'}</Body>
              </View>
            ) : null}
            <Button title="Save and use for future SMS" onPress={save} disabled={!preview} />
          </Card>
        </>
      ) : null}
    </Screen>
  );
}
