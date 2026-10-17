export type InboxMessage = {
  sender: string;
  body: string;
  /** Receive time, epoch ms. */
  date: number;
};

export type InboxSender = {
  sender: string;
  count: number;
  /** Messages with a currency next to a number. */
  moneyCount: number;
  lastDate: number;
};
