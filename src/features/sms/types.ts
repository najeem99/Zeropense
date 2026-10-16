import type { Currency, Direction } from '@/lib/money';

export type RawSms = {
  sender: string;
  body: string;
  receivedAt: number;
};

/** What a parser extracts from one SMS. Fields it can't find are null. */
export type ParsedSms = {
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  merchant: string | null;
  /** Last digits of the account or card, used to pick the account. */
  accountHint: string | null;
  occurredAt: number | null;
  /** Which parser produced this: "custom:<id>", "bank:<id>" or "generic". */
  parserId: string;
};
