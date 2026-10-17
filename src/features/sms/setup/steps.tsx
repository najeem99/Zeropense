import { useState } from 'react';
import { Pressable, View } from 'react-native';

import { Body, Button, Card, Chip, ChipRow, Field, Label } from '@/components/ui';
import { ACCOUNT_KIND_LABELS, accountLabel, type Account, type AccountKind } from '@/features/accounts/repository';
import { CURRENCIES, formatMoney } from '@/lib/money';
import { radius, spacing, useColors } from '@/theme';

import { findAmountCandidates } from '../candidates';
import { KIND_LABELS, type SenderGroup } from '../templates';

import {
  kindId,
  PERIODS,
  type CardChoice,
  type DetectedDigits,
  type KindChoice,
  type SenderOption,
} from './state';

function CheckRow({
  checked,
  onPress,
  title,
  subtitle,
  badge,
}: {
  checked: boolean;
  onPress: () => void;
  title: string;
  subtitle?: string;
  badge?: string;
}) {
  const c = useColors();
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityState={{ checked }}
      onPress={onPress}
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md, paddingVertical: spacing.sm }}>
      <View
        style={{
          width: 22,
          height: 22,
          borderRadius: 6,
          borderWidth: 2,
          borderColor: checked ? c.primary : c.border,
          backgroundColor: checked ? c.primary : 'transparent',
          alignItems: 'center',
          justifyContent: 'center',
        }}>
        {checked ? <Body style={{ color: c.onPrimary, fontSize: 14, fontWeight: '700' }}>✓</Body> : null}
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <Body numberOfLines={1} style={{ fontWeight: '600' }}>
          {title}
        </Body>
        {subtitle ? (
          <Body muted numberOfLines={2} style={{ fontSize: 13 }}>
            {subtitle}
          </Body>
        ) : null}
      </View>
      {badge ? <Body muted style={{ fontSize: 12 }}>{badge}</Body> : null}
    </Pressable>
  );
}

export function PeriodStep({ onPick }: { onPick: (months: number) => void }) {
  return (
    <Card>
      <Label>How far back should we read?</Label>
      <Body muted style={{ fontSize: 14 }}>
        Only bank messages from this period are imported. You can run the setup again later.
      </Body>
      {PERIODS.map((p) => (
        <Button key={p.months} title={p.label} variant="secondary" onPress={() => onPick(p.months)} />
      ))}
    </Card>
  );
}

export function SendersStep({
  senders,
  selected,
  onToggle,
  onNext,
}: {
  senders: SenderOption[];
  selected: Set<string>;
  onToggle: (key: string) => void;
  onNext: () => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const withMoney = senders.filter((s) => s.moneyCount > 0);
  const rest = senders.filter((s) => s.moneyCount === 0);
  const row = (s: SenderOption) => (
    <CheckRow
      key={s.key}
      checked={selected.has(s.key)}
      onPress={() => onToggle(s.key)}
      title={s.key}
      subtitle={`${s.count} messages · ${s.moneyCount} with an amount`}
      badge={s.suggested ? 'bank?' : undefined}
    />
  );
  return (
    <>
      <Card>
        <Label>Which senders are your banks?</Label>
        <Body muted style={{ fontSize: 14 }}>
          Personal numbers are hidden. Tick only the banks and card providers you want to track.
        </Body>
        {withMoney.length === 0 ? <Body muted>No messages with amounts in this period.</Body> : withMoney.map(row)}
      </Card>
      {rest.length > 0 ? (
        <Card>
          <Button
            title={showAll ? 'Hide other senders' : `Show ${rest.length} senders without amounts`}
            variant="ghost"
            onPress={() => setShowAll(!showAll)}
          />
          {showAll ? rest.map(row) : null}
        </Card>
      ) : null}
      <Button title={`Next: message types (${selected.size} selected)`} disabled={selected.size === 0} onPress={onNext} />
    </>
  );
}

function KindEditor({
  sample,
  choice,
  onChange,
}: {
  sample: string;
  choice: KindChoice;
  onChange: (c: KindChoice) => void;
}) {
  const c = useColors();
  const amounts = findAmountCandidates(sample);
  return (
    <View style={{ gap: spacing.sm, paddingLeft: 34 }}>
      <Body muted style={{ fontSize: 13 }}>
        Which amount is the transaction?
      </Body>
      <View style={{ gap: spacing.xs }}>
        {amounts.map((a, i) => {
          const selected = choice.amountIndex === i;
          return (
            <Pressable
              key={a.start}
              onPress={() => onChange({ ...choice, amountIndex: i, currency: a.currency ?? choice.currency })}
              style={{
                borderRadius: radius.sm,
                borderWidth: 1.5,
                borderColor: selected ? c.primary : c.border,
                padding: spacing.sm,
              }}>
              <Body style={{ fontWeight: '600', fontSize: 14 }}>
                {a.currency ? formatMoney(a.minor, a.currency) : (a.minor / 100).toFixed(2)}
                {a.looksLikeBalance ? '  (looks like a balance)' : ''}
              </Body>
              <Body muted style={{ fontSize: 12 }} numberOfLines={1}>
                {a.context}
              </Body>
            </Pressable>
          );
        })}
      </View>
      <ChipRow>
        <Chip label="Money out" selected={choice.direction === 'debit'} onPress={() => onChange({ ...choice, direction: 'debit' })} />
        <Chip label="Money in" selected={choice.direction === 'credit'} onPress={() => onChange({ ...choice, direction: 'credit' })} />
        {CURRENCIES.map((cur) => (
          <Chip key={cur} label={cur} selected={choice.currency === cur} onPress={() => onChange({ ...choice, currency: cur })} />
        ))}
      </ChipRow>
    </View>
  );
}

export function KindsStep({
  groups,
  choices,
  onChange,
  onNext,
  includedCount,
}: {
  groups: SenderGroup[];
  choices: Record<string, KindChoice>;
  onChange: (id: string, c: KindChoice) => void;
  onNext: () => void;
  includedCount: number;
}) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  return (
    <>
      <Body muted>
        Each bank sends several kinds of messages. Tick only the ones that are real payments or money received. OTPs and
        offers are left out.
      </Body>
      {groups.map((g) => {
        const kinds = g.kinds;
        const main = kinds.filter((k) => k.suggestion.label === 'transaction' || choices[kindId(g.senderKey, k.templateKey)]?.include);
        const others = kinds.filter((k) => !main.includes(k));
        const renderKind = (k: (typeof kinds)[number]) => {
          const id = kindId(g.senderKey, k.templateKey);
          const choice = choices[id];
          const sample = k.messages[k.messages.length - 1].body;
          const hasAmount = findAmountCandidates(sample).length > 0;
          return (
            <View key={id} style={{ gap: spacing.xs }}>
              <CheckRow
                checked={choice.include}
                onPress={() => hasAmount && onChange(id, { ...choice, include: !choice.include })}
                title={`${KIND_LABELS[k.suggestion.label]} · ${k.messages.length} message${k.messages.length === 1 ? '' : 's'}`}
                subtitle={sample}
              />
              {choice.include ? <KindEditor sample={sample} choice={choice} onChange={(c) => onChange(id, c)} /> : null}
            </View>
          );
        };
        return (
          <Card key={g.senderKey}>
            <Label>{g.senderKey}</Label>
            {main.length === 0 ? <Body muted style={{ fontSize: 14 }}>No transaction messages recognised.</Body> : null}
            {main.map(renderKind)}
            {others.length > 0 ? (
              <>
                <Button
                  title={expanded[g.senderKey] ? 'Hide other messages' : `Show ${others.length} other kinds (OTP, offers…)`}
                  variant="ghost"
                  onPress={() => setExpanded({ ...expanded, [g.senderKey]: !expanded[g.senderKey] })}
                />
                {expanded[g.senderKey] ? others.map(renderKind) : null}
              </>
            ) : null}
          </Card>
        );
      })}
      <Button title={`Next: cards (${includedCount} transactions)`} disabled={includedCount === 0} onPress={onNext} />
    </>
  );
}

function NewCardForm({ value, onChange }: { value: Extract<CardChoice, { mode: 'new' }>; onChange: (v: CardChoice) => void }) {
  const c = useColors();
  return (
    <View style={{ gap: spacing.sm }}>
      <Body style={{ fontSize: 14, fontWeight: '600' }}>Is this a credit card or a debit card?</Body>
      <ChipRow>
        {(['credit_card', 'debit_card', 'bank'] as AccountKind[]).map((k) => (
          <Chip key={k} label={ACCOUNT_KIND_LABELS[k]} selected={value.kind === k} onPress={() => onChange({ ...value, kind: k })} />
        ))}
      </ChipRow>
      {value.kind === null ? <Body style={{ color: c.expense, fontSize: 13 }}>Choose one.</Body> : null}
      <Field label="Name" value={value.name} onChangeText={(name) => onChange({ ...value, name })} placeholder="e.g. ENBD Platinum" />
      <ChipRow>
        {CURRENCIES.map((cur) => (
          <Chip key={cur} label={cur} selected={value.currency === cur} onPress={() => onChange({ ...value, currency: cur })} />
        ))}
      </ChipRow>
    </View>
  );
}

export function CardsStep({
  digits,
  noDigitKinds,
  accounts,
  cardChoices,
  fixedAccounts,
  onCard,
  onFixed,
  canContinue,
  onNext,
}: {
  digits: DetectedDigits[];
  noDigitKinds: { id: string; senderKey: string; kind: SenderGroup['kinds'][number] }[];
  accounts: Account[];
  cardChoices: Record<string, CardChoice>;
  fixedAccounts: Record<string, string | null>;
  onCard: (digits: string, c: CardChoice) => void;
  onFixed: (kindId: string, accountId: string | null) => void;
  canContinue: boolean;
  onNext: () => void;
}) {
  return (
    <>
      {digits.length === 0 && noDigitKinds.length === 0 ? <Body muted>No card numbers in these messages.</Body> : null}
      {digits.map((d) => {
        const choice = cardChoices[d.digits];
        // An account can only stand for one card number.
        const usable = accounts.filter((a) => !a.smsHint || a.smsHint === d.digits);
        return (
          <Card key={d.digits}>
            <Label>Card ending ••{d.digits}</Label>
            <Body muted style={{ fontSize: 13 }}>
              {d.count} messages · {d.senders.join(', ')}
            </Body>
            <ChipRow>
              {usable.map((a) => (
                <Chip
                  key={a.id}
                  label={accountLabel(a)}
                  selected={choice?.mode === 'existing' && choice.accountId === a.id}
                  onPress={() => onCard(d.digits, { mode: 'existing', accountId: a.id })}
                />
              ))}
              <Chip
                label="+ New card"
                selected={choice?.mode === 'new'}
                onPress={() => onCard(d.digits, { mode: 'new', name: d.senders[0] ?? '', kind: null, currency: d.currency })}
              />
              <Chip label="Skip" selected={choice?.mode === 'skip'} onPress={() => onCard(d.digits, { mode: 'skip' })} />
            </ChipRow>
            {choice?.mode === 'new' ? <NewCardForm value={choice} onChange={(c) => onCard(d.digits, c)} /> : null}
          </Card>
        );
      })}
      {noDigitKinds.length > 0 ? (
        <Card>
          <Label>Messages without a card number</Label>
          <Body muted style={{ fontSize: 13 }}>
            Optionally pick the account these belong to.
          </Body>
          {noDigitKinds.map((k) => (
            <View key={k.id} style={{ gap: spacing.xs, paddingVertical: spacing.xs }}>
              <Body style={{ fontSize: 14 }} numberOfLines={2}>
                {k.senderKey}: {k.kind.messages[k.kind.messages.length - 1].body}
              </Body>
              <ChipRow>
                <Chip label="None" selected={!fixedAccounts[k.id]} onPress={() => onFixed(k.id, null)} />
                {accounts.map((a) => (
                  <Chip key={a.id} label={accountLabel(a)} selected={fixedAccounts[k.id] === a.id} onPress={() => onFixed(k.id, a.id)} />
                ))}
              </ChipRow>
            </View>
          ))}
        </Card>
      ) : null}
      {!canContinue ? <Body muted>Choose a card, “New card” or “Skip” for every number above.</Body> : null}
      <Button title="Next: review and import" disabled={!canContinue} onPress={onNext} />
    </>
  );
}
