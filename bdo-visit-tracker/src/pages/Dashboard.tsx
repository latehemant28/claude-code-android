import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { InstallHint } from '../components/InstallHint';
import { PeriodPicker } from '../components/PeriodPicker';
import { VisitCard } from '../components/VisitCard';
import { Empty, Loading, rupees } from '../components/ui';
import { formatDate, todayISO } from '../lib/dates';
import { resolvePeriod, type Period } from '../lib/periods';
import { computeMetrics, listVisits } from '../lib/repo';
import type { Settings } from '../lib/types';

let rememberedPeriod: Period = { kind: 'month' };

export function Dashboard({ settings }: { settings: Settings }) {
  const today = todayISO(settings.timeZone);
  const [period, setPeriodState] = useState<Period>(rememberedPeriod);
  const setPeriod = (p: Period) => { rememberedPeriod = p; setPeriodState(p); };
  const r = resolvePeriod(period, today);
  const range = { from: r.from ?? '0000-00-00', to: r.to ?? '9999-12-31' };
  const m = useLiveQuery(() => computeMetrics(range), [range.from, range.to]);
  const recent = useLiveQuery(async () => (await listVisits({ sort: 'desc' })).slice(0, 5), []);

  const Tile = ({ label, value, href, tone }: { label: string; value: React.ReactNode; href?: string; tone?: 'red' | 'amber' }) => {
    const cls = `card block p-3 ${tone === 'red' ? 'border-red-300 bg-red-50' : tone === 'amber' ? 'border-amber-300 bg-amber-50' : ''}`;
    const inner = (
      <>
        <p className="text-2xl font-bold text-brand-900">{value}</p>
        <p className="text-xs font-medium leading-tight text-slate-600">{label}</p>
      </>
    );
    return href ? <a href={href} className={cls}>{inner}</a> : <div className={cls}>{inner}</div>;
  };

  return (
    <div className="space-y-5">
      <div>
        <p className="text-sm text-slate-500">{formatDate(today)}</p>
        <h1 className="text-2xl font-bold text-brand-900">{settings.bdmName ? `Welcome, ${settings.bdmName}` : 'BDO Visit Tracker'}</h1>
      </div>

      <a href="#/visits/new" className="btn-primary w-full py-4 text-lg shadow-md">+ Add Visit</a>

      <InstallHint />

      {!m ? <Loading /> : (
        <>
          {(m.followUpsOverdue > 0 || m.followUpsDueToday > 0) && (
            <a href="#/followups" className="block rounded-xl border border-red-300 bg-red-50 p-4">
              <p className="font-bold text-red-800">
                {m.followUpsOverdue > 0 && `${m.followUpsOverdue} overdue follow-up${m.followUpsOverdue > 1 ? 's' : ''}`}
                {m.followUpsOverdue > 0 && m.followUpsDueToday > 0 && ' · '}
                {m.followUpsDueToday > 0 && `${m.followUpsDueToday} due today`}
              </p>
              <p className="text-sm text-red-700">Tap to open the follow-up tracker →</p>
            </a>
          )}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Tile label="Visits today" value={m.visitsToday} />
            <Tile label="Visits this month" value={m.visitsThisMonth} />
            <Tile label="Follow-ups due today" value={m.followUpsDueToday} href="#/followups" tone={m.followUpsDueToday ? 'amber' : undefined} />
            <Tile label="Overdue follow-ups" value={m.followUpsOverdue} href="#/followups" tone={m.followUpsOverdue ? 'red' : undefined} />
          </div>

          <section className="card space-y-3">
            <h2 className="font-semibold">Period summary</h2>
            <PeriodPicker value={period} onChange={setPeriod} today={today} kinds={['today', 'week', 'month', 'pickMonth', 'fy', 'all']} idPrefix="dash" />
            <p className="text-xs text-slate-500">{r.label}</p>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              <Tile label="Visits in period" value={m.visitsInPeriod} />
              <Tile label="Departments visited" value={m.departmentsInPeriod} />
              <Tile label="Opportunities identified (leads)" value={m.opportunitiesInPeriod} />
              <Tile label="Accounts sourced" value={m.accountsSourcedInPeriod} />
              <Tile label="Deposits mobilised" value={rupees(m.depositsMobilizedInPeriod)} />
              <Tile label="Visits converted to business" value={m.convertedInPeriod} />
            </div>
            <p className="text-xs text-slate-500">Opportunities are leads identified; accounts, deposits and conversions count only business actually recorded.</p>
          </section>
        </>
      )}

      <nav className="grid grid-cols-2 gap-2" aria-label="Quick actions">
        <a className="btn-secondary" href="#/visits">View All Visits</a>
        <a className="btn-secondary" href="#/followups">Follow-up Tracker</a>
        <a className="btn-secondary" href="#/export">Generate Excel</a>
        <a className="btn-secondary" href="#/reports">Reports &amp; Analytics</a>
      </nav>

      <section>
        <div className="mb-2 flex items-center justify-between">
          <h2 className="font-semibold">Recent visits</h2>
          <a href="#/visits" className="text-sm font-semibold text-brand-800">See all →</a>
        </div>
        {!recent ? <Loading /> : recent.length === 0 ? (
          <Empty title="No visits yet">Tap “Add Visit” after your first meeting.</Empty>
        ) : (
          <ul className="space-y-2">{recent.map((v) => <li key={v.id}><VisitCard v={v} /></li>)}</ul>
        )}
      </section>
    </div>
  );
}
