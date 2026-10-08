import { financialYearLabel } from '../lib/dates';
import { PERIOD_LABELS, fyOptions, type Period, type PeriodKind } from '../lib/periods';

export function PeriodPicker({ value, onChange, today, kinds, earliest, idPrefix = 'period' }: {
  value: Period; onChange: (p: Period) => void; today: string; kinds: PeriodKind[]; earliest?: string; idPrefix?: string;
}) {
  return (
    <div className="space-y-3">
      <select id={`${idPrefix}-kind`} aria-label="Period" className="input" value={value.kind} onChange={(e) => onChange({ ...value, kind: e.target.value as PeriodKind })}>
        {kinds.map((k) => <option key={k} value={k}>{PERIOD_LABELS[k]}</option>)}
      </select>
      {value.kind === 'pickMonth' && (
        <input id={`${idPrefix}-month`} aria-label="Month" type="month" className="input" max={today.slice(0, 7)} value={value.month ?? today.slice(0, 7)}
          onChange={(e) => onChange({ ...value, month: e.target.value })} />
      )}
      {value.kind === 'fy' && (
        <select id={`${idPrefix}-fy`} aria-label="Financial year" className="input" value={value.fy ?? fyOptions(today)[0]} onChange={(e) => onChange({ ...value, fy: Number(e.target.value) })}>
          {fyOptions(today, earliest).map((y) => <option key={y} value={y}>{financialYearLabel(y)}</option>)}
        </select>
      )}
      {value.kind === 'custom' && (
        <div className="grid grid-cols-2 gap-3">
          <label className="text-sm">From
            <input id={`${idPrefix}-from`} type="date" className="input mt-1" value={value.from ?? ''} onChange={(e) => onChange({ ...value, from: e.target.value })} />
          </label>
          <label className="text-sm">To
            <input id={`${idPrefix}-to`} type="date" className="input mt-1" value={value.to ?? ''} onChange={(e) => onChange({ ...value, to: e.target.value })} />
          </label>
        </div>
      )}
    </div>
  );
}
