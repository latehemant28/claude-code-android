import Dexie, { type Table } from 'dexie';
import type { Attachment, Department, FollowUp, Official, OpportunityType, Visit } from './types';

export interface KV {
  key: string;
  value: unknown;
}

/**
 * IndexedDB database (via Dexie). This is the source of truth; Excel files are generated
 * from it on demand. IndexedDB survives app restarts and page refreshes; the app also asks
 * the browser for persistent storage so the data is not evicted under storage pressure.
 */
export class BdoDatabase extends Dexie {
  visits!: Table<Visit, number>;
  followUps!: Table<FollowUp, number>;
  departments!: Table<Department, number>;
  officials!: Table<Official, number>;
  opportunityTypes!: Table<OpportunityType, number>;
  attachments!: Table<Attachment, number>;
  kv!: Table<KV, string>;

  constructor(name = 'bdo-visit-tracker') {
    super(name);
    this.version(1).stores({
      visits: '++id, visitDate, [visitDate+visitTime], departmentName, zone, status, *opportunities, createdAt, updatedAt',
      followUps: '++id, visitId, dueDate, status, [status+dueDate]',
      departments: '++id, &key, name, useCount',
      officials: '++id, &key, name, departmentName, useCount',
      opportunityTypes: '++id, &key, useCount',
      attachments: '++id, visitId',
      kv: 'key',
    });
  }
}

export let db = new BdoDatabase();

/** Test hook: swap in a fresh database. */
export function useDatabase(next: BdoDatabase) {
  db = next;
}

export async function requestPersistentStorage(): Promise<boolean | null> {
  try {
    if (navigator.storage?.persist) {
      if (await navigator.storage.persisted()) return true;
      return await navigator.storage.persist();
    }
  } catch {
    /* not supported */
  }
  return null;
}
