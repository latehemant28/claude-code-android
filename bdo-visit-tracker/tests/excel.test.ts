import { describe, expect, it } from 'vitest';
import ExcelJS from 'exceljs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { buildReportWorkbook } from '../src/lib/excel';
import { DEFAULT_SETTINGS, type Visit } from '../src/lib/types';

const ts = '2026-10-01T05:00:00.000Z';
const mk = (i: number, over: Partial<Visit> = {}): Visit => ({
  id: i, departmentName: `Department ${i}`, officialName: `Official ${i}`, officialDesignation: 'District Treasury Officer',
  contactNumber: '9999999999', visitDate: `2026-10-${String(i).padStart(2, '0')}`, visitTime: '14:35',
  opportunities: ['CG 2025 Salary Account', 'CASA'], remarks: 'Short remark', zone: 'Raipur', branch: '', location: '', purpose: '',
  followUpRequired: false, status: 'Open', businessGenerated: 'INTERNAL', depositsMobilized: 99, accountsSourced: 7, createdAt: ts, updatedAt: ts, ...over,
});

const settings = { ...DEFAULT_SETTINGS, bdmName: 'A. Kumar', zmName: 'S. Verma', zmDesignation: 'DGM & Zonal Manager, Raipur Zone', organisationName: 'Raipur Zone' };

async function roundTrip(wb: ExcelJS.Workbook, file: string) {
  const buf = await wb.xlsx.writeBuffer();
  mkdirSync('test-output', { recursive: true });
  writeFileSync(`test-output/${file}`, Buffer.from(buf));
  const back = new ExcelJS.Workbook();
  await back.xlsx.load(buf);
  return back.worksheets[0];
}

describe('Annexure I workbook', () => {
  it('writes the 7-column layout with exact rows, serials, dates and signatures', async () => {
    const long = 'Met the Commissioner regarding the municipal bond issue. '.repeat(12);
    const visits = Array.from({ length: 40 }, (_, i) => mk(i % 28 + 1, i === 2 ? { remarks: long } : {}));
    const ws = await roundTrip(buildReportWorkbook(visits, settings, { periodLabel: '01-Oct-2026 to 31-Oct-2026', includeTime: false }), 'annexure-standard.xlsx');

    expect(ws.getCell('A1').value).toBe('Annexure I: Visit Details for Liability Business Mobilization');
    expect(ws.model.merges).toContain('A1:G1');
    expect(String(ws.getCell('A2').value)).toContain('01-Oct-2026 to 31-Oct-2026');
    expect(ws.getRow(3).values).toEqual([undefined, 'S.No.', 'Name of the Dept', 'Official Met', 'Date of Visit', 'Opportunities Identified', 'Remarks', 'Zone']);
    expect(ws.getCell('A3').font.bold).toBe(true);
    for (let i = 0; i < visits.length; i++) {
      const r = ws.getRow(4 + i);
      expect(r.getCell(1).value).toBe(i + 1);
      expect(r.getCell(2).value).toBe(visits[i].departmentName);
      const dt = r.getCell(4).value as Date;
      expect(dt.toISOString().slice(0, 10)).toBe(visits[i].visitDate);
      expect(r.getCell(4).numFmt).toBe('dd-mm-yyyy');
      expect(r.getCell(6).alignment.wrapText).toBe(true);
      expect(r.getCell(7).border.bottom?.style).toBe('thin');
      expect(r.getCell(8).value ?? null).toBeNull(); // nothing beyond column G
    }
    expect(ws.getCell('C4').value).toBe('Official 1,\nDistrict Treasury Officer');
    expect(ws.getCell('E4').value).toBe('1. CG 2025 Salary Account\n2. CASA');
    expect(ws.getRow(6).height).toBeGreaterThan(100); // long remark row grows
    // no internal fields leaked
    const all = JSON.stringify(ws.getSheetValues());
    expect(all).not.toContain('INTERNAL');
    // signatures
    const values = ws.getSheetValues().flat().filter(Boolean).map(String);
    expect(values).toContain('Business Development Manager');
    expect(values).toContain('DGM & Zonal Manager');
    expect(values).toContain('(S. Verma)');
    // print setup
    expect(ws.pageSetup.orientation).toBe('landscape');
    expect(ws.pageSetup.fitToWidth).toBe(1);
    expect(ws.pageSetup.fitToHeight).toBe(0);
    expect(ws.pageSetup.printTitlesRow).toBe('3:3');
  });

  it('adds a real time column when requested', async () => {
    const ws = await roundTrip(buildReportWorkbook([mk(1), mk(2, { visitTime: '09:05' })], settings, { periodLabel: 'All visits', includeTime: true }), 'annexure-with-time.xlsx');
    expect(ws.getCell('E3').value).toBe('Time of Visit');
    expect(ws.getCell('H3').value).toBe('Zone');
    // read back as a real Excel time value (day 0, 14:35)
    expect((ws.getCell('E4').value as Date).toISOString()).toBe('1899-12-30T14:35:00.000Z');
    expect(ws.getCell('E5').numFmt).toBe('hh:mm AM/PM');
    expect(ws.model.merges).toContain('A1:H1');
  });
});
