import { useSQLiteContext, type SQLiteDatabase } from 'expo-sqlite';
import { useEffect, useState, useSyncExternalStore } from 'react';

let version = 0;
const listeners = new Set<() => void>();

/** Call after any write so mounted queries re-run. */
export function notifyDbChanged(): void {
  version++;
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Runs `query` now and again after every write. `key` must change when the
 * query's inputs change (e.g. the selected month).
 */
export function useDbQuery<T>(query: (db: SQLiteDatabase) => Promise<T>, key: string = ''): T | undefined {
  const db = useSQLiteContext();
  const dbVersion = useSyncExternalStore(subscribe, () => version);
  const [state, setState] = useState<{ key: string; data: T } | undefined>();

  useEffect(() => {
    let cancelled = false;
    query(db).then(
      (data) => !cancelled && setState({ key, data }),
      (err) => console.error('Query failed', err),
    );
    return () => {
      cancelled = true;
    };
    // `query` is usually an inline arrow; `key` stands in for its inputs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [db, dbVersion, key]);

  // Don't show the previous month's rows while the new month loads.
  return state && state.key === key ? state.data : undefined;
}
