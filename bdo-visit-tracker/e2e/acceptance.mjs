// End-to-end acceptance test: drives the built app in Chromium at phone size.
// Usage: npm run build && npm run e2e   (starts `vite preview` itself)
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import ExcelJS from 'exceljs';

const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  ({ chromium } = require('/opt/node22/lib/node_modules/playwright'));
}

const OUT = 'test-output/e2e';
mkdirSync(OUT, { recursive: true });
const PORT = 4173;
const BASE = `http://localhost:${PORT}/`;
const PASS = 'bdo2468';

const results = [];
const check = (name, cond, detail = '') => {
  results.push({ name, ok: !!cond, detail });
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
};

// Dates relative to "today" in Asia/Kolkata.
const istToday = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
const addDays = (iso, n) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
};
const D1 = addDays(istToday, -3);
const D2 = addDays(istToday, -1);
const D_OLD = addDays(istToday, -40);

const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview', '--port', String(PORT), '--strictPort'], { stdio: 'pipe' });
await new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('preview server did not start')), 30000);
  server.stdout.on('data', (d) => d.toString().includes('localhost') && (clearTimeout(t), res()));
});

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const mobile = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, acceptDownloads: true, timezoneId: 'Asia/Kolkata' };
const ctx = await browser.newContext(mobile);
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));

const noHorizontalScroll = async (label) => {
  const { sw, cw } = await page.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
  check(`no horizontal page scroll on ${label}`, sw <= cw, `${sw} <= ${cw}`);
};
const shot = (n) => page.screenshot({ path: `${OUT}/${n}.png`, fullPage: true });

async function fillVisit({ dept, official, designation, date, time, opps, newOpp, remarks, zone, branch }) {
  await page.fill('#departmentName', dept);
  await page.keyboard.press('Escape');
  await page.fill('#officialName', official);
  await page.keyboard.press('Escape');
  await page.fill('#officialDesignation', designation);
  if (date) await page.fill('#visitDate', date);
  if (time) await page.fill('#visitTime', time);
  for (const o of opps ?? []) await page.getByRole('button', { name: `+ ${o}`, exact: true }).click();
  if (newOpp) {
    await page.fill('#opportunities', newOpp);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
  }
  await page.fill('#remarks', remarks);
  await page.fill('#zone', zone);
  await page.keyboard.press('Escape');
  if (branch) await page.fill('#branch', branch);
}

try {
  // ---- 13a. fresh device: passcode required before any data
  await page.goto(BASE);
  await page.waitForSelector('#passcode');
  check('first launch asks to set a passcode', await page.isVisible('text=Set a passcode'));
  await page.fill('#passcode', '123456');
  await page.fill('#passcode2', '123456');
  await page.click('button:has-text("Set passcode")');
  check('weak passcode rejected with guidance', await page.isVisible('text=Avoid repeated or sequential digits'));
  await page.fill('#passcode', PASS);
  await page.fill('#passcode2', PASS);
  await page.click('button:has-text("Set passcode")');
  await page.waitForSelector('text=+ Add Visit');
  await noHorizontalScroll('dashboard (empty)');
  await shot('01-dashboard-empty');

  // ---- 1/2. create a visit with all fields; check auto-captured date/time
  await page.click('text=+ Add Visit'); // one tap from dashboard
  await page.waitForSelector('#departmentName');
  const autoDate = await page.inputValue('#visitDate');
  const autoTime = await page.inputValue('#visitTime');
  const nowIST = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date());
  const minutesApart = Math.abs((+autoTime.slice(0, 2) * 60 + +autoTime.slice(3)) - (+nowIST.slice(0, 2) * 60 + +nowIST.slice(3)));
  check('new visit pre-fills today (Asia/Kolkata)', autoDate === istToday, `${autoDate}`);
  check('new visit pre-fills current time (Asia/Kolkata)', minutesApart <= 1 || minutesApart >= 1439, `${autoTime} vs ${nowIST}`);

  // validation on empty submit
  await page.click('button:has-text("Save Visit")');
  check('required-field errors explain the fix', await page.isVisible('text=Enter the department or organisation you visited.') && await page.isVisible('text=Add at least one opportunity'));
  await noHorizontalScroll('add visit form');

  await fillVisit({
    dept: 'Office of the District Treasury, Raipur', official: 'Shri R. K. Sharma', designation: 'District Treasury Officer',
    date: D1, time: '11:30', opps: ['CG 2025 Salary Account', 'CASA'], newOpp: 'Pension disbursement tie-up',
    remarks: 'Discussed salary accounts for 450 employees under CG 2025. ' + 'Officer asked for a detailed proposal with service charges and insurance cover. '.repeat(3),
    zone: 'Raipur', branch: 'Main Branch',
  });
  await page.check('#followUpRequired');
  await page.locator('input[placeholder="e.g. Submit salary account proposal"]').first().fill('Submit salary account proposal');
  await page.locator('input[placeholder="Self"]').first().fill('Self');
  await page.click('text=More details (status, business, location, contact)');
  await page.fill('#purpose', 'Salary account tie-up');
  await page.fill('#location', 'Civil Lines, Raipur');
  await page.fill('#contactNumber', '+91 98765 43210');
  await page.fill('#businessGenerated', '5 salary accounts opened on the spot');
  await page.fill('#accountsSourced', '5');
  await page.fill('#depositsMobilized', '250000');
  await shot('02-add-visit-filled');
  await page.click('button:has-text("Save Visit")');
  await page.waitForSelector('role=dialog[name="Visit saved"]');
  check('save shows confirmation with next-step options', await page.isVisible('text=+ Add another visit') && await page.isVisible('text=View saved visits'));
  await shot('03-saved');

  // ---- 6. multiple visits to the same department (different day), and a same-day duplicate warning
  await page.click('text=+ Add another visit');
  await fillVisit({ dept: 'Office of the District Treasury, Raipur', official: 'Shri R. K. Sharma', designation: 'District Treasury Officer', date: D2, time: '15:10', opps: ['Municipal Bonds'], remarks: 'Second meeting: proposal submitted.', zone: 'Raipur' });
  await page.click('button:has-text("Save Visit")');
  await page.waitForSelector('role=dialog[name="Visit saved"]');
  check('repeat visit to same department on another date saves without warning', true);

  await page.click('text=+ Add another visit');
  await fillVisit({ dept: 'office of the district treasury, raipur', official: 'Smt. A. Verma', designation: 'Accounts Officer', date: D1, time: '16:45', opps: ['Deposit mobilization'], remarks: 'Separate afternoon meeting with Accounts section.', zone: 'Raipur' });
  await page.click('button:has-text("Save Visit")');
  await page.waitForSelector('role=dialog[name="Possible duplicate entry"]');
  check('same department + same date triggers duplicate warning', true);
  await shot('04-duplicate-warning');
  await page.click('button:has-text("Save anyway")');
  await page.waitForSelector('role=dialog[name="Visit saved"]');

  await page.click('text=+ Add another visit');
  await fillVisit({ dept: 'Municipal Corporation Bilaspur', official: 'Shri P. Singh', designation: 'Commissioner', date: D_OLD, time: '10:00', opps: ['E-auction bid participation'], remarks: 'E-auction EMD collection discussed.', zone: 'Bilaspur' });
  await page.click('button:has-text("Save Visit")');
  await page.waitForSelector('role=dialog[name="Visit saved"]');
  await page.click('text=View saved visits');
  await page.waitForSelector('text=4 visits');
  check('four visits listed', true);

  // ---- 3/13b. reload: locked, data hidden, then persists after unlock
  await page.reload();
  await page.waitForSelector('#passcode');
  check('reopening the app requires the passcode', true);
  check('no visit data rendered while locked', !(await page.content()).includes('District Treasury'));
  await page.fill('#passcode', 'wrong-one');
  await page.click('button:has-text("Unlock")');
  check('wrong passcode rejected', await page.waitForSelector('text=Wrong passcode').then(() => true));
  await page.fill('#passcode', PASS);
  await page.click('button:has-text("Unlock")');
  await page.waitForSelector('text=4 visits');
  check('all 4 visits persist after closing/reopening', true);
  await noHorizontalScroll('visit list');
  await shot('05-visit-list');

  // ---- 4. edit a visit
  await page.click(`a:has-text("Municipal Corporation Bilaspur")`);
  await page.waitForSelector('text=Visit Details');
  const createdText = await page.textContent('text=/Record #\\d+/');
  await page.click('a:has-text("Edit")');
  await page.waitForSelector('#remarks');
  await page.fill('#remarks', 'E-auction EMD collection discussed. Commissioner agreed to route EMD through our bank.');
  await page.fill('#visitTime', '10:20');
  await page.click('button:has-text("Save Changes")');
  await page.waitForURL(/#\/visits\/\d+$/);
  await page.waitForSelector('h1:has-text("Visit Details")');
  const detail = await page.textContent('main');
  check('edited remarks and time saved', detail.includes('Commissioner agreed to route EMD') && detail.includes('10:20 AM'), detail.slice(0, 400));
  check('created and last-updated timestamps shown separately', /created .* · last updated/.test(createdText ?? ''));

  // ---- 5. search and filter
  await page.goto(BASE + '#/visits');
  await page.fill('input[type=search]', 'proposal submitted');
  await page.waitForSelector('text=1 visit');
  check('search by remarks', true);
  await page.fill('input[type=search]', 'verma');
  await page.waitForSelector('text=1 visit');
  check('search by official', true);
  await page.fill('input[type=search]', 'municipal');
  await page.waitForSelector('text=2 visits');
  check('search by opportunity/department text', true);
  await page.fill('input[type=search]', '');
  await page.click('button:has-text("Filters")');
  await page.selectOption('select[aria-label="Zone"]', 'Bilaspur');
  await page.waitForSelector('text=1 visit');
  check('filter by zone', true);
  await page.selectOption('select[aria-label="Zone"]', '');
  await page.selectOption('select[aria-label="Follow-up"]', 'open');
  await page.waitForSelector('text=1 visit');
  check('filter by follow-up status', true);
  await page.click('text=Clear all filters');
  await page.selectOption('#list-period-kind', 'custom');
  await page.fill('#list-period-from', D1);
  await page.fill('#list-period-to', D1);
  await page.waitForSelector('text=2 visits');
  check('filter by date range', true);
  await page.click('text=Clear all filters');

  // ---- 7. follow-ups: add, complete, reschedule
  await page.goto(BASE + '#/followups');
  await page.click('[role=tab]:has-text("Upcoming")');
  await page.waitForSelector('text=Submit salary account proposal');
  await page.click('button:has-text("Reschedule")');
  await page.fill('role=dialog >> input[type=date]', addDays(istToday, 10));
  await page.fill('role=dialog >> input >> nth=1', 'Officer on tour');
  await page.click('role=dialog >> button:has-text("Reschedule")');
  await page.waitForSelector('text=Rescheduled 1×');
  check('follow-up rescheduled with history', true);
  await page.click('button:has-text("Mark completed")');
  await page.fill('role=dialog >> input', '120 salary accounts agreed');
  await page.click('role=dialog >> button:has-text("Mark completed")');
  await page.click('[role=tab]:has-text("Completed")');
  await page.waitForSelector('text=120 salary accounts agreed');
  check('follow-up completed with result', true);
  await noHorizontalScroll('follow-ups');
  await shot('06-followups');

  // overdue follow-up shows on dashboard
  await page.goto(BASE + '#/visits');
  await page.click('a:has-text("Smt. A. Verma")');
  await page.click('button:has-text("+ Add")');
  await page.fill('role=dialog >> input >> nth=0', 'Collect KYC documents');
  await page.fill('role=dialog >> input[type=date]', addDays(istToday, -1));
  await page.click('button:has-text("Add follow-up")');
  await page.goto(BASE + '#/');
  await page.waitForSelector('text=1 overdue follow-up');
  check('overdue follow-up highlighted on dashboard', true);
  await noHorizontalScroll('dashboard');
  await shot('07-dashboard');

  // ---- 8-11. export a date range, open the xlsx and verify contents
  await page.click('text=Generate Excel');
  await page.selectOption('#export-kind', 'custom');
  await page.fill('#export-from', addDays(istToday, -10));
  await page.fill('#export-to', istToday);
  await page.waitForSelector('[data-testid=export-summary] >> text=Visits included: 3');
  check('export confirms filters and visit count before generating', true);
  check('report preview shown', await page.isVisible('table.report-preview'));
  await page.click('text=Signature names');
  await page.fill('input >> nth=-4', 'Hemant Late').catch(() => {});
  await shot('08-export-preview');
  await noHorizontalScroll('export');
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Generate & download Excel")')]);
  const file = `${OUT}/${dl.suggestedFilename()}`;
  await dl.saveAs(file);
  check('generated file is .xlsx', file.endsWith('.xlsx'), dl.suggestedFilename());

  const wb = new ExcelJS.Workbook();
  await wb.xlsx.readFile(file); // throws if not a genuine xlsx (zip) file
  const ws = wb.worksheets[0];
  check('title row', ws.getCell('A1').value === 'Annexure I: Visit Details for Liability Business Mobilization');
  const heads = ws.getRow(3).values.slice(1);
  check('7 standard columns in order', JSON.stringify(heads) === JSON.stringify(['S.No.', 'Name of the Dept', 'Official Met', 'Date of Visit', 'Opportunities Identified', 'Remarks', 'Zone']), heads.join(' | '));
  const rows = [];
  for (let r = 4; ws.getCell(r, 1).value !== null && typeof ws.getCell(r, 1).value === 'number'; r++) rows.push(ws.getRow(r).values.slice(1));
  check('exactly the 3 visits in range, no missing/duplicates', rows.length === 3, `${rows.length} rows`);
  check('serial numbers sequential', rows.map((r) => r[0]).join(',') === '1,2,3');
  const dates = rows.map((r) => r[3].toISOString().slice(0, 10));
  check('dates are real Excel dates in chronological order', JSON.stringify(dates) === JSON.stringify([D1, D1, D2]), dates.join(','));
  check('out-of-range visit excluded', !rows.some((r) => String(r[1]).includes('Bilaspur')));
  check('multiple opportunities listed', String(rows[0][4]).includes('1. CG 2025 Salary Account') && String(rows[0][4]).includes('Pension disbursement tie-up'));
  check('long remarks kept in full with wrap', String(rows[0][5]).length > 200 && ws.getCell('F4').alignment?.wrapText === true);
  check('borders on data cells', ws.getCell('G6').border?.right?.style === 'thin');
  check('landscape, fit to width, repeated headings', ws.pageSetup.orientation === 'landscape' && ws.pageSetup.fitToWidth === 1 && ws.pageSetup.printTitlesRow === '3:3');
  const flat = ws.getSheetValues().flat().map(String);
  check('signature section present', flat.includes('Business Development Manager') && flat.includes('DGM & Zonal Manager'));
  check('internal fields not in report', !flat.some((v) => v.includes('250000') || v.includes('Civil Lines')));

  // with time column
  await page.check('#includeTime');
  const [dl2] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Generate & download Excel")')]);
  const file2 = `${OUT}/${dl2.suggestedFilename()}`;
  await dl2.saveAs(file2);
  const wb2 = new ExcelJS.Workbook();
  await wb2.xlsx.readFile(file2);
  const t = wb2.worksheets[0].getCell('E4').value;
  check('optional Time of Visit column holds the recorded time', wb2.worksheets[0].getCell('E3').value === 'Time of Visit' && t instanceof Date && t.toISOString().slice(11, 16) === '11:30');

  // selected visits only
  await page.goto(BASE + '#/visits');
  await page.click('button:has-text("Select")');
  await page.locator('input[type=checkbox]').nth(0).check();
  await page.locator('input[type=checkbox]').nth(3).check();
  await page.click('button:has-text("Export 2 selected visits")');
  await page.waitForSelector('[data-testid=export-summary] >> text=Visits included: 2');
  await page.uncheck('#includeTime');
  const [dl3] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Generate & download Excel")')]);
  const file3 = `${OUT}/${dl3.suggestedFilename()}`;
  await dl3.saveAs(file3);
  const wb3 = new ExcelJS.Workbook();
  await wb3.xlsx.readFile(file3);
  const ws3 = wb3.worksheets[0];
  check('selected-visits export has exactly 2 rows', ws3.getCell('A5').value === 2 && ws3.getCell('A6').value !== 3 && typeof ws3.getCell('A6').value !== 'number');

  // ---- backup download works
  await page.goto(BASE + '#/settings');
  const [bk] = await Promise.all([page.waitForEvent('download'), page.click('button:has-text("Download full backup")')]);
  await bk.saveAs(`${OUT}/${bk.suggestedFilename()}`);
  const backup = JSON.parse(await (await import('node:fs/promises')).readFile(`${OUT}/${bk.suggestedFilename()}`, 'utf8'));
  check('backup contains all visits and follow-ups', backup.visits.length === 4 && backup.followUps.length === 2);
  await noHorizontalScroll('settings');

  // ---- 13c. another browser profile (another user/device) sees nothing
  const ctx2 = await browser.newContext(mobile);
  const p2 = await ctx2.newPage();
  await p2.goto(BASE + '#/visits');
  await p2.waitForSelector('#passcode');
  check('different browser profile cannot see records (no shared server data)', !(await p2.content()).includes('Treasury') && (await p2.isVisible('text=Set a passcode')));
  await ctx2.close();

  check('no uncaught page errors', errors.length === 0, errors.join(' | '));
} catch (e) {
  check('unexpected failure', false, e.stack);
  await shot('zz-failure').catch(() => {});
} finally {
  await browser.close();
  server.kill();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
