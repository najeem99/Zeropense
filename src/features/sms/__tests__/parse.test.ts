import { findMerchant, smsSnippet } from '../extract';
import { skipReason } from '../filters';
import { parseSms } from '../parse';

const at = new Date(2026, 9, 11, 12, 0).getTime();
const sms = (sender: string, body: string) => ({ sender, body, receivedAt: at });

describe('parseSms', () => {
  it('parses a UAE card purchase and ignores the available limit amount', () => {
    const r = parseSms(
      sms(
        'EmiratesNBD',
        'Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00',
      ),
      [],
    );
    expect(r).toMatchObject({
      amountMinor: 4550,
      currency: 'AED',
      direction: 'debit',
      merchant: 'CARREFOUR MOE',
      accountHint: '1234',
      parserId: 'generic',
    });
  });

  it('parses an HDFC multi-line UPI message', () => {
    const r = parseSms(
      sms('VM-HDFCBK', 'Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nOn 11/10/26\nRef 123456789\nNot You?'),
      [],
    );
    expect(r).toMatchObject({
      amountMinor: 50000,
      currency: 'INR',
      direction: 'debit',
      merchant: 'SWIGGY',
      accountHint: '5678',
      parserId: 'bank:hdfc',
    });
    expect(new Date(r!.occurredAt!).toDateString()).toBe(new Date(2026, 9, 11).toDateString());
  });

  it('parses ICICI where the payee appears before "credited"', () => {
    const r = parseSms(
      sms('AD-ICICIB', 'ICICI Bank Acct XX123 debited for Rs 250.00 on 11-Oct-26; GAZEBO credited. UPI:123456. Call 18002662.'),
      [],
    );
    expect(r).toMatchObject({ amountMinor: 25000, direction: 'debit', merchant: 'GAZEBO', parserId: 'bank:icici' });
  });

  it('parses SBI amounts written without a currency', () => {
    const r = parseSms(
      sms('JD-SBIUPI', 'Dear UPI user A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 612345678901'),
      [],
    );
    expect(r).toMatchObject({ amountMinor: 15000, currency: 'INR', direction: 'debit', merchant: 'ZOMATO' });
  });

  it('parses Axis UPI merchant from the UPI reference', () => {
    const r = parseSms(
      sms('AX-AXISBK', 'INR 1,250.00 debited A/c no. XX4321 11-10-26 14:05:21 UPI/P2M/612345/AMAZON Not you? SMS BLOCK'),
      [],
    );
    expect(r).toMatchObject({ amountMinor: 125000, merchant: 'AMAZON', accountHint: '4321' });
  });

  it('treats salary as a credit', () => {
    const r = parseSms(
      sms('ADCB', 'Your account XXX9876 has been credited with AED 15,000.00 from ACME TRADING on 01/10/2026.'),
      [],
    );
    expect(r).toMatchObject({ amountMinor: 1500000, direction: 'credit', merchant: 'ACME TRADING' });
  });

  it('returns null when there is no amount', () => {
    expect(parseSms(sms('VM-HDFCBK', 'Your card has been dispatched.'), [])).toBeNull();
  });
});

describe('skipReason', () => {
  it.each([
    ['123456 is your OTP for txn of Rs 500 at AMAZON. Do not share.', 'OTP / verification code'],
    ['ABC has requested money from you on Google Pay. Rs 200.', 'Payment request, not a payment'],
    ['Rs 999 will be debited from your account on 15-10-26 for NETFLIX.', 'Upcoming debit reminder'],
    ['Txn of AED 30 at UBER declined due to insufficient funds.', 'Failed or declined'],
  ])('skips %s', (body, reason) => {
    expect(skipReason(body)).toBe(reason);
  });

  it('keeps a normal debit', () => {
    expect(skipReason('Rs 500 debited from A/C XX12 to SWIGGY')).toBeNull();
  });
});

describe('merchant names', () => {
  it('reads labelled merchant fields', () => {
    expect(findMerchant('Card XX1234 Purchase, Amount: AED 12.00, Merchant: AMRK AND SAVE, Date 12/10/26', 'debit')).toBe(
      'AMRK AND SAVE',
    );
    expect(findMerchant('AED 8.50 spent on card 1234. Location: LULU HYPERMARKET. Avl bal AED 99', 'debit')).toBe(
      'LULU HYPERMARKET',
    );
  });

  it('falls back to a short snippet without numbers when no merchant is found', () => {
    expect(smsSnippet('Your card 1234 was charged AED 45.00 today')).toBe('Your card was charged today');
    expect(smsSnippet('Purchase of AED 45.50 with card at CARREFOUR, DUBAI.')).toBe('CARREFOUR');
  });
});
