import type { AccountKind } from '@/features/accounts/repository';
import type { Currency, Direction } from '@/lib/money';

import type { InboxSender } from '../../../../modules/sms-reader';
import { isPersonalSender, senderKey, type SenderGroup, type SmsRule } from '../templates';
import { profileForSender } from '../banks';

export const PERIODS = [
  { label: '1 month', months: 1 },
  { label: '3 months', months: 3 },
  { label: '6 months', months: 6 },
  { label: '1 year', months: 12 },
] as const;

export function periodStart(months: number, now = Date.now()): number {
  const d = new Date(now);
  d.setMonth(d.getMonth() - months);
  return d.getTime();
}

/** One bank sender in the setup, merged across its operator prefixes. */
export type SenderOption = {
  key: string;
  addresses: string[];
  count: number;
  moneyCount: number;
  /** Pre-ticked: a known bank, or mostly messages with amounts. */
  suggested: boolean;
};

export function mergeSenders(raw: InboxSender[]): SenderOption[] {
  const byKey = new Map<string, SenderOption>();
  for (const s of raw) {
    if (isPersonalSender(s.sender)) continue;
    const key = senderKey(s.sender);
    let o = byKey.get(key);
    if (!o) byKey.set(key, (o = { key, addresses: [], count: 0, moneyCount: 0, suggested: false }));
    o.addresses.push(s.sender);
    o.count += s.count;
    o.moneyCount += s.moneyCount;
  }
  const out = [...byKey.values()];
  for (const o of out) {
    o.suggested = profileForSender(o.key) !== null || (o.moneyCount >= 2 && o.moneyCount / o.count >= 0.3);
  }
  return out.sort((a, b) => b.moneyCount - a.moneyCount || b.count - a.count);
}

export const kindId = (sender: string, template: string) => `${sender}|${template}`;

export type KindChoice = {
  include: boolean;
  amountIndex: number;
  direction: Direction;
  currency: Currency;
};

export function initialChoices(groups: SenderGroup[]): Record<string, KindChoice> {
  const out: Record<string, KindChoice> = {};
  for (const g of groups) {
    for (const k of g.kinds) {
      const s = k.suggestion;
      out[kindId(g.senderKey, k.templateKey)] = {
        include: s.label === 'transaction',
        amountIndex: s.amountIndex,
        direction: s.direction,
        currency: s.currency,
      };
    }
  }
  return out;
}

export type CardChoice =
  | { mode: 'existing'; accountId: string }
  | { mode: 'new'; name: string; kind: AccountKind | null; currency: Currency }
  | { mode: 'skip' };

export type DetectedDigits = { digits: string; count: number; senders: string[]; currency: Currency };

/** Card digits named in the kinds the user is importing. */
export function collectDigits(groups: SenderGroup[], choices: Record<string, KindChoice>): DetectedDigits[] {
  const map = new Map<string, DetectedDigits>();
  for (const g of groups) {
    for (const k of g.kinds) {
      const c = choices[kindId(g.senderKey, k.templateKey)];
      if (!c?.include) continue;
      for (const d of k.digits) {
        let e = map.get(d);
        if (!e) map.set(d, (e = { digits: d, count: 0, senders: [], currency: c.currency }));
        e.count += k.messages.length;
        if (!e.senders.includes(g.senderKey)) e.senders.push(g.senderKey);
      }
    }
  }
  return [...map.values()].sort((a, b) => b.count - a.count);
}

/** Included kinds that never name a card; the user can pick a fixed account for them. */
export function kindsWithoutDigits(groups: SenderGroup[], choices: Record<string, KindChoice>) {
  return groups.flatMap((g) =>
    g.kinds
      .filter((k) => choices[kindId(g.senderKey, k.templateKey)]?.include && k.digits.length === 0)
      .map((k) => ({ id: kindId(g.senderKey, k.templateKey), senderKey: g.senderKey, kind: k })),
  );
}

export function cardChoiceComplete(c: CardChoice | undefined): boolean {
  if (!c) return false;
  if (c.mode === 'new') return c.kind !== null && c.name.trim().length > 0;
  return true;
}

/** Every kind of the chosen senders becomes a rule: included ones import, the rest are ignored. */
export function buildRules(
  groups: SenderGroup[],
  choices: Record<string, KindChoice>,
  fixedAccounts: Record<string, string | null>,
): Omit<SmsRule, 'id'>[] {
  return groups.flatMap((g) =>
    g.kinds.map((k) => {
      const id = kindId(g.senderKey, k.templateKey);
      const c = choices[id];
      return {
        senderKey: g.senderKey,
        templateKey: k.templateKey,
        action: c?.include ? ('import' as const) : ('ignore' as const),
        amountIndex: c?.amountIndex ?? 0,
        direction: c?.direction ?? 'debit',
        currency: c?.currency ?? 'AED',
        fixedAccountId: fixedAccounts[id] ?? null,
        sample: k.messages[k.messages.length - 1].body,
      };
    }),
  );
}

export function countIncluded(groups: SenderGroup[], choices: Record<string, KindChoice>): number {
  let n = 0;
  for (const g of groups) {
    for (const k of g.kinds) if (choices[kindId(g.senderKey, k.templateKey)]?.include) n += k.messages.length;
  }
  return n;
}
