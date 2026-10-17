/** The user's real bank SMS (ENBD-style, UAE). Each must be read correctly end to end. */
import { DedupeIndex } from '@/features/transactions/dedupe';

import { processSms, type IngestContext } from '../ingest';
import { buildRules, initialChoices } from '../setup/state';
import { groupMessages, templateKey, type SmsRule } from '../templates';

const SAMPLES = {
  purchase:
    'Purchase of AED 12.00 with Credit Card ending 9157 at GLOBALFASHION.AE, +971521253808. Avl Cr. Limit is AED 6,988.22',
  refund:
    'Purchase amount of AED 12.00 at GLOBALFASHION.AE on your Credit Card ending 9157 has been refunded to your card account. Avl Limit is AED 7,000.22.',
  fuel: ' Credit Card Purchase\nCard Ending: 1122\nAt: ADNOC AL BUHAIRA 176, SHARJAH\nAmount: AED 124.52\nDate: 15/10/2026, 19:12\nAvailable Limit: AED 6,754.45',
  salary:
    ' Salary Deposited \nAmount: AED 1234.00\nAccount: 370XXX08XXX01\nDate: 26/09/2026 11:39 \nAvailable Balance: AED 1234.60',
  transfer: 'AED 500.00 has been credited to your account XXX4567 from JOHN SMITH via Aani. Avl Bal AED 2,000.00',
};

const at = new Date(2026, 9, 16, 12, 0).getTime();
const msg = (body: string, minutes = 0) => ({ sender: 'EmiratesNBD', body, receivedAt: at + minutes * 60_000 });

/** What the setup would save if the user accepted its suggestions for these messages. */
function setupRules(bodies: string[]): SmsRule[] {
  const groups = groupMessages(bodies.map((b, i) => msg(b, i)));
  return buildRules(groups, initialChoices(groups), {}).map((r, i) => ({ ...r, id: `r${i}` }));
}

function read(body: string, rules: SmsRule[]) {
  const ctx: IngestContext = {
    smsRules: rules,
    customParsers: [],
    accounts: [
      { id: 'card9157', currency: 'AED', smsHint: '9157' },
      { id: 'card1122', currency: 'AED', smsHint: '1122' },
    ],
    dedupe: new DedupeIndex(),
    rules: [],
  };
  const r = processSms(msg(body, 999), ctx);
  if (r.kind !== 'draft') throw new Error(`not read: ${JSON.stringify(r)}`);
  return r.draft;
}

describe('real SMS samples', () => {
  const rules = setupRules(Object.values(SAMPLES));

  it('reads a card purchase, keeping the dot in the merchant name', () => {
    expect(read(SAMPLES.purchase, rules)).toMatchObject({
      amountMinor: 1200,
      direction: 'debit',
      merchant: 'GLOBALFASHION.AE',
      accountId: 'card9157',
    });
  });

  it('reads a refund as money in, in the Refund category', () => {
    expect(read(SAMPLES.refund, rules)).toMatchObject({
      amountMinor: 1200,
      direction: 'credit',
      accountId: 'card9157',
      categoryId: 'refund',
    });
  });

  it('reads a "Credit Card Purchase" as money out, with the labelled merchant and card', () => {
    expect(read(SAMPLES.fuel, rules)).toMatchObject({
      amountMinor: 12452,
      direction: 'debit',
      merchant: 'ADNOC AL BUHAIRA 176',
      accountHint: '1122',
      accountId: 'card1122',
    });
  });

  it('reads salary as income named Salary in the Salary category', () => {
    expect(read(SAMPLES.salary, rules)).toMatchObject({
      amountMinor: 123400,
      direction: 'credit',
      merchant: 'Salary',
      categoryId: 'salary',
    });
  });

  it('reads money other people send as income in Money received', () => {
    expect(read(SAMPLES.transfer, rules)).toMatchObject({
      amountMinor: 50000,
      direction: 'credit',
      merchant: 'JOHN SMITH',
      categoryId: 'transfer-in',
    });
  });

  it('groups refunds from different shops as one kind', () => {
    const other = SAMPLES.refund.replace('GLOBALFASHION.AE', 'NOON.COM');
    expect(templateKey(other)).toBe(templateKey(SAMPLES.refund));
    expect(read(other, rules)).toMatchObject({ direction: 'credit', categoryId: 'refund' });
  });

  it('suggests all five kinds as transactions in the setup', () => {
    const groups = groupMessages(Object.values(SAMPLES).map((b) => msg(b)));
    expect(groups[0].kinds.every((k) => k.suggestion.label === 'transaction')).toBe(true);
    expect(groups[0].kinds).toHaveLength(5);
  });
});
