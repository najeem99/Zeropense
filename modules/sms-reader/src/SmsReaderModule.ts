import { NativeModule, requireOptionalNativeModule } from 'expo';

import type { InboxMessage, InboxSender } from './SmsReader.types';

declare class SmsReaderModule extends NativeModule<{}> {
  hasPermission(): boolean;
  listSenders(sinceMs: number): Promise<InboxSender[]>;
  readInbox(afterMs: number, limit: number, senders: string[] | null): Promise<InboxMessage[]>;
}

/** null in Expo Go and on iOS/web, where the native module isn't compiled in. */
export default requireOptionalNativeModule<SmsReaderModule>('SmsReader');
