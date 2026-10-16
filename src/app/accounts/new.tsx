import { router, useLocalSearchParams } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useState } from 'react';
import { Alert, View } from 'react-native';

import { Body, Button, Card, Chip, ChipRow, Field, Label, Screen } from '@/components/ui';
import { ACCOUNT_KIND_LABELS, createAccount, type AccountKind } from '@/features/accounts/repository';
import { profileForSender } from '@/features/sms/banks';
import { CURRENCIES, type Currency } from '@/lib/money';
import { spacing, useColors } from '@/theme';

const KINDS: AccountKind[] = ['credit_card', 'debit_card', 'bank'];

export default function NewAccount() {
  const params = useLocalSearchParams<{ digits?: string; currency?: string; sender?: string }>();
  const db = useSQLiteContext();
  const c = useColors();
  const fromSms = Boolean(params.digits);
  const bank = params.sender ? (profileForSender(params.sender)?.name ?? params.sender) : '';

  // No default: the user must say whether it's a credit or debit card.
  const [kind, setKind] = useState<AccountKind | null>(null);
  const [name, setName] = useState(bank);
  const [currency, setCurrency] = useState<Currency>(params.currency === 'INR' ? 'INR' : 'AED');
  const [digits, setDigits] = useState(params.digits ?? '');
  const [showKindError, setShowKindError] = useState(false);

  const save = async () => {
    if (!kind) {
      setShowKindError(true);
      return;
    }
    const linked = await createAccount(db, {
      name: name.trim() || ACCOUNT_KIND_LABELS[kind],
      kind,
      currency,
      smsHint: digits,
    });
    if (linked > 0) {
      Alert.alert('Added', `${linked} earlier transaction${linked === 1 ? ' was' : 's were'} linked to this ${ACCOUNT_KIND_LABELS[kind].toLowerCase()}.`);
    }
    router.back();
  };

  return (
    <Screen>
      {fromSms ? (
        <Body muted>
          Found in your SMS{bank ? ` from ${bank}` : ''}: a card or account ending ••{params.digits}.
        </Body>
      ) : null}

      <Card>
        <Label>Is this a credit card or a debit card?</Label>
        <ChipRow>
          {KINDS.map((k) => (
            <Chip
              key={k}
              label={ACCOUNT_KIND_LABELS[k]}
              selected={kind === k}
              onPress={() => {
                setKind(k);
                setShowKindError(false);
              }}
            />
          ))}
        </ChipRow>
        {showKindError ? <Body style={{ color: c.expense, fontSize: 14 }}>Choose one to continue.</Body> : null}
      </Card>

      <Card>
        <Field label="Name" value={name} onChangeText={setName} placeholder="e.g. ENBD Platinum" />
        <Field
          label="Last 4 digits"
          value={digits}
          onChangeText={(t) => setDigits(t.replace(/\D/g, '').slice(-4))}
          keyboardType="number-pad"
          placeholder="1234"
        />
        <Body muted style={{ fontSize: 13 }}>
          SMS mentioning these digits (like “card ending 1234”) are linked to this {kind ? ACCOUNT_KIND_LABELS[kind].toLowerCase() : 'card'}.
        </Body>
        <View style={{ gap: spacing.xs }}>
          <Label>Currency</Label>
          <ChipRow>
            {CURRENCIES.map((cur) => (
              <Chip key={cur} label={cur} selected={currency === cur} onPress={() => setCurrency(cur)} />
            ))}
          </ChipRow>
        </View>
      </Card>

      <Button title="Save" onPress={save} />
    </Screen>
  );
}
