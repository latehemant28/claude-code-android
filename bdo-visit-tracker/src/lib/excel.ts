import ExcelJS from 'exceljs';
import { formatTimestamp, isValidISODate } from './dates';
import { reportColumns, reportRow } from './reportLayout';

export { reportColumns, reportRow, reportFileName } from './reportLayout';
import type { FollowUp, Settings, Visit } from './types';

export interface ReportOptions {
  /** e.g. "01-Oct-2026 to 31-Oct-2026" or "All visits" */
  periodLabel: string;
  includeTime: boolean;
  generatedAt?: Date;
}

/** Excel serial-free date: a UTC midnight Date so Excel shows the stored calendar day in any time zone. */
const excelDate = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
/** HH:mm -> fraction of a day (Excel time value). */
const excelTime = (t: string) => {
  const [h, m] = t.split(':').map(Number);
  return (h * 60 + m) / 1440;
};

/** Estimated wrapped line count for `text` in a column `width` characters wide. */
function lineCount(text: string, width: number): number {
  const usable = Math.max(1, width * 1.1 - 2);
  return String(text ?? '')
    .split('\n')
    .reduce((n, line) => n + Math.max(1, Math.ceil(line.length / usable)), 0);
}

const thin = { style: 'thin' as const, color: { argb: 'FF000000' } };
const allBorders = { top: thin, left: thin, bottom: thin, right: thin };

/**
 * Builds the "Annexure I" visit-details workbook in the bank's reporting layout:
 * merged title, period line, bordered table with bold headings, wrapped text, real Excel dates,
 * landscape fit-to-width print setup with the heading row repeated on every page, and
 * signature blocks for the BDM and the DGM & Zonal Manager.
 */
export function buildReportWorkbook(visits: Visit[], settings: Settings, opts: ReportOptions): ExcelJS.Workbook {
  const wb = new ExcelJS.Workbook();
  wb.creator = 'BDO Visit Tracker';
  wb.created = opts.generatedAt ?? new Date();
  wb.title = settings.reportTitle;

  const cols = reportColumns(opts.includeTime);
  const n = cols.length;
  const ws = wb.addWorksheet('Annexure I', {
    pageSetup: {
      paperSize: 9, // A4
      orientation: 'landscape',
      fitToPage: true,
      fitToWidth: 1,
      fitToHeight: 0,
      horizontalCentered: true,
      margins: { left: 0.4, right: 0.4, top: 0.5, bottom: 0.6, header: 0.3, footer: 0.3 },
    },
    headerFooter: { oddFooter: '&L&8Annexure I - Visit Details&R&8Page &P of &N' },
    views: [{ state: 'frozen', ySplit: 3, showGridLines: false }],
  });
  ws.columns = cols.map((c) => ({ width: c.width }));

  // Row 1: title
  ws.mergeCells(1, 1, 1, n);
  const title = ws.getCell(1, 1);
  title.value = settings.reportTitle || 'Annexure I: Visit Details for Liability Business Mobilization';
  title.font = { name: 'Calibri', size: 14, bold: true };
  title.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
  ws.getRow(1).height = 26;

  // Row 2: report metadata (period, organisation)
  ws.mergeCells(2, 1, 2, n);
  const meta = ws.getCell(2, 1);
  const metaParts = [`Period: ${opts.periodLabel}`, `Total visits: ${visits.length}`];
  if (settings.organisationName) metaParts.unshift(settings.organisationName);
  meta.value = metaParts.join('     |     ');
  meta.font = { name: 'Calibri', size: 10, italic: true };
  meta.alignment = { horizontal: 'center', vertical: 'middle' };
  ws.getRow(2).height = 18;

  // Row 3: column headings
  const head = ws.getRow(3);
  cols.forEach((c, i) => {
    const cell = head.getCell(i + 1);
    cell.value = c.header;
    cell.font = { name: 'Calibri', size: 11, bold: true };
    cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
    cell.border = allBorders;
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD9E1F2' } };
  });
  head.height = 32;

  // Data rows
  visits.forEach((v, i) => {
    const r = ws.getRow(4 + i);
    const text = reportRow(v, i, opts.includeTime);
    const values: (string | number | Date)[] = [...text];
    values[3] = isValidISODate(v.visitDate) ? excelDate(v.visitDate) : v.visitDate;
    if (opts.includeTime && v.visitTime) values[4] = excelTime(v.visitTime);
    values.forEach((val, c) => {
      const cell = r.getCell(c + 1);
      cell.value = val;
      cell.font = { name: 'Calibri', size: 11 };
      cell.border = allBorders;
      cell.alignment = { horizontal: cols[c].align, vertical: 'top', wrapText: true };
    });
    r.getCell(4).numFmt = 'dd-mm-yyyy';
    if (opts.includeTime) r.getCell(5).numFmt = 'hh:mm AM/PM';
    // Excel does not auto-size wrapped rows written by a library, so size them here.
    const lines = Math.max(...text.map((t, c) => lineCount(String(t), cols[c].width)));
    r.height = Math.max(20, lines * 15 + 4);
  });

  // Signature section
  const last = 3 + visits.length;
  const sigTop = last + 4; // three blank rows of signing space
  ws.getRow(sigTop - 1).height = 36;
  const leftCols: [number, number] = [1, 2];
  const rightCols: [number, number] = [n - 1, n];
  const block = (row: number, [c1, c2]: [number, number], value: string, font: Partial<ExcelJS.Font>, border = false) => {
    ws.mergeCells(row, c1, row, c2);
    const cell = ws.getCell(row, c1);
    cell.value = value;
    cell.font = { name: 'Calibri', size: 11, ...font };
    cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
    if (border) for (let c = c1; c <= c2; c++) ws.getCell(row, c).border = { top: thin };
  };
  block(sigTop, leftCols, settings.bdmName ? `(${settings.bdmName})` : '', {}, true);
  block(sigTop, rightCols, settings.zmName ? `(${settings.zmName})` : '', {}, true);
  block(sigTop + 1, leftCols, settings.bdmSignatureLabel || 'Business Development Manager', { bold: true });
  block(sigTop + 1, rightCols, settings.zmSignatureLabel || 'DGM & Zonal Manager', { bold: true });
  if (settings.bdmDesignation || settings.zmDesignation) {
    block(sigTop + 2, leftCols, settings.bdmDesignation, { size: 10 });
    block(sigTop + 2, rightCols, settings.zmDesignation, { size: 10 });
  }

  ws.pageSetup.printTitlesRow = '3:3';
  ws.pageSetup.printArea = `A1:${String.fromCharCode(64 + n)}${sigTop + 2}`;
  return wb;
}

export async function workbookToBlob(wb: ExcelJS.Workbook): Promise<Blob> {
  const buf = await wb.xlsx.writeBuffer();
  return new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
}

/**
 * A full-data workbook (every field, every follow-up) for backup and analysis. Not the
 * official report format.
 */
export function buildFullDataWorkbook(visits: Visit[], followUps: FollowUp[], tz: string): ExcelJS.Workbook {
  const wb = new ExcelJS.Workbook();
  wb.creator = 'BDO Visit Tracker';
  const vs = wb.addWorksheet('Visits');
  vs.columns = [
    { header: 'Record ID', key: 'id', width: 10 },
    { header: 'Date of Visit', key: 'visitDate', width: 13 },
    { header: 'Time of Visit', key: 'visitTime', width: 11 },
    { header: 'Department', key: 'departmentName', width: 30 },
    { header: 'Official', key: 'officialName', width: 22 },
    { header: 'Designation', key: 'officialDesignation', width: 22 },
    { header: 'Contact', key: 'contactNumber', width: 15 },
    { header: 'Opportunities', key: 'opportunities', width: 32 },
    { header: 'Remarks', key: 'remarks', width: 46 },
    { header: 'Zone', key: 'zone', width: 14 },
    { header: 'Branch', key: 'branch', width: 16 },
    { header: 'Location', key: 'location', width: 18 },
    { header: 'Purpose', key: 'purpose', width: 22 },
    { header: 'Follow-up Required', key: 'followUpRequired', width: 10 },
    { header: 'Status', key: 'status', width: 16 },
    { header: 'Business Generated', key: 'businessGenerated', width: 26 },
    { header: 'Deposits Mobilised (Rs)', key: 'depositsMobilized', width: 16 },
    { header: 'Accounts Sourced', key: 'accountsSourced', width: 10 },
    { header: 'Record Created', key: 'createdAt', width: 20 },
    { header: 'Last Updated', key: 'updatedAt', width: 20 },
  ];
  for (const v of visits) {
    vs.addRow({
      ...v,
      visitDate: isValidISODate(v.visitDate) ? excelDate(v.visitDate) : v.visitDate,
      opportunities: v.opportunities.join('; '),
      followUpRequired: v.followUpRequired ? 'Yes' : 'No',
      createdAt: formatTimestamp(v.createdAt, tz),
      updatedAt: formatTimestamp(v.updatedAt, tz),
    });
  }
  vs.getColumn('visitDate').numFmt = 'dd-mm-yyyy';
  vs.getRow(1).font = { bold: true };
  vs.views = [{ state: 'frozen', ySplit: 1 }];

  const fs = wb.addWorksheet('Follow-ups');
  fs.columns = [
    { header: 'Follow-up ID', key: 'id', width: 10 },
    { header: 'Visit ID', key: 'visitId', width: 9 },
    { header: 'Department', key: 'dept', width: 28 },
    { header: 'Action', key: 'action', width: 36 },
    { header: 'Responsible', key: 'responsible', width: 18 },
    { header: 'Due Date', key: 'dueDate', width: 13 },
    { header: 'Status', key: 'status', width: 12 },
    { header: 'Result', key: 'result', width: 28 },
    { header: 'Completion Notes', key: 'completionNotes', width: 36 },
    { header: 'Times Rescheduled', key: 'rescheduled', width: 11 },
  ];
  const deptOf = new Map(visits.map((v) => [v.id, v.departmentName]));
  for (const f of followUps) {
    fs.addRow({ ...f, dept: deptOf.get(f.visitId) ?? '', dueDate: isValidISODate(f.dueDate) ? excelDate(f.dueDate) : f.dueDate, rescheduled: f.rescheduleHistory.length });
  }
  fs.getColumn('dueDate').numFmt = 'dd-mm-yyyy';
  fs.getRow(1).font = { bold: true };
  return wb;
}
