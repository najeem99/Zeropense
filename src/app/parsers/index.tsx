import { router } from 'expo-router';
import { useSQLiteContext } from 'expo-sqlite';
import { Alert, View } from 'react-native';

import { Body, Button, Card, Label, Row, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { BANK_PROFILES } from '@/features/sms/banks';
import { deleteCustomParser, dismissUnread, listCustomParsers, listUnread } from '@/features/sms/repository';
import { formatDateTime } from '@/lib/dates';
import { spacing } from '@/theme';

export default function SmsSettings() {
  const db = useSQLiteContext();
  const data = useDbQuery(async (d) => {
    const [parsers, unread] = await Promise.all([listCustomParsers(d), listUnread(d)]);
    return { parsers, unread };
  });
  if (!data) return <Screen>{null}</Screen>;

  return (
    <Screen>
      <Button title="Set up a new SMS format" onPress={() => router.push('/parsers/new')} />

      {data.unread.length > 0 ? (
        <Card>
          <Label>Messages we couldn&apos;t read ({data.unread.length})</Label>
          <Body muted style={{ fontSize: 14 }}>
            These have an amount but no format matched. Set one up and similar messages are read from then on.
          </Body>
          {data.unread.slice(0, 20).map((u) => (
            <View key={u.smsHash} style={{ gap: spacing.xs, paddingVertical: spacing.sm }}>
              <Body muted style={{ fontSize: 13 }}>
                {u.sender} · {formatDateTime(u.receivedAt)}
              </Body>
              <Body numberOfLines={3} style={{ fontSize: 14 }}>
                {u.body}
              </Body>
              <View style={{ flexDirection: 'row', gap: spacing.sm }}>
                <Button
                  title="Set up this format"
                  variant="secondary"
                  onPress={() => router.push({ pathname: '/parsers/new', params: { sample: u.body, sender: u.sender } })}
                />
                <Button title="Ignore" variant="ghost" onPress={() => dismissUnread(db, u.smsHash)} />
              </View>
            </View>
          ))}
        </Card>
      ) : null}

      <Card>
        <Label>Your formats</Label>
        {data.parsers.length === 0 ? (
          <Body muted>None yet. Yours are tried before the built-in ones.</Body>
        ) : null}
        {data.parsers.map((p) => (
          <Row
            key={p.id}
            title={p.name}
            subtitle={[
              p.senderContains ? `Sender contains “${p.senderContains}”` : 'Any sender',
              p.currency,
              p.direction === 'debit' ? 'Money out' : 'Money in',
            ].join(' · ')}
            right={
              <Button
                title="Delete"
                variant="ghost"
                onPress={() =>
                  Alert.alert(`Delete ${p.name}?`, undefined, [
                    { text: 'Cancel', style: 'cancel' },
                    { text: 'Delete', style: 'destructive', onPress: () => deleteCustomParser(db, p.id) },
                  ])
                }
              />
            }
          />
        ))}
      </Card>

      <Card>
        <Label>Built in</Label>
        {BANK_PROFILES.map((b) => (
          <Row key={b.id} title={b.name} subtitle={`${b.defaultCurrency} · matched by sender`} />
        ))}
        <Row title="Generic" subtitle="Any bank, AED or INR, using common wording" />
      </Card>
    </Screen>
  );
}
