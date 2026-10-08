import { db } from './db';
import { getSettings, saveSettings } from './repo';
import type { Attachment, Department, FollowUp, Official, OpportunityType, Settings, Visit } from './types';

export const BACKUP_FORMAT = 'bdo-visit-tracker-backup';
export const BACKUP_VERSION = 1;

interface SerialAttachment extends Omit<Attachment, 'data'> {
  dataBase64: string;
}

export interface Backup {
  format: typeof BACKUP_FORMAT;
  version: number;
  exportedAt: string;
  settings: Settings;
  visits: Visit[];
  followUps: FollowUp[];
  departments: Department[];
  officials: Official[];
  opportunityTypes: OpportunityType[];
  attachments: SerialAttachment[];
}

async function blobToBase64(b: Blob): Promise<string> {
  const bytes = new Uint8Array(await b.arrayBuffer());
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
function base64ToBlob(b64: string, type: string): Blob {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type });
}

export async function createBackup(includeAttachments = true): Promise<Backup> {
  const attachments = includeAttachments ? await db.attachments.toArray() : [];
  return {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    exportedAt: new Date().toISOString(),
    settings: await getSettings(),
    visits: await db.visits.toArray(),
    followUps: await db.followUps.toArray(),
    departments: await db.departments.toArray(),
    officials: await db.officials.toArray(),
    opportunityTypes: await db.opportunityTypes.toArray(),
    attachments: await Promise.all(
      attachments.map(async ({ data, ...a }) => ({ ...a, dataBase64: await blobToBase64(data) })),
    ),
  };
}

export function parseBackup(text: string): Backup {
  let b: Backup;
  try {
    b = JSON.parse(text);
  } catch {
    throw new Error('This file is not a valid backup (it is not JSON). Choose a file exported from Settings > Backup.');
  }
  if (b?.format !== BACKUP_FORMAT) throw new Error('This file is not a BDO Visit Tracker backup.');
  if (b.version > BACKUP_VERSION) throw new Error('This backup was made by a newer version of the app. Update the app first.');
  if (!Array.isArray(b.visits) || !Array.isArray(b.followUps)) throw new Error('The backup file is incomplete or damaged.');
  for (const v of b.visits) {
    if (typeof v.departmentName !== 'string' || typeof v.visitDate !== 'string' || !Array.isArray(v.opportunities))
      throw new Error('The backup contains a damaged visit record; nothing was imported.');
  }
  return b;
}

const visitFingerprint = (v: Visit) => `${v.createdAt}|${v.visitDate}|${v.visitTime}|${v.departmentName.toLowerCase()}`;

/**
 * Restores a backup.
 *  - mode "merge": adds visits not already present (matched by creation time, date, time and
 *    department), keeping everything currently on the device.
 *  - mode "replace": wipes the device data first, then restores the backup exactly.
 * Runs in one transaction: either everything is restored or nothing changes.
 */
export async function restoreBackup(b: Backup, mode: 'merge' | 'replace'): Promise<{ added: number; skipped: number }> {
  let added = 0;
  let skipped = 0;
  await db.transaction('rw', [db.visits, db.followUps, db.departments, db.officials, db.opportunityTypes, db.attachments, db.kv], async () => {
    if (mode === 'replace') {
      await Promise.all([db.visits.clear(), db.followUps.clear(), db.departments.clear(), db.officials.clear(), db.opportunityTypes.clear(), db.attachments.clear()]);
    }
    const existing = new Set((await db.visits.toArray()).map(visitFingerprint));
    const idMap = new Map<number, number>();
    for (const v of b.visits) {
      if (existing.has(visitFingerprint(v))) {
        skipped++;
        continue;
      }
      const { id, ...rest } = v;
      const newId = await db.visits.add(mode === 'replace' ? { ...rest, id } : rest);
      idMap.set(id!, newId);
      added++;
    }
    for (const f of b.followUps) {
      const vid = idMap.get(f.visitId);
      if (vid === undefined) continue;
      const { id, ...rest } = f;
      await db.followUps.add(mode === 'replace' ? { ...rest, id, visitId: vid } : { ...rest, visitId: vid });
    }
    for (const a of b.attachments ?? []) {
      const vid = idMap.get(a.visitId);
      if (vid === undefined) continue;
      const { id: _id, dataBase64, ...rest } = a;
      void _id;
      await db.attachments.add({ ...rest, visitId: vid, data: base64ToBlob(dataBase64, a.type) });
    }
    for (const [table, rows] of [
      [db.departments, b.departments ?? []],
      [db.officials, b.officials ?? []],
      [db.opportunityTypes, b.opportunityTypes ?? []],
    ] as const) {
      for (const row of rows as { id?: number; key: string }[]) {
        const { id: _id, ...rest } = row;
        void _id;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const t = table as any;
        if (!(await t.where('key').equals(rest.key).first())) await t.add(rest);
      }
    }
    if (mode === 'replace' && b.settings) await saveSettings(b.settings);
  });
  return { added, skipped };
}
