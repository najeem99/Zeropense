import { buildRules, collectDigits, initialChoices, kindId, mergeSenders } from '../setup/state';
import { applyRule, groupMessages, senderKey, suggestKind, templateKey, type SmsRule } from '../templates';

const enbd = (amount: string, digits: string, merchant: string, minutes = 0) => ({
  sender: 'EmiratesNBD',
  body: `Purchase of AED ${amount} with Credit Card ending ${digits} at ${merchant}. Avl Cr. Limit is AED 9,000.00`,
  receivedAt: minutes * 60_000,
});
const otp = (code: string) => ({ sender: 'EmiratesNBD', body: `${code} is your OTP for online purchase of AED 20.00`, receivedAt: 0 });
const promo = { sender: 'EmiratesNBD', body: 'Get 10% cashback up to AED 200 on dining this weekend! Apply now.', receivedAt: 0 };

describe('senderKey / templateKey', () => {
  it('merges operator prefixes and suffixes', () => {
    expect(['VM-HDFCBK', 'AD-HDFCBK-S', 'HDFCBK'].map(senderKey)).toEqual(['HDFCBK', 'HDFCBK', 'HDFCBK']);
  });

  it('keys messages of one kind together regardless of amounts, cards and merchants', () => {
    expect(templateKey(enbd('45.50', '1234', 'CARREFOUR').body)).toBe(templateKey(enbd('1,200.00', '9876', 'NOON').body));
    expect(templateKey(enbd('45.50', '1234', 'X').body)).not.toBe(templateKey(otp('123456').body));
  });

  it('separates debited and credited messages that share an opening', () => {
    expect(templateKey('Your A/c XX1234 has been debited with INR 50.00')).not.toBe(
      templateKey('Your A/c XX1234 has been credited with INR 50.00'),
    );
  });
});

describe('suggestKind', () => {
  it('labels transactions, OTPs and promotions', () => {
    expect(suggestKind(enbd('45.50', '1234', 'CARREFOUR'))).toMatchObject({
      label: 'transaction',
      amountIndex: 0,
      direction: 'debit',
      currency: 'AED',
    });
    expect(suggestKind(otp('123456')).label).toBe('otp');
    expect(suggestKind(promo).label).toBe('promo');
  });
});

describe('groupMessages', () => {
  it('groups by sender then kind, biggest kind first, with card digits', () => {
    const groups = groupMessages([
      enbd('45.50', '1234', 'CARREFOUR'),
      otp('111111'),
      enbd('12.00', '9876', 'NOON', 5),
      enbd('3.00', '1234', 'LULU', 9),
      promo,
    ]);
    expect(groups).toHaveLength(1);
    const [txKind, ...rest] = groups[0].kinds;
    expect(txKind.messages).toHaveLength(3);
    expect(txKind.digits.sort()).toEqual(['1234', '9876']);
    expect(rest.map((k) => k.suggestion.label).sort()).toEqual(['otp', 'promo']);
  });
});

describe('applyRule', () => {
  const rule: SmsRule = {
    id: 'r',
    senderKey: 'EMIRATESNBD',
    templateKey: templateKey(enbd('1', '1', 'X').body),
    action: 'import',
    amountIndex: 0,
    direction: 'debit',
    currency: 'AED',
    fixedAccountId: null,
    sample: '',
  };

  it('reads new merchants and amounts by position, skipping the balance', () => {
    expect(applyRule(rule, enbd('1,250.75', '4321', 'SHARAF DG'))).toMatchObject({
      amountMinor: 125075,
      currency: 'AED',
      merchant: 'SHARAF DG',
      accountHint: '4321',
    });
  });

  it('takes the second amount when the user picked it', () => {
    expect(applyRule({ ...rule, amountIndex: 1 }, enbd('1.00', '4321', 'X'))?.amountMinor).toBe(900000);
  });
});

describe('setup state', () => {
  it('drops personal numbers and pre-ticks bank-like senders', () => {
    const senders = mergeSenders([
      { sender: 'VM-HDFCBK', count: 40, moneyCount: 30, lastDate: 0 },
      { sender: 'AD-HDFCBK', count: 10, moneyCount: 5, lastDate: 0 },
      { sender: '+971501234567', count: 99, moneyCount: 9, lastDate: 0 },
      { sender: 'NOON', count: 50, moneyCount: 2, lastDate: 0 },
    ]);
    expect(senders.map((s) => [s.key, s.count, s.suggested])).toEqual([
      ['HDFCBK', 50, true],
      ['NOON', 50, false],
    ]);
  });

  it('turns choices into import and ignore rules, collecting digits only from imported kinds', () => {
    const groups = groupMessages([enbd('45.50', '1234', 'CARREFOUR'), otp('111111')]);
    const choices = initialChoices(groups);
    const g = groups[0];
    expect(collectDigits(groups, choices).map((d) => d.digits)).toEqual(['1234']);

    const rules = buildRules(groups, choices, {});
    expect(rules.map((r) => r.action).sort()).toEqual(['ignore', 'import']);
    expect(choices[kindId(g.senderKey, g.kinds[0].templateKey)].include).toBe(true);
  });
});
