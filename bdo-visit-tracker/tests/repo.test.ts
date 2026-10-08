import { beforeEach, describe, expect, it } from 'vitest';
import { freshDb, visit } from './helpers';
import { BdoDatabase, useDatabase } from '../src/lib/db';
import {
  completeFollowUp, computeMetrics, deleteVisit, findPossibleDuplicates, followUpsForVisit, getVisit,
  listDepartments, listFollowUpsWithVisits, listVisits, rescheduleFollowUp, saveVisit,
} from '../src/lib/repo';
import { ValidationError } from '../src/lib/validation';
import { createBackup, parseBackup, restoreBackup } from '../src/lib/backup';
import { todayISO, addDays } from '../src/lib/dates';

let d: BdoDatabase;
beforeEach(() => {
  d = freshDb();
});

describe('visits', () => {
  it('creates a visit with all fields, ids and timestamps', async () => {
    const { visit: v } = await saveVisit(
      visit({ contactNumber: '+91 98765 43210', location: 'Civil Lines', followUpRequired: true, status: 'Follow-up Pending', businessGenerated: '5 a/cs', depositsMobilized: 250000, accountsSourced: 5 }),
      [{ action: 'Share MoU draft', responsible: 'Self', dueDate: '2026-10-10' }],
    );
    expect(v.id).toBeGreaterThan(0);
    expect(v.visitDate).toBe('2026-10-05');
    expect(v.visitTime).toBe('11:30');
    expect(v.createdAt).toMatch(/Z$/);
    expect(v.updatedAt).toBe(v.createdAt);
    expect(v.depositsMobilized).toBe(250000);
    expect(await followUpsForVisit(v.id!)).toHaveLength(1);
  });

  it('persists across a fresh database connection (app restart)', async () => {
    const { visit: v } = await saveVisit(visit(), []);
    const name = d.name;
    d.close();
    const reopened = new BdoDatabase(name);
    useDatabase(reopened);
    expect((await getVisit(v.id!))?.departmentName).toBe('Office of the Treasury, Raipur');
  });

  it('edits a visit, keeps createdAt and bumps updatedAt', async () => {
    const { visit: v } = await saveVisit(visit(), []);
    await new Promise((r) => setTimeout(r, 5));
    const { visit: e } = await saveVisit({ ...visit(), remarks: 'Updated remarks', visitTime: '15:45' }, [], v.id);
    expect(e.remarks).toBe('Updated remarks');
    expect(e.visitTime).toBe('15:45');
    expect(e.createdAt).toBe(v.createdAt);
    expect(e.updatedAt > v.updatedAt).toBe(true);
    expect(await d.visits.count()).toBe(1);
  });

  it('rejects invalid input with field-specific messages', async () => {
    const bad = visit({ departmentName: '  ', opportunities: [], visitTime: '25:00', visitDate: addDays(todayISO(), 2) });
    await expect(saveVisit(bad, [])).rejects.toBeInstanceOf(ValidationError);
    try {
      await saveVisit(bad, []);
    } catch (e) {
      const errs = (e as ValidationError).errors;
      expect(Object.keys(errs).sort()).toEqual(['departmentName', 'opportunities', 'visitDate', 'visitTime']);
      expect(errs.visitDate).toMatch(/future/);
    }
    await expect(saveVisit(visit({ followUpRequired: true }), [])).rejects.toThrow(/follow-up/i);
    expect(await d.visits.count()).toBe(0);
  });

  it('allows repeat visits to the same department and flags same-day duplicates', async () => {
    await saveVisit(visit({ visitDate: '2026-10-01' }), []);
    await saveVisit(visit({ visitDate: '2026-10-05' }), []);
    expect(await d.visits.count()).toBe(2);
    expect(await findPossibleDuplicates({ departmentName: 'office of the treasury,  raipur', visitDate: '2026-10-05' })).toHaveLength(1);
    expect(await findPossibleDuplicates({ departmentName: 'Office of the Treasury, Raipur', visitDate: '2026-10-02' })).toHaveLength(0);
    // master data de-duplicated
    expect(await listDepartments()).toHaveLength(1);
    expect((await listDepartments())[0].useCount).toBe(2);
    const { visit: v3 } = await saveVisit(visit({ departmentName: 'office of the treasury, raipur', visitDate: '2026-10-06' }), []);
    expect(v3.departmentName).toBe('Office of the Treasury, Raipur');
  });

  it('keeps the historical snapshot when department/official details change later', async () => {
    const { visit: v1 } = await saveVisit(visit({ officialDesignation: 'Assistant Treasury Officer' }), []);
    await saveVisit(visit({ visitDate: '2026-10-06', officialDesignation: 'Treasury Officer' }), []);
    expect((await getVisit(v1.id!))?.officialDesignation).toBe('Assistant Treasury Officer');
  });

  it('searches and filters', async () => {
    await saveVisit(visit({ departmentName: 'Municipal Corporation Bilaspur', zone: 'Bilaspur', opportunities: ['Municipal Bonds'], visitDate: '2026-09-15', remarks: 'Bond issue planned' }), []);
    await saveVisit(visit({ visitDate: '2026-10-02', status: 'Converted' }), []);
    await saveVisit(visit({ departmentName: 'PWD Division', visitDate: '2026-04-01', opportunities: ['E-auction bid participation'], remarks: 'Tender EMD collection' }), []);
    expect(await listVisits({ q: 'bond' })).toHaveLength(1);
    expect(await listVisits({ q: 'sharma treasury' })).toHaveLength(3);
    expect(await listVisits({ q: 'tender emd' })).toHaveLength(1);
    expect(await listVisits({ from: '2026-10-01', to: '2026-10-31' })).toHaveLength(1);
    expect(await listVisits({ zone: 'bilaspur' })).toHaveLength(1);
    expect(await listVisits({ opportunity: 'casa' })).toHaveLength(1);
    expect(await listVisits({ status: 'Converted' })).toHaveLength(1);
    expect(await listVisits({ department: 'pwd division' })).toHaveLength(1);
    const asc = await listVisits({ sort: 'asc' });
    expect(asc.map((v) => v.visitDate)).toEqual(['2026-04-01', '2026-09-15', '2026-10-02']);
  });

  it('sorts by date then time', async () => {
    await saveVisit(visit({ visitDate: '2026-10-05', visitTime: '16:00', departmentName: 'B' }), []);
    await saveVisit(visit({ visitDate: '2026-10-05', visitTime: '09:15', departmentName: 'A' }), []);
    expect((await listVisits({ sort: 'asc' })).map((v) => v.departmentName)).toEqual(['A', 'B']);
  });

  it('deletes a visit with its follow-ups', async () => {
    const { visit: v } = await saveVisit(visit({ followUpRequired: true }), [{ action: 'Call', responsible: '', dueDate: '2026-10-09' }]);
    await deleteVisit(v.id!);
    expect(await d.visits.count()).toBe(0);
    expect(await d.followUps.count()).toBe(0);
  });
});

describe('follow-ups', () => {
  it('creates, completes and reschedules; filters overdue/due today', async () => {
    const today = todayISO();
    const { visit: v } = await saveVisit(visit({ visitDate: addDays(today, -3), followUpRequired: true }), [
      { action: 'Submit proposal', responsible: 'Self', dueDate: addDays(today, -1) },
      { action: 'Call DDO', responsible: 'Branch Manager', dueDate: today },
      { action: 'Collect KYC', responsible: '', dueDate: addDays(today, 4) },
    ]);
    let m = await computeMetrics({ from: addDays(today, -30), to: today });
    expect(m.followUpsOverdue).toBe(1);
    expect(m.followUpsDueToday).toBe(1);
    expect(await listVisits({ followUp: 'overdue' })).toHaveLength(1);

    const [overdue, dueToday] = await followUpsForVisit(v.id!);
    await rescheduleFollowUp(overdue.id!, addDays(today, 2), 'Official on leave');
    await completeFollowUp(dueToday.id!, 'Spoke to DDO', '120 accounts agreed');
    m = await computeMetrics({ from: addDays(today, -30), to: today });
    expect(m.followUpsOverdue).toBe(0);
    expect(m.followUpsDueToday).toBe(0);
    const rows = await listFollowUpsWithVisits();
    const res = rows.find((r) => r.id === overdue.id)!;
    expect(res.dueDate).toBe(addDays(today, 2));
    expect(res.rescheduleHistory[0]).toMatchObject({ from: addDays(today, -1), reason: 'Official on leave' });
    expect(rows.find((r) => r.id === dueToday.id)).toMatchObject({ status: 'Completed', result: '120 accounts agreed' });
    expect(rows[0].visit?.departmentName).toBe('Office of the Treasury, Raipur');
  });

  it('editing a visit keeps completed follow-ups', async () => {
    const { visit: v, followUps } = await saveVisit(visit({ followUpRequired: true }), [{ action: 'A', responsible: '', dueDate: '2026-10-09' }]);
    await completeFollowUp(followUps[0].id!, 'done', '');
    await saveVisit(visit({ followUpRequired: false }), [], v.id);
    expect(await followUpsForVisit(v.id!)).toHaveLength(1);
  });
});

describe('metrics keep opportunities separate from business', () => {
  it('counts opportunities, accounts, deposits and conversions separately', async () => {
    const t = todayISO();
    await saveVisit(visit({ visitDate: t, opportunities: ['CASA', 'Home loan', 'Municipal Bonds'] }), []);
    await saveVisit(visit({ visitDate: t, departmentName: 'Collectorate', accountsSourced: 12, depositsMobilized: 500000, status: 'Converted' }), []);
    const m = await computeMetrics({ from: t, to: t });
    expect(m).toMatchObject({ visitsToday: 2, departmentsInPeriod: 2, opportunitiesInPeriod: 5, accountsSourcedInPeriod: 12, depositsMobilizedInPeriod: 500000, convertedInPeriod: 1 });
  });
});

describe('backup', () => {
  it('round-trips through JSON and merges without duplicates', async () => {
    await saveVisit(visit(), []);
    await saveVisit(visit({ visitDate: '2026-10-06', followUpRequired: true }), [{ action: 'X', responsible: '', dueDate: '2026-10-10' }]);
    const json = JSON.stringify(await createBackup());
    freshDb();
    const r1 = await restoreBackup(parseBackup(json), 'merge');
    expect(r1).toEqual({ added: 2, skipped: 0 });
    const r2 = await restoreBackup(parseBackup(json), 'merge');
    expect(r2).toEqual({ added: 0, skipped: 2 });
    expect(await listVisits()).toHaveLength(2);
    expect((await listFollowUpsWithVisits())[0].visit).toBeDefined();
    expect(() => parseBackup('{"format":"x"}')).toThrow(/not a BDO/);
  });
});
