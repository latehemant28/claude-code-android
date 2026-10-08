import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { PeriodPicker } from '../components/PeriodPicker';
import { Empty, Loading, PageHeader, rupees } from '../components/ui';
import { monthLabel, todayISO } from '../lib/dates';
import { resolvePeriod, type Period } from '../lib/periods';
import { listVisits } from '../lib/repo';
import { normaliseKey } from '../lib/validation';
import { VISIT_STATUSES, type Settings, type Visit } from '../lib/types';

function countBy(vs: Visit[], key: (v: Visit) => string[]) {
  const m = new Map<string, { label: string; n: number }>();
  for (const v of vs) for (const k of key(v)) {
    const nk = normaliseKey(k);
    if (!nk) continue;
    const cur = m.get(nk) ?? { label: k, n: 0 };
    cur.n++;
    m.set(nk, cur);
  }
  return [...m.values()].sort((a, b) => b.n - a.n || a.label.localeCompare(b.label));
}

function Table({ title, rows, col }: { title: string; rows: { label: string; n: number | string }[]; col: string }) {
  return (
    <section className="card">
      <h2 className="mb-2 font-semibold">{title}</h2>
      {rows.length === 0 ? <p className="text-sm text-slate-500">No data.</p> : (
        <table className="w-full text-sm">
          <thead><tr className="border-b text-left text-slate-500"><th className="py-1 font-medium">{col}</th><th className="py-1 text-right font-medium">Count</th></tr></thead>
          <tbody>{rows.map((r) => <tr key={r.label} className="border-b border-slate-100 last:border-0"><td className="py-1.5 pr-2">{r.label}</td><td className="py-1.5 text-right font-semibold">{r.n}</td></tr>)}</tbody>
        </table>
      )}
    </section>
  );
}

export function Reports({ settings }: { settings: Settings }) {
  const today = todayISO(settings.timeZone);
  const [period, setPeriod] = useState<Period>({ kind: 'fy' });
  const r = resolvePeriod(period, today);
  const visits = useLiveQuery(() => listVisits({ from: r.from, to: r.to, sort: 'asc' }), [r.from, r.to]);

  return (
    <div className="space-y-3">
      <PageHeader title="Reports & Analytics" back="/" />
      <div className="card"><PeriodPicker value={period} onChange={setPeriod} today={today} kinds={['month', 'pickMonth', 'fy', 'custom', 'all']} idPrefix="rep" /></div>
      {!visits ? <Loading /> : visits.length === 0 ? <Empty title="No visits in this period" /> : (
        <>
          <section className="card">
            <h2 className="mb-2 font-semibold">{r.label}</h2>
            <div className="grid grid-cols-2 gap-y-1 text-sm">
              <span>Visits</span><b className="text-right">{visits.length}</b>
              <span>Departments visited</span><b className="text-right">{new Set(visits.map((v) => normaliseKey(v.departmentName))).size}</b>
              <span>Opportunities identified</span><b className="text-right">{visits.reduce((n, v) => n + v.opportunities.length, 0)}</b>
              <span>Accounts sourced</span><b className="text-right">{visits.reduce((n, v) => n + v.accountsSourced, 0)}</b>
              <span>Deposits mobilised</span><b className="text-right">{rupees(visits.reduce((n, v) => n + v.depositsMobilized, 0))}</b>
              <span>Visits converted</span><b className="text-right">{visits.filter((v) => v.status === 'Converted').length}</b>
            </div>
          </section>
          <Table title="Visits by month" col="Month" rows={countBy(visits, (v) => [v.visitDate.slice(0, 7)]).sort((a, b) => a.label.localeCompare(b.label)).map((x) => ({ ...x, label: monthLabel(x.label) }))} />
          <Table title="Opportunities identified" col="Opportunity" rows={countBy(visits, (v) => v.opportunities)} />
          <Table title="Visits by zone" col="Zone" rows={countBy(visits, (v) => [v.zone])} />
          <Table title="Most visited departments" col="Department" rows={countBy(visits, (v) => [v.departmentName]).slice(0, 15)} />
          <Table title="Visits by status" col="Status" rows={VISIT_STATUSES.map((s) => ({ label: s, n: visits.filter((v) => v.status === s).length })).filter((x) => x.n)} />
        </>
      )}
    </div>
  );
}
