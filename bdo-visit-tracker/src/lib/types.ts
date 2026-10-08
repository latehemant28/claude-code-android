export const VISIT_STATUSES = ['Open', 'Follow-up Pending', 'In Progress', 'Converted', 'Closed'] as const;
export type VisitStatus = (typeof VISIT_STATUSES)[number];

export const FOLLOWUP_STATUSES = ['Pending', 'Completed', 'Cancelled'] as const;
export type FollowUpStatus = (typeof FOLLOWUP_STATUSES)[number];

/**
 * A visit keeps its own copy (snapshot) of the department name and the official's
 * name/designation as they were on the day of the visit, so renaming a department
 * or an official's later promotion never rewrites history.
 */
export interface Visit {
  id?: number;
  departmentId?: number;
  departmentName: string;
  officialId?: number;
  officialName: string;
  officialDesignation: string;
  contactNumber: string;
  /** Visit date as YYYY-MM-DD in the configured local time zone (Asia/Kolkata). */
  visitDate: string;
  /** Visit time as HH:mm (24h) in the configured local time zone. */
  visitTime: string;
  opportunities: string[];
  remarks: string;
  zone: string;
  branch: string;
  location: string;
  purpose: string;
  followUpRequired: boolean;
  status: VisitStatus;
  /** Business actually generated (free text, e.g. "12 salary a/cs opened"). Never an opportunity. */
  businessGenerated: string;
  /** Deposits mobilised, in rupees (0 when none). */
  depositsMobilized: number;
  /** Accounts actually sourced (0 when none). */
  accountsSourced: number;
  /** Record creation / last update, ISO 8601 UTC. Distinct from the visit date/time. */
  createdAt: string;
  updatedAt: string;
}

export interface FollowUp {
  id?: number;
  visitId: number;
  action: string;
  responsible: string;
  /** YYYY-MM-DD */
  dueDate: string;
  status: FollowUpStatus;
  completionNotes: string;
  result: string;
  completedAt?: string;
  /** Previous due dates, newest last, kept when a follow-up is rescheduled. */
  rescheduleHistory: { from: string; to: string; reason: string; at: string }[];
  createdAt: string;
  updatedAt: string;
}

export interface Department {
  id?: number;
  name: string;
  /** Lower-cased name used for case-insensitive uniqueness. */
  key: string;
  zone: string;
  branch: string;
  useCount: number;
  lastUsedAt: string;
}

export interface Official {
  id?: number;
  name: string;
  designation: string;
  departmentName: string;
  contactNumber: string;
  /** departmentKey|nameKey, unique. */
  key: string;
  useCount: number;
  lastUsedAt: string;
}

export interface OpportunityType {
  id?: number;
  name: string;
  key: string;
  useCount: number;
}

export interface Attachment {
  id?: number;
  visitId: number;
  name: string;
  type: string;
  size: number;
  data: Blob;
  createdAt: string;
}

export interface Settings {
  reportTitle: string;
  bdmName: string;
  bdmDesignation: string;
  zmName: string;
  zmDesignation: string;
  zmSignatureLabel: string;
  bdmSignatureLabel: string;
  organisationName: string;
  defaultZone: string;
  defaultBranch: string;
  includeTimeColumn: boolean;
  timeZone: string;
  autoLockMinutes: number;
}

export const DEFAULT_SETTINGS: Settings = {
  reportTitle: 'Annexure I: Visit Details for Liability Business Mobilization',
  bdmName: '',
  bdmDesignation: '',
  zmName: '',
  zmDesignation: '',
  bdmSignatureLabel: 'Business Development Manager',
  zmSignatureLabel: 'DGM & Zonal Manager',
  organisationName: '',
  defaultZone: '',
  defaultBranch: '',
  includeTimeColumn: false,
  timeZone: 'Asia/Kolkata',
  autoLockMinutes: 5,
};

export const COMMON_OPPORTUNITIES = [
  'CG 2025 Salary Account',
  'CASA',
  'Municipal Bonds',
  'Deposit mobilization',
  'Collection accounts',
  'Home loan',
  'Personal loan',
  'E-auction bid participation',
  'Institutional / Government business',
];

export interface FollowUpDraft {
  id?: number;
  action: string;
  responsible: string;
  dueDate: string;
}

export type VisitInput = Omit<Visit, 'id' | 'createdAt' | 'updatedAt' | 'departmentId' | 'officialId'>;
