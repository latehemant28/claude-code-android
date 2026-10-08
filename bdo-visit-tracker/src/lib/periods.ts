import { financialYearLabel, financialYearRange, financialYearStart, formatDate, monthRange, weekRange } from './dates';

export type PeriodKind = 'all' | 'today' | 'week' | 'month' | 'pickMonth' | 'fy' | 'custom';

export interface Period {
  kind: PeriodKind;
  from?: string;
  to?: string;
  /** YYYY-MM for pickMonth */
  month?: string;
  /** starting year for fy */
  fy?: number;
}

export const PERIOD_LABELS: Record<PeriodKind, string> = {
  all: 'All visits',
  today: "Today's visits",
  week: "This week's visits",
  month: "This month's visits",
  pickMonth: 'A specific month',
  fy: 'Financial year',
  custom: 'Custom date range',
};

/** Resolves a period to an inclusive from/to range (undefined = unbounded) and a human label. */
export function resolvePeriod(p: Period, today: string): { from?: string; to?: string; label: string; slug: string } {
  const lbl = (from: string, to: string) => (from === to ? formatDate(from) : `${formatDate(from)} to ${formatDate(to)}`);
  switch (p.kind) {
    case 'today':
      return { from: today, to: today, label: formatDate(today), slug: today };
    case 'week': {
      const r = weekRange(today);
      return { ...r, label: lbl(r.from, r.to), slug: `${r.from}_to_${r.to}` };
    }
    case 'month': {
      const r = monthRange(today);
      return { ...r, label: lbl(r.from, r.to), slug: r.from.slice(0, 7) };
    }
    case 'pickMonth': {
      const r = monthRange(`${p.month ?? today.slice(0, 7)}-01`);
      return { ...r, label: lbl(r.from, r.to), slug: r.from.slice(0, 7) };
    }
    case 'fy': {
      const y = p.fy ?? financialYearStart(today);
      const r = financialYearRange(y);
      return { ...r, label: `${financialYearLabel(y)} (${lbl(r.from, r.to)})`, slug: financialYearLabel(y).replace(' ', '') };
    }
    case 'custom': {
      const from = p.from || undefined;
      const to = p.to || undefined;
      const label = from && to ? lbl(from, to) : from ? `From ${formatDate(from)}` : to ? `Up to ${formatDate(to)}` : 'All visits';
      return { from, to, label, slug: `${from ?? 'start'}_to_${to ?? 'end'}` };
    }
    default:
      return { label: 'All visits', slug: 'all-visits' };
  }
}

export function fyOptions(today: string, earliest?: string): number[] {
  const cur = financialYearStart(today);
  const first = earliest ? Math.min(financialYearStart(earliest), cur) : cur - 2;
  const out: number[] = [];
  for (let y = cur; y >= first; y--) out.push(y);
  return out;
}
