import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { useMemo, useState } from 'react';
import { Alert, FlatList, View, type AlertButton } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Amount, Body, Button, Card, Empty, Label, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { CategoryIcon, CategoryPill } from '@/features/categories/category-icon';
import { transactionTitle, useLookups, type Lookups } from '@/features/transactions/components';
import { approveAllPending, listPending, setStatus, type Transaction } from '@/features/transactions/repository';
import { formatDateTime } from '@/lib/dates';
import { radius, spacing, useColors } from '@/theme';

export default function Review() {
  const db = useSQLiteContext();
  const c = useColors();
  const pending = useDbQuery(listPending);
  const lookups = useLookups();

  const byId = useMemo(() => new Map((pending ?? []).map((t) => [t.id, t])), [pending]);

  if (!pending || !lookups) return <Screen title="Review">{null}</Screen>;

  const duplicates = pending.filter((t) => t.duplicateOfId).length;

  const approveAll = () => {
    const n = pending.length;
    const buttons: AlertButton[] = [{ text: 'Cancel', style: 'cancel' }];
    if (duplicates > 0) {
      buttons.push({
        text: `All except ${duplicates} duplicate${duplicates === 1 ? '' : 's'}`,
        onPress: () => approveAllPending(db, false),
      });
    }
    buttons.push({ text: duplicates > 0 ? `All ${n}` : 'Approve', onPress: () => approveAllPending(db, true) });
    Alert.alert(
      `Approve ${n} transaction${n === 1 ? '' : 's'}?`,
      duplicates > 0
        ? `${duplicates} of them may be duplicates of another entry. Approving those counts the payment twice.`
        : 'They will be added to your totals.',
      buttons,
    );
  };

  if (pending.length === 0) {
    return (
      <Screen title="Review">
        <Empty title="All caught up" message="New transactions from your bank SMS show up here for you to check.">
          <Button title="Paste an SMS" variant="secondary" onPress={() => router.push('/import-sms')} />
        </Empty>
      </Screen>
    );
  }

  return (
    <SafeAreaView edges={['top']} style={{ flex: 1, backgroundColor: c.background }}>
      <FlatList
        data={pending}
        keyExtractor={(t) => t.id}
        contentContainerStyle={{ padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl * 2 }}
        initialNumToRender={8}
        windowSize={7}
        removeClippedSubviews
        ListHeaderComponent={
          <View style={{ gap: spacing.md, marginBottom: spacing.md }}>
            <Body style={{ fontSize: 32, fontWeight: '700' }}>Review</Body>
            <Body muted>
              Accept what looks right, edit what doesn&apos;t, and reject double entries. Your category choices are
              remembered for next time.
            </Body>
            <Button title={`Approve all (${pending.length})`} onPress={approveAll} />
          </View>
        }
        renderItem={({ item }) => (
          <ReviewCard
            tx={item}
            lookups={lookups}
            duplicateOf={item.duplicateOfId ? byId.get(item.duplicateOfId) : undefined}
            onAccept={() => setStatus(db, [item.id], 'accepted')}
            onReject={() => setStatus(db, [item.id], 'rejected')}
          />
        )}
      />
    </SafeAreaView>
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
  const title = transactionTitle(tx);

  return (
    <Card>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
        <CategoryIcon categoryId={tx.categoryId} size={40} />
        <View style={{ flex: 1, gap: 4 }}>
          <Body
            numberOfLines={1}
            style={title.isSnippet ? { fontSize: 13, fontStyle: 'italic', color: c.textMuted } : { fontWeight: '600' }}>
            {title.text}
          </Body>
          <CategoryPill categoryId={tx.categoryId} name={lookups.categoryName(tx.categoryId) ?? 'Uncategorised'} />
        </View>
        <Amount minor={tx.amountMinor} currency={tx.currency} direction={tx.direction} style={{ fontSize: 18 }} />
      </View>
      <Body muted style={{ fontSize: 13 }}>
        {[formatDateTime(tx.occurredAt), lookups.cardName(tx) ?? 'No card'].join(' · ')}
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
