import { router, type Href } from 'expo-router';

import { Body, Card, Row, Screen } from '@/components/ui';
import { ScanCard } from '@/features/sms/scan-card';

const LINKS: { title: string; subtitle: string; href: Href }[] = [
  { title: 'SMS configuration', subtitle: 'Choose which amount to take from a bank SMS', href: '/parsers' },
  { title: 'Cards & accounts', subtitle: 'Credit and debit cards, found from SMS by last 4 digits', href: '/accounts' },
  { title: 'Paste an SMS', subtitle: 'Test how a single message is read', href: '/import-sms' },
  { title: 'Categories', subtitle: 'Categories and remembered merchants', href: '/categories' },
];

export default function More() {
  return (
    <Screen title="More">
      <ScanCard />
      <Card>
        {LINKS.map((l) => (
          <Row key={l.title} title={l.title} subtitle={l.subtitle} right={<Body muted>›</Body>} onPress={() => router.push(l.href)} />
        ))}
      </Card>
      <Body muted style={{ fontSize: 13, textAlign: 'center' }}>
        Your data stays on this device. SMS are read on the phone and never uploaded.
      </Body>
    </Screen>
  );
}
