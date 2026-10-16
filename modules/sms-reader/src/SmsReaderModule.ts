import { NativeModule, requireOptionalNativeModule } from 'expo';

import type { InboxMessage } from './SmsReader.types';

declare class SmsReaderModule extends NativeModule<{}> {
  hasPermission(): boolean;
  readInbox(afterMs: number, limit: number): Promise<InboxMessage[]>;
}

/** null in Expo Go and on iOS/web, where the native module isn't compiled in. */
export default requireOptionalNativeModule<SmsReaderModule>('SmsReader');
