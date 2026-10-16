import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { Alert } from 'react-native';

import { Body, Button, Card, Label, Row, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import {
  ACCOUNT_KIND_LABELS,
  accountLabel,
  deleteAccount,
  listAccounts,
  listDetectedCards,
  type Account,
  type AccountKind,
} from '@/features/accounts/repository';
import { formatDate } from '@/lib/dates';

const KIND_ORDER: AccountKind[] = ['credit_card', 'debit_card', 'bank'];

export default function Accounts() {
  const db = useSQLiteContext();
  const data = useDbQuery(async (d) => {
    const [accounts, detected] = await Promise.all([listAccounts(d), listDetectedCards(d)]);
    return { accounts, detected };
  });
  if (!data) return <Screen>{null}</Screen>;

  const confirmDelete = (a: Account) =>
    Alert.alert(`Delete ${accountLabel(a)}?`, 'Its transactions are kept but no longer linked to it.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => deleteAccount(db, a.id) },
    ]);

  return (
    <Screen>
      {data.detected.length > 0 ? (
        <Card>
          <Label>Found in your SMS</Label>
          <Body muted style={{ fontSize: 14 }}>
            These card or account numbers appear in your bank messages. Add them so you can see which card each
            payment was made with.
          </Body>
          {data.detected.map((d) => (
            <Row
              key={`${d.digits}-${d.currency}`}
              title={`Ending ••${d.digits}`}
              subtitle={[
                `${d.count} transaction${d.count === 1 ? '' : 's'}`,
                d.currency,
                d.lastSender,
                `last ${formatDate(d.lastAt)}`,
              ]
                .filter(Boolean)
                .join(' · ')}
              right={
                <Button
                  title="Add"
                  variant="secondary"
                  onPress={() =>
                    router.push({
                      pathname: '/accounts/new',
                      params: { digits: d.digits, currency: d.currency, sender: d.lastSender ?? '' },
                    })
                  }
                />
              }
            />
          ))}
        </Card>
      ) : null}

      {KIND_ORDER.map((kind) => {
        const items = data.accounts.filter((a) => a.kind === kind);
        if (items.length === 0) return null;
        return (
          <Card key={kind}>
            <Label>{ACCOUNT_KIND_LABELS[kind]}s</Label>
            {items.map((a) => (
              <Row
                key={a.id}
                title={accountLabel(a)}
                subtitle={a.currency}
                right={<Button title="Delete" variant="ghost" onPress={() => confirmDelete(a)} />}
              />
            ))}
          </Card>
        );
      })}

      {data.accounts.length === 0 && data.detected.length === 0 ? (
        <Body muted>No cards or accounts yet. Add one, or scan your SMS and the cards will be found for you.</Body>
      ) : null}

      <Button title="Add card or account" onPress={() => router.push('/accounts/new')} />
    </Screen>
  );
}
