import { router } from 'expo-router';
import { useState, type ReactNode } from 'react';
import { View } from 'react-native';

import { Amount, Body, Button, Card, Chip, ChipRow, Empty, Label, MonthSwitcher, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { summarize, type CurrencySummary } from '@/features/dashboard/summary';
import { ScanCard } from '@/features/sms/scan-card';
import { TransactionRow, useLookups, type Lookups } from '@/features/transactions/components';
import { countPending, listAccepted, listSummaryInputs } from '@/features/transactions/repository';
import { addMonths, formatMonth, startOfMonth } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { radius, spacing, useColors } from '@/theme';

export default function Overview() {
  const [month, setMonth] = useState(() => startOfMonth(Date.now()));
  const [allTime, setAllTime] = useState(false);
  const lookups = useLookups();
  const data = useDbQuery(
    async (db) => {
      const [from, to] = allTime ? [0, Number.MAX_SAFE_INTEGER] : [month, addMonths(month, 1)];
      const [recent, inputs, pending] = await Promise.all([
        listAccepted(db, from, to, 5),
        listSummaryInputs(db, from, to),
        countPending(db),
      ]);
      return { recent, pending, summaries: summarize(inputs) };
    },
    allTime ? 'all' : String(month),
  );

  return (
    <Screen title="Overview">
      <ChipRow>
        <Chip label="Month" selected={!allTime} onPress={() => setAllTime(false)} />
        <Chip label="All time" selected={allTime} onPress={() => setAllTime(true)} />
      </ChipRow>
      {allTime ? null : (
        <MonthSwitcher
          label={formatMonth(month)}
          onPrev={() => setMonth(addMonths(month, -1))}
          onNext={() => setMonth(addMonths(month, 1))}
        />
      )}

      <ScanCard />

      {data && data.pending > 0 ? (
        <Card>
          <Body style={{ fontWeight: '600' }}>
            {data.pending} transaction{data.pending === 1 ? '' : 's'} waiting for review
          </Body>
          <Button title="Review now" onPress={() => router.navigate('/review')} />
        </Card>
      ) : null}

      {data && lookups ? (
        data.summaries.length === 0 ? (
          <Empty
            title={allTime ? 'No transactions yet' : 'Nothing this month'}
            message="Import a bank SMS or add a transaction to get started."
          >
            <View style={{ flexDirection: 'row', gap: spacing.sm }}>
              <Button title="Import SMS" variant="secondary" onPress={() => router.push('/import-sms')} />
              <Button
                title="Add manually"
                onPress={() => router.push({ pathname: '/transaction/[id]', params: { id: 'new' } })}
              />
            </View>
          </Empty>
        ) : (
          <>
            {data.summaries.map((s) => (
              <SummaryCard key={s.currency} summary={s} lookups={lookups} />
            ))}
            <Card>
              <Label>Recent</Label>
              {data.recent.map((tx) => (
                <TransactionRow key={tx.id} tx={tx} lookups={lookups} />
              ))}
              <Button title="See all" variant="ghost" onPress={() => router.navigate('/transactions')} />
            </Card>
          </>
        )
      ) : null}
    </Screen>
  );
}

function SummaryCard({ summary: s, lookups }: { summary: CurrencySummary; lookups: Lookups }) {
  const c = useColors();
  const top = s.topSpending.slice(0, 5);
  const max = top[0]?.minor ?? 1;
  return (
    <Card>
      <Label>{s.currency}</Label>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Stat label="Money in" value={<Amount minor={s.inMinor} currency={s.currency} direction="credit" />} />
        <Stat label="Money out" value={<Amount minor={s.outMinor} currency={s.currency} direction="debit" />} />
        <Stat
          label="Net"
          value={
            <Body style={{ fontWeight: '600', color: s.netMinor < 0 ? c.expense : c.income }}>
              {formatMoney(s.netMinor, s.currency, { signed: true })}
            </Body>
          }
        />
      </View>
      {top.length > 0 ? (
        <View style={{ gap: spacing.sm, marginTop: spacing.sm }}>
          {top.map((t) => (
            <View key={t.categoryId ?? 'none'} style={{ gap: 4 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                <Body style={{ fontSize: 14 }}>{lookups.categoryName(t.categoryId) ?? 'Uncategorised'}</Body>
                <Body muted style={{ fontSize: 14 }}>
                  {formatMoney(t.minor, s.currency)}
                </Body>
              </View>
              <View style={{ height: 6, borderRadius: radius.pill, backgroundColor: c.surfaceAlt }}>
                <View
                  style={{
                    height: 6,
                    borderRadius: radius.pill,
                    backgroundColor: c.primary,
                    width: `${Math.max(4, (t.minor / max) * 100)}%`,
                  }}
                />
              </View>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <View style={{ gap: 2 }}>
      <Body muted style={{ fontSize: 13 }}>
        {label}
      </Body>
      {value}
    </View>
  );
}
