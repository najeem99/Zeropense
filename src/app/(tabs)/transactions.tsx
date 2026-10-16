import { router } from 'expo-router';
import { useState } from 'react';

import { Button, Card, Empty, Label, MonthSwitcher, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { TransactionRow, useLookups } from '@/features/transactions/components';
import { listAccepted, type Transaction } from '@/features/transactions/repository';
import { addMonths, formatDate, formatMonth, startOfMonth } from '@/lib/dates';

function groupByDay(txs: Transaction[]): { day: string; items: Transaction[] }[] {
  const groups: { day: string; items: Transaction[] }[] = [];
  for (const tx of txs) {
    const day = formatDate(tx.occurredAt);
    const last = groups[groups.length - 1];
    if (last?.day === day) last.items.push(tx);
    else groups.push({ day, items: [tx] });
  }
  return groups;
}

export default function Transactions() {
  const [month, setMonth] = useState(() => startOfMonth(Date.now()));
  const lookups = useLookups();
  const txs = useDbQuery((db) => listAccepted(db, month, addMonths(month, 1)), String(month));

  return (
    <Screen title="Transactions">
      <Button
        title="Add transaction"
        onPress={() => router.push({ pathname: '/transaction/[id]', params: { id: 'new' } })}
      />
      <MonthSwitcher
        label={formatMonth(month)}
        onPrev={() => setMonth(addMonths(month, -1))}
        onNext={() => setMonth(addMonths(month, 1))}
      />
      {txs && lookups ? (
        txs.length === 0 ? (
          <Empty title="No transactions" message="Accepted transactions for this month appear here." />
        ) : (
          groupByDay(txs).map((g) => (
            <Card key={g.day}>
              <Label>{g.day}</Label>
              {g.items.map((tx) => (
                <TransactionRow key={tx.id} tx={tx} lookups={lookups} />
              ))}
            </Card>
          ))
        )
      ) : null}
    </Screen>
  );
}
