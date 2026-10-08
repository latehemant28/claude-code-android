import 'fake-indexeddb/auto';
import { BdoDatabase, useDatabase } from '../src/lib/db';
import type { VisitInput } from '../src/lib/types';

let n = 0;
export function freshDb() {
  const d = new BdoDatabase(`test-${Date.now()}-${n++}`);
  useDatabase(d);
  return d;
}

export function visit(over: Partial<VisitInput> = {}): VisitInput {
  return {
    departmentName: 'Office of the Treasury, Raipur',
    officialName: 'Shri R. K. Sharma',
    officialDesignation: 'Treasury Officer',
    contactNumber: '',
    visitDate: '2026-10-05',
    visitTime: '11:30',
    opportunities: ['CG 2025 Salary Account', 'CASA'],
    remarks: 'Discussed salary account tie-up for 450 employees.',
    zone: 'Raipur',
    branch: 'Main Branch',
    location: '',
    purpose: 'Salary tie-up',
    followUpRequired: false,
    status: 'Open',
    businessGenerated: '',
    depositsMobilized: 0,
    accountsSourced: 0,
    ...over,
  };
}
