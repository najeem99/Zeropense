import type { ExtractHints } from './extract';

export type BankProfile = ExtractHints & {
  id: string;
  name: string;
  /** Matched against the SMS sender, e.g. "VM-HDFCBK". */
  sender: RegExp;
};

/**
 * Built-in profiles for major Indian banks. Bank SMS formats change often and differ
 * by product (UPI, card, NEFT), so these only add bank-specific merchant/amount patterns
 * on top of the generic extractor. Formats we miss are covered by user custom parsers.
 */
export const BANK_PROFILES: BankProfile[] = [
  {
    id: 'hdfc',
    name: 'HDFC Bank',
    sender: /HDFC/i,
    defaultCurrency: 'INR',
    // "Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 11/10/26"
    merchant: [/^\s*To\s+([^\n]+?)\s*$/im],
  },
  {
    id: 'icici',
    name: 'ICICI Bank',
    sender: /ICICI/i,
    defaultCurrency: 'INR',
    // "ICICI Bank Acct XX123 debited for Rs 250.00 on 11-Oct-26; SWIGGY credited."
    merchant: [/;\s*([^;.]+?)\s+credited/i],
  },
  {
    id: 'sbi',
    name: 'State Bank of India',
    sender: /SBI/i,
    defaultCurrency: 'INR',
    // "A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 123"
    amount: [/(?:debited|credited) by\s*(\d[\d,]*(?:\.\d{1,2})?)/i],
    merchant: [/\btrf to\s+(.+?)\s+Ref/i, /\btransfer from\s+(.+?)\s+Ref/i],
  },
  {
    id: 'axis',
    name: 'Axis Bank',
    sender: /AXIS/i,
    defaultCurrency: 'INR',
    // "INR 500.00 debited A/c no. XX1234 11-10-26 14:05:21 UPI/P2M/123456/SWIGGY"
    merchant: [/UPI\/P2[AM]\/\d+\/([^\s/]+)/i],
  },
  {
    id: 'kotak',
    name: 'Kotak Mahindra Bank',
    sender: /KOTAK|KMBL/i,
    defaultCurrency: 'INR',
    // "Sent Rs.120.00 from Kotak Bank AC X1234 to swiggy@ybl on 11-10-26."
    merchant: [/\bto\s+([\w.\-]+@[\w]+)/i],
  },
];

export function profileForSender(sender: string): BankProfile | null {
  return BANK_PROFILES.find((p) => p.sender.test(sender)) ?? null;
}
