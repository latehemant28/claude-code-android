/**
 * Date helpers. Visit dates are stored as plain YYYY-MM-DD strings and times as HH:mm,
 * both interpreted in the configured time zone (Asia/Kolkata by default), so a record
 * never shifts day because the phone's clock zone changes.
 */
export const DEFAULT_TZ = 'Asia/Kolkata';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function parts(d: Date, tz: string) {
  const f = new Intl.DateTimeFormat('en-GB', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  });
  const o: Record<string, string> = {};
  for (const p of f.formatToParts(d)) o[p.type] = p.value;
  return o;
}

export function nowLocal(tz = DEFAULT_TZ, d = new Date()): { date: string; time: string } {
  const p = parts(d, tz);
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}` };
}

export const todayISO = (tz = DEFAULT_TZ) => nowLocal(tz).date;

export function isValidISODate(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const [y, m, d] = s.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

export const isValidTime = (s: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(s);

/** 2026-10-08 -> 08-Oct-2026 */
export function formatDate(iso: string): string {
  if (!isValidISODate(iso)) return iso || '';
  const [y, m, d] = iso.split('-');
  return `${d}-${MONTHS[Number(m) - 1]}-${y}`;
}

/** 14:05 -> 02:05 PM */
export function formatTime(t: string): string {
  if (!isValidTime(t)) return t || '';
  const [h, m] = t.split(':').map(Number);
  const ap = h >= 12 ? 'PM' : 'AM';
  return `${String(h % 12 || 12).padStart(2, '0')}:${String(m).padStart(2, '0')} ${ap}`;
}

/** ISO timestamp -> "08-Oct-2026 02:05 PM" in tz */
export function formatTimestamp(ts: string, tz = DEFAULT_TZ): string {
  if (!ts) return '';
  const { date, time } = nowLocal(tz, new Date(ts));
  return `${formatDate(date)} ${formatTime(time)}`;
}

export function addDays(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + n));
  return dt.toISOString().slice(0, 10);
}

/** Monday-based week containing `iso`. */
export function weekRange(iso: string): { from: string; to: string } {
  const [y, m, d] = iso.split('-').map(Number);
  const dow = (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7; // Mon=0
  const from = addDays(iso, -dow);
  return { from, to: addDays(from, 6) };
}

export function monthRange(iso: string): { from: string; to: string } {
  const [y, m] = iso.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const mm = String(m).padStart(2, '0');
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(last).padStart(2, '0')}` };
}

/** Indian financial year (1 April - 31 March). Returns the starting year, e.g. 2026 for FY 2026-27. */
export function financialYearStart(iso: string): number {
  const [y, m] = iso.split('-').map(Number);
  return m >= 4 ? y : y - 1;
}

export function financialYearRange(startYear: number): { from: string; to: string } {
  return { from: `${startYear}-04-01`, to: `${startYear + 1}-03-31` };
}

export const financialYearLabel = (startYear: number) => `FY ${startYear}-${String((startYear + 1) % 100).padStart(2, '0')}`;

export function monthLabel(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  return `${MONTHS[m - 1]} ${y}`;
}
