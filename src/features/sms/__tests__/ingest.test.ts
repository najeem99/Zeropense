import { DedupeIndex } from '@/features/transactions/dedupe';

import { matchAccount, processSms, type IngestContext } from '../ingest';
import { senderKey, templateKey, type SmsRule } from '../templates';

const t0 = new Date(2026, 9, 11, 13, 0).getTime();
const hdfc = 'Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123';

const rule = (over: Partial<SmsRule> = {}): SmsRule => ({
  id: 'r1',
  senderKey: senderKey('VM-HDFCBK'),
  templateKey: templateKey(hdfc),
  action: 'import',
  amountIndex: 0,
  direction: 'debit',
  currency: 'INR',
  fixedAccountId: null,
  sample: hdfc,
  ...over,
});

const ctx = (over: Partial<IngestContext> = {}): IngestContext => ({
  smsRules: [rule()],
  customParsers: [],
  accounts: [
    { id: 'hdfc', currency: 'INR', smsHint: '5678' },
    { id: 'enbd', currency: 'AED', smsHint: '1234' },
  ],
  dedupe: new DedupeIndex(),
  rules: [{ merchantKey: 'SWIGGY', categoryId: 'food' }],
  ...over,
});

describe('processSms (only configured SMS)', () => {
  it('turns a configured kind into a draft with card, category and SMS time', () => {
    const r = processSms({ sender: 'VM-HDFCBK', body: hdfc, receivedAt: t0 }, ctx());
    expect(r.kind).toBe('draft');
    if (r.kind !== 'draft') return;
    expect(r.draft).toMatchObject({
      amountMinor: 50000,
      accountId: 'hdfc',
      accountHint: '5678',
      categoryId: 'food',
      occurredAt: t0,
      duplicateOfId: null,
      parserId: 'rule:r1',
    });
  });

  it('matches the same bank under another operator prefix', () => {
    const r = processSms({ sender: 'AD-HDFCBK-S', body: hdfc.replace('500.00', '75.00'), receivedAt: t0 }, ctx());
    expect(r.kind === 'draft' && r.draft.amountMinor).toBe(7500);
  });

  it('ignores a real-looking transaction from a sender that was not set up', () => {
    const body = 'Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI.';
    expect(processSms({ sender: 'EmiratesNBD', body, receivedAt: t0 }, ctx())).toEqual({
      kind: 'ignored',
      reason: 'Not a configured bank SMS',
    });
  });

  it('ignores kinds the user chose to ignore', () => {
    const otp = '482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share.';
    const c = ctx({ smsRules: [rule(), rule({ id: 'r2', templateKey: templateKey(otp), action: 'ignore' })] });
    expect(processSms({ sender: 'VM-HDFCBK', body: otp, receivedAt: t0 }, c).kind).toBe('ignored');
  });

  it('flags unknown kinds with amounts from a configured bank as unread, but not OTPs', () => {
    const odd = 'Your HDFC card statement total is Rs 12,000.00. Pay by 20th.';
    expect(processSms({ sender: 'VM-HDFCBK', body: odd, receivedAt: t0 }, ctx())).toEqual({ kind: 'unparsed' });
    const otp = '111111 is your OTP for Rs 99.00';
    expect(processSms({ sender: 'VM-HDFCBK', body: otp, receivedAt: t0 }, ctx()).kind).toBe('ignored');
  });

  it('flags a second SMS for the same payment as a duplicate', () => {
    const dedupe = new DedupeIndex([
      { id: 'tx1', amountMinor: 50000, currency: 'INR', direction: 'debit', occurredAt: t0 - 2 * 60_000, accountId: 'hdfc' },
    ]);
    const r = processSms({ sender: 'VM-HDFCBK', body: hdfc, receivedAt: t0 }, ctx({ dedupe }));
    expect(r.kind === 'draft' && r.draft.duplicateOfId).toBe('tx1');
  });

  it('uses the fixed account for kinds that never name a card', () => {
    const body = 'Rs 250.00 spent on your card at ZOMATO.';
    const c = ctx({ smsRules: [rule({ templateKey: templateKey(body), fixedAccountId: 'enbd' })] });
    const r = processSms({ sender: 'VM-HDFCBK', body, receivedAt: t0 }, c);
    expect(r.kind === 'draft' && r.draft.accountId).toBe('enbd');
  });
});

describe('matchAccount', () => {
  const accounts = ctx().accounts;
  it('uses last digits first', () => expect(matchAccount('1234', 'INR', accounts)).toBe('enbd'));
  it('leaves unknown digits unlinked instead of guessing', () => expect(matchAccount('9999', 'AED', accounts)).toBeNull());
  it('falls back to the only account in that currency', () => expect(matchAccount(null, 'AED', accounts)).toBe('enbd'));
  it('gives up when ambiguous', () =>
    expect(matchAccount(null, 'INR', [...accounts, { id: 'sbi', currency: 'INR', smsHint: null }])).toBeNull());
});
