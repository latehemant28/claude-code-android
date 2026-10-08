import { useEffect, useMemo, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { PeriodPicker } from '../components/PeriodPicker';
import { VisitCard } from '../components/VisitCard';
import { Empty, Loading, PageHeader } from '../components/ui';
import { todayISO } from '../lib/dates';
import { resolvePeriod, type Period } from '../lib/periods';
import { navigate } from '../lib/router';
import { distinctValues, listVisits, type FollowUpFilter, type VisitFilter } from '../lib/repo';
import { VISIT_STATUSES, type Settings, type VisitStatus } from '../lib/types';

interface ListState {
  q: string;
  period: Period;
  zone: string;
  department: string;
  opportunity: string;
  status: VisitStatus | '';
  followUp: FollowUpFilter;
  sort: 'desc' | 'asc';
}
const INITIAL: ListState = { q: '', period: { kind: 'all' }, zone: '', department: '', opportunity: '', status: '', followUp: 'any', sort: 'desc' };
// Kept for the session so filters survive opening a visit and coming back.
let remembered: ListState = INITIAL;

export function VisitList({ settings, initialFollowUp }: { settings: Settings; initialFollowUp?: FollowUpFilter }) {
  const today = todayISO(settings.timeZone);
  const [s, setS] = useState<ListState>(initialFollowUp ? { ...INITIAL, followUp: initialFollowUp } : remembered);
  const [showFilters, setShowFilters] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [q, setQ] = useState(s.q);
  useEffect(() => {
    remembered = s;
  }, [s]);
  useEffect(() => {
    const t = setTimeout(() => setS((x) => ({ ...x, q })), 200);
    return () => clearTimeout(t);
  }, [q]);

  const range = resolvePeriod(s.period, today);
  const filter: VisitFilter = { q: s.q, from: range.from, to: range.to, zone: s.zone, department: s.department, opportunity: s.opportunity, status: s.status, followUp: s.followUp, sort: s.sort };
  const visits = useLiveQuery(() => listVisits(filter), [JSON.stringify(filter)]);
  const distinct = useLiveQuery(distinctValues, []);
  const activeFilters = useMemo(
    () => [s.period.kind !== 'all', s.zone, s.department, s.opportunity, s.status, s.followUp !== 'any'].filter(Boolean).length,
    [s],
  );

  const toggle = (id: number) => setSelected((x) => {
    const n = new Set(x);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    return n;
  });

  return (
    <div className={selecting ? 'pb-24' : ''}>
      <PageHeader
        title="All Visits"
        back="/"
        actions={
          <button className="btn-ghost" onClick={() => { setSelecting((x) => !x); setSelected(new Set()); }}>
            {selecting ? 'Cancel' : 'Select'}
          </button>
        }
      />
      <div className="mb-3 flex gap-2">
        <input type="search" className="input" placeholder="Search department, official, opportunity, remarks" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search visits" />
        <button className="btn-secondary shrink-0" onClick={() => setShowFilters((x) => !x)} aria-expanded={showFilters}>
          Filters{activeFilters ? ` (${activeFilters})` : ''}
        </button>
      </div>

      {showFilters && (
        <div className="card mb-3 space-y-3">
          <PeriodPicker value={s.period} onChange={(period) => setS({ ...s, period })} today={today} kinds={['all', 'today', 'week', 'month', 'pickMonth', 'fy', 'custom']} idPrefix="list-period" />
          <div className="grid grid-cols-2 gap-3">
            <select aria-label="Zone" className="input" value={s.zone} onChange={(e) => setS({ ...s, zone: e.target.value })}>
              <option value="">All zones</option>
              {distinct?.zones.map((z) => <option key={z}>{z}</option>)}
            </select>
            <select aria-label="Status" className="input" value={s.status} onChange={(e) => setS({ ...s, status: e.target.value as VisitStatus | '' })}>
              <option value="">Any status</option>
              {VISIT_STATUSES.map((x) => <option key={x}>{x}</option>)}
            </select>
          </div>
          <select aria-label="Department" className="input" value={s.department} onChange={(e) => setS({ ...s, department: e.target.value })}>
            <option value="">All departments</option>
            {distinct?.departments.map((d) => <option key={d}>{d}</option>)}
          </select>
          <select aria-label="Opportunity" className="input" value={s.opportunity} onChange={(e) => setS({ ...s, opportunity: e.target.value })}>
            <option value="">All opportunities</option>
            {distinct?.opportunities.map((d) => <option key={d}>{d}</option>)}
          </select>
          <div className="grid grid-cols-2 gap-3">
            <select aria-label="Follow-up" className="input" value={s.followUp} onChange={(e) => setS({ ...s, followUp: e.target.value as FollowUpFilter })}>
              <option value="any">Any follow-up</option>
              <option value="open">Open follow-ups</option>
              <option value="dueToday">Due today</option>
              <option value="overdue">Overdue</option>
              <option value="none">No open follow-up</option>
            </select>
            <select aria-label="Sort" className="input" value={s.sort} onChange={(e) => setS({ ...s, sort: e.target.value as 'asc' | 'desc' })}>
              <option value="desc">Newest first</option>
              <option value="asc">Oldest first</option>
            </select>
          </div>
          <button className="btn-ghost w-full" onClick={() => { setS(INITIAL); setQ(''); }}>Clear all filters</button>
        </div>
      )}

      {!visits ? (
        <Loading />
      ) : visits.length === 0 ? (
        <Empty title={activeFilters || s.q ? 'No visits match these filters' : 'No visits recorded yet'}>
          {activeFilters || s.q ? 'Try clearing the search or filters.' : <a className="btn-primary mt-3" href="#/visits/new">+ Add your first visit</a>}
        </Empty>
      ) : (
        <>
          <div className="mb-2 flex items-center justify-between text-sm text-slate-600">
            <span>{visits.length} visit{visits.length !== 1 && 's'}{s.period.kind !== 'all' && ` · ${range.label}`}</span>
            {selecting && (
              <button className="font-semibold text-brand-800" onClick={() => setSelected(selected.size === visits.length ? new Set() : new Set(visits.map((v) => v.id!)))}>
                {selected.size === visits.length ? 'Select none' : 'Select all'}
              </button>
            )}
          </div>
          <ul className="space-y-2">
            {visits.map((v) => (
              <li key={v.id}>
                <VisitCard v={v} selectable={selecting} selected={selected.has(v.id!)} onToggle={() => toggle(v.id!)} />
              </li>
            ))}
          </ul>
        </>
      )}

      {selecting && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t bg-white p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">
          <div className="mx-auto max-w-3xl">
            <button className="btn-primary w-full" disabled={!selected.size} onClick={() => navigate(`/export?ids=${[...selected].join(',')}`)}>
              Export {selected.size} selected visit{selected.size !== 1 && 's'} to Excel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
