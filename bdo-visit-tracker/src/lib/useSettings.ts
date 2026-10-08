import { useLiveQuery } from 'dexie-react-hooks';
import { getSettings } from './repo';
import { db } from './db';

export function useSettings() {
  return useLiveQuery(() => db.kv.get('settings').then(() => getSettings()), []);
}
