import { db } from './db';
import { addDays, monthRange, todayISO } from './dates';
import { normaliseKey, normaliseVisit, validateVisit, ValidationError } from './validation';
import {
  DEFAULT_SETTINGS,
  type Attachment,
  type Department,
  type FollowUp,
  type FollowUpDraft,
  type Official,
  type OpportunityType,
  type Settings,
  type Visit,
  type VisitInput,
  type VisitStatus,
} from './types';

const nowTs = () => new Date().toISOString();

// ---------------------------------------------------------------- settings

export async function getSettings(): Promise<Settings> {
  const row = await db.kv.get('settings');
  return { ...DEFAULT_SETTINGS, ...((row?.value as Partial<Settings>) ?? {}) };
}

export async function saveSettings(s: Partial<Settings>): Promise<Settings> {
  const next = { ...(await getSettings()), ...s };
  await db.kv.put({ key: 'settings', value: next });
  return next;
}

export async function getKV<T>(key: string): Promise<T | undefined> {
  return (await db.kv.get(key))?.value as T | undefined;
}
export async function setKV(key: string, value: unknown) {
  await db.kv.put({ key, value });
}
export async function deleteKV(key: string) {
  await db.kv.delete(key);
}

// ---------------------------------------------------------------- master data (suggestions)

async function upsertDepartment(v: VisitInput): Promise<number> {
  const key = normaliseKey(v.departmentName);
  const existing = await db.departments.where('key').equals(key).first();
  if (existing) {
    await db.departments.update(existing.id!, {
      name: v.departmentName, zone: v.zone, branch: v.branch, useCount: existing.useCount + 1, lastUsedAt: nowTs(),
    });
    return existing.id!;
  }
  return db.departments.add({ name: v.departmentName, key, zone: v.zone, branch: v.branch, useCount: 1, lastUsedAt: nowTs() });
}

async function upsertOfficial(v: VisitInput): Promise<number> {
  const key = `${normaliseKey(v.departmentName)}|${normaliseKey(v.officialName)}`;
  const existing = await db.officials.where('key').equals(key).first();
  if (existing) {
    await db.officials.update(existing.id!, {
      name: v.officialName,
      designation: v.officialDesignation,
      departmentName: v.departmentName,
      contactNumber: v.contactNumber || existing.contactNumber,
      useCount: existing.useCount + 1,
      lastUsedAt: nowTs(),
    });
    return existing.id!;
  }
  return db.officials.add({
    name: v.officialName, designation: v.officialDesignation, departmentName: v.departmentName,
    contactNumber: v.contactNumber, key, useCount: 1, lastUsedAt: nowTs(),
  });
}

async function upsertOpportunityTypes(names: string[]) {
  for (const name of names) {
    const key = normaliseKey(name);
    const existing = await db.opportunityTypes.where('key').equals(key).first();
    if (existing) await db.opportunityTypes.update(existing.id!, { useCount: existing.useCount + 1 });
    else await db.opportunityTypes.add({ name, key, useCount: 1 });
  }
}

export const listDepartments = (): Promise<Department[]> => db.departments.orderBy('useCount').reverse().toArray();
export const listOfficials = (): Promise<Official[]> => db.officials.orderBy('useCount').reverse().toArray();
export const listOpportunityTypes = (): Promise<OpportunityType[]> => db.opportunityTypes.orderBy('useCount').reverse().toArray();

// ---------------------------------------------------------------- visits

export interface SaveResult {
  visit: Visit;
  followUps: FollowUp[];
}

/**
 * Creates (no id) or updates (id) a visit together with its follow-ups, atomically.
 * Follow-up drafts with an id update that follow-up; drafts without one are added; existing
 * pending follow-ups of the visit missing from `followUps` are removed. Completed follow-ups
 * are never removed this way.
 */
export async function saveVisit(raw: VisitInput, followUps: FollowUpDraft[], id?: number): Promise<SaveResult> {
  const input = normaliseVisit(raw);
  // A new visit to a known department uses its saved spelling, so the report never lists one
  // department under two spellings ("Collectorate Raipur" / "collectorate raipur").
  if (id === undefined && input.departmentName) {
    const known = await db.departments.where('key').equals(normaliseKey(input.departmentName)).first();
    if (known) input.departmentName = known.name;
  }
  const drafts = followUps
    .map((f) => ({ ...f, action: f.action.trim(), responsible: f.responsible.trim() }))
    .filter((f) => f.action || f.dueDate);
  const errors = validateVisit(input, drafts, todayISO((await getSettings()).timeZone));
  if (Object.keys(errors).length) throw new ValidationError(errors);

  return db.transaction('rw', [db.visits, db.followUps, db.departments, db.officials, db.opportunityTypes, db.kv], async () => {
    const ts = nowTs();
    const departmentId = await upsertDepartment(input);
    const officialId = await upsertOfficial(input);
    let visitId: number;
    if (id !== undefined) {
      const old = await db.visits.get(id);
      if (!old) throw new Error('This visit no longer exists. It may have been deleted on another screen.');
      const added = input.opportunities.filter((o) => !old.opportunities.some((p) => normaliseKey(p) === normaliseKey(o)));
      await upsertOpportunityTypes(added);
      await db.visits.put({ ...input, id, departmentId, officialId, createdAt: old.createdAt, updatedAt: ts });
      visitId = id;
    } else {
      await upsertOpportunityTypes(input.opportunities);
      visitId = await db.visits.add({ ...input, departmentId, officialId, createdAt: ts, updatedAt: ts });
    }

    const existing = await db.followUps.where('visitId').equals(visitId).toArray();
    const keepIds = new Set(drafts.filter((d) => d.id).map((d) => d.id));
    for (const f of existing) if (!keepIds.has(f.id) && f.status === 'Pending') await db.followUps.delete(f.id!);
    for (const d of drafts) {
      if (d.id) {
        const cur = existing.find((f) => f.id === d.id);
        if (cur) await db.followUps.update(d.id, { action: d.action, responsible: d.responsible, dueDate: d.dueDate, updatedAt: ts });
      } else {
        await db.followUps.add({
          visitId, action: d.action, responsible: d.responsible, dueDate: d.dueDate, status: 'Pending',
          completionNotes: '', result: '', rescheduleHistory: [], createdAt: ts, updatedAt: ts,
        });
      }
    }
    return {
      visit: (await db.visits.get(visitId))!,
      followUps: await db.followUps.where('visitId').equals(visitId).toArray(),
    };
  });
}

export const getVisit = (id: number) => db.visits.get(id);

export async function deleteVisit(id: number) {
  await db.transaction('rw', [db.visits, db.followUps, db.attachments], async () => {
    await db.followUps.where('visitId').equals(id).delete();
    await db.attachments.where('visitId').equals(id).delete();
    await db.visits.delete(id);
  });
}

/** Other visits to the same department on the same date (a possible accidental double entry). */
export async function findPossibleDuplicates(v: Pick<Visit, 'departmentName' | 'visitDate'>, excludeId?: number): Promise<Visit[]> {
  const key = normaliseKey(v.departmentName);
  if (!key || !v.visitDate) return [];
  const sameDay = await db.visits.where('visitDate').equals(v.visitDate).toArray();
  return sameDay.filter((x) => x.id !== excludeId && normaliseKey(x.departmentName) === key);
}

export type FollowUpFilter = 'any' | 'open' | 'overdue' | 'dueToday' | 'none';

export interface VisitFilter {
  q?: string;
  from?: string;
  to?: string;
  zone?: string;
  department?: string;
  opportunity?: string;
  status?: VisitStatus | '';
  followUp?: FollowUpFilter;
  ids?: number[];
  sort?: 'desc' | 'asc';
}

export const visitSortKey = (v: Visit) => `${v.visitDate} ${v.visitTime}`;

export function sortVisits(list: Visit[], dir: 'asc' | 'desc' = 'desc') {
  return list.sort((a, b) => {
    const c = visitSortKey(a).localeCompare(visitSortKey(b)) || (a.id ?? 0) - (b.id ?? 0);
    return dir === 'asc' ? c : -c;
  });
}

export async function listVisits(f: VisitFilter = {}): Promise<Visit[]> {
  let list: Visit[];
  if (f.ids) list = (await db.visits.bulkGet(f.ids)).filter((v): v is Visit => !!v);
  else if (f.from || f.to) list = await db.visits.where('visitDate').between(f.from || '0000-00-00', f.to || '9999-99-99', true, true).toArray();
  else list = await db.visits.toArray();

  if (f.ids && (f.from || f.to)) list = list.filter((v) => (!f.from || v.visitDate >= f.from) && (!f.to || v.visitDate <= f.to));
  if (f.zone) list = list.filter((v) => normaliseKey(v.zone) === normaliseKey(f.zone!));
  if (f.department) list = list.filter((v) => normaliseKey(v.departmentName) === normaliseKey(f.department!));
  if (f.opportunity) {
    const k = normaliseKey(f.opportunity);
    list = list.filter((v) => v.opportunities.some((o) => normaliseKey(o) === k));
  }
  if (f.status) list = list.filter((v) => v.status === f.status);
  if (f.q?.trim()) {
    const terms = normaliseKey(f.q).split(' ');
    list = list.filter((v) => {
      const hay = [v.departmentName, v.officialName, v.officialDesignation, v.remarks, v.zone, v.branch, v.location, v.purpose, ...v.opportunities]
        .join(' \n ')
        .toLowerCase();
      return terms.every((t) => hay.includes(t));
    });
  }
  if (f.followUp && f.followUp !== 'any') {
    const today = todayISO((await getSettings()).timeZone);
    const fus = await db.followUps.toArray();
    const byVisit = new Map<number, FollowUp[]>();
    for (const x of fus) byVisit.set(x.visitId, [...(byVisit.get(x.visitId) ?? []), x]);
    list = list.filter((v) => {
      const mine = (byVisit.get(v.id!) ?? []).filter((x) => x.status === 'Pending');
      switch (f.followUp) {
        case 'open': return mine.length > 0;
        case 'overdue': return mine.some((x) => x.dueDate < today);
        case 'dueToday': return mine.some((x) => x.dueDate === today);
        case 'none': return mine.length === 0;
        default: return true;
      }
    });
  }
  return sortVisits(list, f.sort ?? 'desc');
}

export async function distinctValues(): Promise<{ zones: string[]; departments: string[]; opportunities: string[] }> {
  const visits = await db.visits.toArray();
  const uniq = (xs: string[]) => {
    const m = new Map<string, string>();
    for (const x of xs) if (x && !m.has(normaliseKey(x))) m.set(normaliseKey(x), x);
    return [...m.values()].sort((a, b) => a.localeCompare(b));
  };
  return {
    zones: uniq(visits.map((v) => v.zone)),
    departments: uniq(visits.map((v) => v.departmentName)),
    opportunities: uniq(visits.flatMap((v) => v.opportunities)),
  };
}

// ---------------------------------------------------------------- follow-ups

export const followUpsForVisit = (visitId: number) => db.followUps.where('visitId').equals(visitId).sortBy('dueDate');

export async function addFollowUp(visitId: number, d: FollowUpDraft) {
  if (!d.action.trim()) throw new ValidationError({ action: 'Describe the follow-up action.' });
  if (!d.dueDate) throw new ValidationError({ dueDate: 'Choose a due date.' });
  const ts = nowTs();
  return db.followUps.add({
    visitId, action: d.action.trim(), responsible: d.responsible.trim(), dueDate: d.dueDate, status: 'Pending',
    completionNotes: '', result: '', rescheduleHistory: [], createdAt: ts, updatedAt: ts,
  });
}

export async function completeFollowUp(id: number, completionNotes: string, result: string) {
  const ts = nowTs();
  await db.followUps.update(id, { status: 'Completed', completionNotes: completionNotes.trim(), result: result.trim(), completedAt: ts, updatedAt: ts });
}

export async function reopenFollowUp(id: number) {
  await db.followUps.update(id, { status: 'Pending', completedAt: undefined, updatedAt: nowTs() });
}

export async function rescheduleFollowUp(id: number, newDate: string, reason: string) {
  const f = await db.followUps.get(id);
  if (!f) throw new Error('Follow-up not found.');
  if (!newDate) throw new ValidationError({ dueDate: 'Choose the new due date.' });
  const ts = nowTs();
  await db.followUps.update(id, {
    dueDate: newDate,
    status: 'Pending',
    rescheduleHistory: [...f.rescheduleHistory, { from: f.dueDate, to: newDate, reason: reason.trim(), at: ts }],
    updatedAt: ts,
  });
}

export const deleteFollowUp = (id: number) => db.followUps.delete(id);

export interface FollowUpRow extends FollowUp {
  visit?: Visit;
}

export async function listFollowUpsWithVisits(): Promise<FollowUpRow[]> {
  const fus = await db.followUps.orderBy('dueDate').toArray();
  const visits = await db.visits.bulkGet([...new Set(fus.map((f) => f.visitId))]);
  const byId = new Map(visits.filter(Boolean).map((v) => [v!.id!, v!]));
  return fus.map((f) => ({ ...f, visit: byId.get(f.visitId) }));
}

// ---------------------------------------------------------------- attachments

export const listAttachments = (visitId: number) => db.attachments.where('visitId').equals(visitId).toArray();
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

export async function addAttachment(visitId: number, file: File): Promise<number> {
  if (file.size > MAX_ATTACHMENT_BYTES) throw new Error(`"${file.name}" is larger than 10 MB. Compress it or attach a smaller file.`);
  const a: Attachment = { visitId, name: file.name, type: file.type || 'application/octet-stream', size: file.size, data: file, createdAt: nowTs() };
  return db.attachments.add(a);
}
export const deleteAttachment = (id: number) => db.attachments.delete(id);

// ---------------------------------------------------------------- dashboard metrics

export interface Metrics {
  visitsToday: number;
  visitsThisMonth: number;
  visitsInPeriod: number;
  departmentsInPeriod: number;
  opportunitiesInPeriod: number;
  followUpsDueToday: number;
  followUpsOverdue: number;
  accountsSourcedInPeriod: number;
  depositsMobilizedInPeriod: number;
  convertedInPeriod: number;
}

export async function computeMetrics(period: { from: string; to: string }): Promise<Metrics> {
  const today = todayISO((await getSettings()).timeZone);
  const month = monthRange(today);
  const all = await db.visits.toArray();
  const inP = all.filter((v) => v.visitDate >= period.from && v.visitDate <= period.to);
  const pending = await db.followUps.where('status').equals('Pending').toArray();
  const liveVisitIds = new Set(all.map((v) => v.id));
  const livePending = pending.filter((f) => liveVisitIds.has(f.visitId));
  return {
    visitsToday: all.filter((v) => v.visitDate === today).length,
    visitsThisMonth: all.filter((v) => v.visitDate >= month.from && v.visitDate <= month.to).length,
    visitsInPeriod: inP.length,
    departmentsInPeriod: new Set(inP.map((v) => normaliseKey(v.departmentName))).size,
    opportunitiesInPeriod: inP.reduce((n, v) => n + v.opportunities.length, 0),
    followUpsDueToday: livePending.filter((f) => f.dueDate === today).length,
    followUpsOverdue: livePending.filter((f) => f.dueDate < today).length,
    accountsSourcedInPeriod: inP.reduce((n, v) => n + (v.accountsSourced || 0), 0),
    depositsMobilizedInPeriod: inP.reduce((n, v) => n + (v.depositsMobilized || 0), 0),
    convertedInPeriod: inP.filter((v) => v.status === 'Converted').length,
  };
}

export const upcomingWindow = (today: string) => ({ from: today, to: addDays(today, 7) });
