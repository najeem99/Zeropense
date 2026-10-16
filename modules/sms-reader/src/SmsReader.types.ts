export type InboxMessage = {
  sender: string;
  body: string;
  /** Receive time, epoch ms. */
  date: number;
};
