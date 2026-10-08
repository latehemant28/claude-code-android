import { formatDate, formatTime } from '../lib/dates';
import type { Visit } from '../lib/types';
import { StatusBadge } from './ui';

export function VisitCard({ v, selectable, selected, onToggle }: { v: Visit; selectable?: boolean; selected?: boolean; onToggle?: () => void }) {
  const body = (
    <>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate font-semibold text-brand-900">{v.departmentName}</p>
          <p className="truncate text-sm text-slate-600">{v.officialName}{v.officialDesignation && `, ${v.officialDesignation}`}</p>
        </div>
        <div className="shrink-0 text-right text-sm">
          <p className="font-semibold">{formatDate(v.visitDate)}</p>
          <p className="text-slate-500">{formatTime(v.visitTime)}</p>
        </div>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        {v.opportunities.slice(0, 3).map((o) => <span key={o} className="badge bg-brand-50 text-brand-800">{o}</span>)}
        {v.opportunities.length > 3 && <span className="badge bg-slate-100 text-slate-600">+{v.opportunities.length - 3}</span>}
        <span className="ml-auto flex items-center gap-1.5">
          <span className="text-xs text-slate-500">{v.zone}</span>
          <StatusBadge status={v.status} />
        </span>
      </div>
    </>
  );
  if (selectable)
    return (
      <label className={`card flex cursor-pointer gap-3 ${selected ? 'border-brand-600 ring-2 ring-brand-100' : ''}`}>
        <input type="checkbox" className="mt-1 h-6 w-6 shrink-0 accent-brand-800" checked={selected} onChange={onToggle} aria-label={`Select visit to ${v.departmentName} on ${formatDate(v.visitDate)}`} />
        <div className="min-w-0 flex-1">{body}</div>
      </label>
    );
  return <a href={`#/visits/${v.id}`} className="card block hover:border-brand-600">{body}</a>;
}
