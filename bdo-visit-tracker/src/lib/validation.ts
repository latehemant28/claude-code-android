import { isValidISODate, isValidTime } from './dates';
import { VISIT_STATUSES, type FollowUpDraft, type VisitInput } from './types';

export type FieldErrors = Record<string, string>;

export class ValidationError extends Error {
  constructor(public errors: FieldErrors) {
    super('Please fix the highlighted fields: ' + Object.values(errors).join(' '));
    this.name = 'ValidationError';
  }
}

const LIMITS = { short: 200, remarks: 4000 };
export const normaliseKey = (s: string) => s.trim().replace(/\s+/g, ' ').toLowerCase();
const clean = (s: unknown) => (typeof s === 'string' ? s.replace(/\s+$/g, '').replace(/^\s+/g, '') : '');

/** Trim text, de-duplicate opportunities, coerce numbers. Used before validation and saving. */
export function normaliseVisit(v: VisitInput): VisitInput {
  const seen = new Set<string>();
  const opportunities: string[] = [];
  for (const o of v.opportunities ?? []) {
    const t = clean(o).replace(/\s+/g, ' ');
    if (t && !seen.has(t.toLowerCase())) {
      seen.add(t.toLowerCase());
      opportunities.push(t);
    }
  }
  const num = (n: unknown) => {
    const x = typeof n === 'number' ? n : Number(String(n ?? '').replace(/,/g, ''));
    return Number.isFinite(x) ? x : NaN;
  };
  return {
    departmentName: clean(v.departmentName).replace(/\s+/g, ' '),
    officialName: clean(v.officialName).replace(/\s+/g, ' '),
    officialDesignation: clean(v.officialDesignation).replace(/\s+/g, ' '),
    contactNumber: clean(v.contactNumber),
    visitDate: clean(v.visitDate),
    visitTime: clean(v.visitTime),
    opportunities,
    remarks: clean(v.remarks),
    zone: clean(v.zone),
    branch: clean(v.branch),
    location: clean(v.location),
    purpose: clean(v.purpose),
    followUpRequired: !!v.followUpRequired,
    status: v.status,
    businessGenerated: clean(v.businessGenerated),
    depositsMobilized: v.depositsMobilized === undefined || (v.depositsMobilized as unknown) === '' ? 0 : num(v.depositsMobilized),
    accountsSourced: v.accountsSourced === undefined || (v.accountsSourced as unknown) === '' ? 0 : num(v.accountsSourced),
  };
}

/**
 * Validates a visit and its follow-ups. Called by the form for instant feedback and again by the
 * data layer before every write, so an invalid record can never reach the database.
 */
export function validateVisit(v: VisitInput, followUps: FollowUpDraft[], today: string): FieldErrors {
  const e: FieldErrors = {};
  if (!v.departmentName) e.departmentName = 'Enter the department or organisation you visited.';
  else if (v.departmentName.length > LIMITS.short) e.departmentName = `Department name is too long (max ${LIMITS.short} characters).`;
  if (!v.officialName) e.officialName = 'Enter the name of the official you met.';
  else if (v.officialName.length > LIMITS.short) e.officialName = 'Official name is too long.';
  if (!v.officialDesignation) e.officialDesignation = "Enter the official's designation (e.g. Treasury Officer).";
  if (!v.visitDate) e.visitDate = 'Pick the date of the visit.';
  else if (!isValidISODate(v.visitDate)) e.visitDate = 'Visit date is not a valid date. Use the date picker.';
  else if (v.visitDate > today) e.visitDate = 'Visit date is in the future. Record visits on or after the day they happen.';
  else if (v.visitDate < '2000-01-01') e.visitDate = 'Visit date looks wrong (before 2000). Check the year.';
  if (!v.visitTime) e.visitTime = 'Enter the time of the visit.';
  else if (!isValidTime(v.visitTime)) e.visitTime = 'Time must be in HH:MM format, e.g. 11:30.';
  if (v.opportunities.length === 0) e.opportunities = 'Add at least one opportunity identified (tap a suggestion or type your own). Use "No specific opportunity" if none.';
  if (!v.remarks) e.remarks = 'Write a short summary of the discussion.';
  else if (v.remarks.length > LIMITS.remarks) e.remarks = `Remarks are too long (max ${LIMITS.remarks} characters).`;
  if (!v.zone) e.zone = 'Enter the zone (e.g. Raipur Zone).';
  if (v.contactNumber && !/^[+\d][\d\s\-()]{5,19}$/.test(v.contactNumber))
    e.contactNumber = 'Contact number should contain only digits, spaces, +, - or brackets (6-20 characters).';
  if (!VISIT_STATUSES.includes(v.status)) e.status = 'Choose a status from the list.';
  if (!Number.isFinite(v.depositsMobilized) || v.depositsMobilized < 0) e.depositsMobilized = 'Deposits mobilised must be a number of rupees, 0 or more.';
  if (!Number.isFinite(v.accountsSourced) || v.accountsSourced < 0 || !Number.isInteger(v.accountsSourced))
    e.accountsSourced = 'Accounts sourced must be a whole number, 0 or more.';

  const active = followUps.filter((f) => f.action.trim() || f.dueDate);
  if (v.followUpRequired && active.length === 0) e.followUps = 'Follow-up is marked as required: add at least one follow-up action with a due date, or switch it off.';
  active.forEach((f, i) => {
    if (!f.action.trim()) e[`followUps.${i}.action`] = `Follow-up ${i + 1}: describe the action to take.`;
    if (!f.dueDate) e[`followUps.${i}.dueDate`] = `Follow-up ${i + 1}: choose a due date.`;
    else if (!isValidISODate(f.dueDate)) e[`followUps.${i}.dueDate`] = `Follow-up ${i + 1}: due date is not valid.`;
  });
  return e;
}

/** Fields the standard Annexure I report needs. Used to warn before export. */
export function reportGaps(v: { departmentName: string; officialName: string; visitDate: string; opportunities: string[]; remarks: string; zone: string }): string[] {
  const gaps: string[] = [];
  if (!v.departmentName?.trim()) gaps.push('Name of the Dept');
  if (!v.officialName?.trim()) gaps.push('Official Met');
  if (!isValidISODate(v.visitDate)) gaps.push('Date of Visit');
  if (!v.opportunities?.length) gaps.push('Opportunities Identified');
  if (!v.remarks?.trim()) gaps.push('Remarks');
  if (!v.zone?.trim()) gaps.push('Zone');
  return gaps;
}
