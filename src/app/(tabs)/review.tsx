import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useState } from 'react';
import { View } from 'react-native';

import { Amount, Body, Button, Card, Empty, Label, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { useLookups, type Lookups } from '@/features/transactions/components';
import { listPending, setStatus, type Transaction } from '@/features/transactions/repository';
import { formatDateTime } from '@/lib/dates';
import { radius, spacing, useColors } from '@/theme';

export default function Review() {
  const db = useSQLiteContext();
  const pending = useDbQuery(listPending);
  const lookups = useLookups();

  if (!pending || !lookups) return <Screen title="Review">{null}</Screen>;

  const clean = pending.filter((t) => !t.duplicateOfId);

  return (
    <Screen title="Review">
      {pending.length === 0 ? (
        <Empty title="All caught up" message="New transactions found in your SMS show up here for you to check.">
          <Button title="Import SMS" variant="secondary" onPress={() => router.push('/import-sms')} />
        </Empty>
      ) : (
        <>
          <Body muted>
            Accept what looks right, edit what doesn&apos;t, and reject OTPs or double entries. Your category choices
            are remembered for next time.
          </Body>
          {clean.length > 1 ? (
            <Button
              title={`Accept ${clean.length} without duplicate warnings`}
              variant="secondary"
              onPress={() => setStatus(db, clean.map((t) => t.id), 'accepted')}
            />
          ) : null}
          {pending.map((tx) => (
            <ReviewCard
              key={tx.id}
              tx={tx}
              lookups={lookups}
              duplicateOf={tx.duplicateOfId ? pending.find((p) => p.id === tx.duplicateOfId) : undefined}
              onAccept={() => setStatus(db, [tx.id], 'accepted')}
              onReject={() => setStatus(db, [tx.id], 'rejected')}
            />
          ))}
        </>
      )}
    </Screen>
  );
}

function ReviewCard({
  tx,
  lookups,
  duplicateOf,
  onAccept,
  onReject,
}: {
  tx: Transaction;
  lookups: Lookups;
  duplicateOf?: Transaction;
  onAccept: () => void;
  onReject: () => void;
}) {
  const c = useColors();
  const [showSms, setShowSms] = useState(false);

  return (
    <Card>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm }}>
        <Body style={{ fontWeight: '600', flex: 1 }} numberOfLines={1}>
          {tx.merchant ?? 'Unknown merchant'}
        </Body>
        <Amount minor={tx.amountMinor} currency={tx.currency} direction={tx.direction} style={{ fontSize: 18 }} />
      </View>
      <Body muted style={{ fontSize: 14 }}>
        {[
          formatDateTime(tx.occurredAt),
          lookups.cardName(tx) ?? 'No card',
          lookups.categoryName(tx.categoryId) ?? 'Uncategorised',
        ].join(' · ')}
      </Body>

      {tx.accountHint && !tx.accountId ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm }}>
          <Body muted style={{ fontSize: 14, flex: 1 }}>
            Card ••{tx.accountHint.slice(-4)} isn&apos;t in your cards yet.
          </Body>
          <Button
            title="Add card"
            variant="secondary"
            onPress={() =>
              router.push({
                pathname: '/accounts/new',
                params: { digits: tx.accountHint!.slice(-4), currency: tx.currency, sender: tx.smsSender ?? '' },
              })
            }
          />
        </View>
      ) : null}

      {tx.duplicateOfId ? (
        <View style={{ backgroundColor: c.warningBg, borderRadius: radius.sm, padding: spacing.sm }}>
          <Body style={{ color: c.warning, fontSize: 14 }}>
            Possible duplicate: same amount{' '}
            {duplicateOf ? `as the entry from ${formatDateTime(duplicateOf.occurredAt)}` : 'as an existing transaction'}{' '}
            within a few minutes. Reject it if this is the same payment.
          </Body>
        </View>
      ) : null}

      {tx.smsBody ? (
        <View>
          <Button
            title={showSms ? 'Hide SMS' : `Show SMS from ${tx.smsSender ?? 'sender'}`}
            variant="ghost"
            onPress={() => setShowSms(!showSms)}
            style={{ alignSelf: 'flex-start', paddingHorizontal: 0, minHeight: 32 }}
          />
          {showSms ? (
            <View style={{ backgroundColor: c.surfaceAlt, borderRadius: radius.sm, padding: spacing.sm }}>
              <Label style={{ textTransform: 'none' }}>{tx.smsBody}</Label>
            </View>
          ) : null}
        </View>
      ) : null}

      <View style={{ flexDirection: 'row', gap: spacing.sm }}>
        <Button title="Reject" variant="danger" onPress={onReject} style={{ flex: 1 }} />
        <Button
          title="Edit"
          variant="secondary"
          onPress={() => router.push({ pathname: '/transaction/[id]', params: { id: tx.id } })}
          style={{ flex: 1 }}
        />
        <Button title="Accept" onPress={onAccept} style={{ flex: 1 }} />
      </View>
    </Card>
  );
}
