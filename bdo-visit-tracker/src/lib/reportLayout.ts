import { formatDate, formatTime } from './dates';
import type { Visit } from './types';

/**
 * Column layout and cell text of the Annexure I report. Kept free of ExcelJS so the on-screen
 * preview can load instantly; the workbook itself is built in excel.ts.
 */
export interface Col {
  header: string;
  width: number;
  align: 'left' | 'center';
}

export function reportColumns(includeTime: boolean): Col[] {
  const cols: Col[] = [
    { header: 'S.No.', width: 7, align: 'center' },
    { header: 'Name of the Dept', width: 30, align: 'left' },
    { header: 'Official Met', width: 26, align: 'left' },
    { header: 'Date of Visit', width: 13, align: 'center' },
    { header: 'Opportunities Identified', width: 32, align: 'left' },
    { header: 'Remarks', width: 46, align: 'left' },
    { header: 'Zone', width: 14, align: 'center' },
  ];
  if (includeTime) cols.splice(4, 0, { header: 'Time of Visit', width: 11, align: 'center' });
  return cols;
}

export const officialMetText = (v: Pick<Visit, 'officialName' | 'officialDesignation'>) =>
  [v.officialName, v.officialDesignation].filter(Boolean).join(',\n');

export const opportunitiesText = (ops: string[]) => (ops.length > 1 ? ops.map((o, i) => `${i + 1}. ${o}`).join('\n') : ops[0] ?? '');

/** Row values for the report, in column order, as shown in the preview and written to Excel. */
export function reportRow(v: Visit, index: number, includeTime: boolean): (string | number)[] {
  const row: (string | number)[] = [index + 1, v.departmentName, officialMetText(v), formatDate(v.visitDate), opportunitiesText(v.opportunities), v.remarks, v.zone];
  if (includeTime) row.splice(4, 0, formatTime(v.visitTime));
  return row;
}

export function reportFileName(periodSlug: string, includeTime: boolean) {
  const safe = periodSlug.replace(/[^A-Za-z0-9_-]+/g, '_').replace(/_+/g, '_').replace(/^_|_$/g, '');
  return `Annexure-I_Visit-Details_${safe || 'report'}${includeTime ? '_with-time' : ''}.xlsx`;
}
