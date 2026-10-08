import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { FollowUpItem } from '../components/FollowUpItem';
import { Empty, Loading, PageHeader } from '../components/ui';
import { addDays, todayISO } from '../lib/dates';
import { listFollowUpsWithVisits } from '../lib/repo';
import type { Settings } from '../lib/types';

type Tab = 'due' | 'upcoming' | 'completed';

export function FollowUps({ settings }: { settings: Settings }) {
  const today = todayISO(settings.timeZone);
  const [tab, setTab] = useState<Tab>('due');
  const rows = useLiveQuery(listFollowUpsWithVisits, []);
  if (!rows) return <Loading />;
  const live = rows.filter((r) => r.visit);
  const pending = live.filter((r) => r.status === 'Pending');
  const overdue = pending.filter((r) => r.dueDate < today);
  const dueToday = pending.filter((r) => r.dueDate === today);
  const upcoming = pending.filter((r) => r.dueDate > today);
  const completed = live.filter((r) => r.status !== 'Pending').sort((a, b) => (b.completedAt ?? b.updatedAt).localeCompare(a.completedAt ?? a.updatedAt));
  const nextWeek = addDays(today, 7);

  const tabs: [Tab, string, number][] = [
    ['due', 'Overdue & today', overdue.length + dueToday.length],
    ['upcoming', 'Upcoming', upcoming.length],
    ['completed', 'Completed', completed.length],
  ];
  const list = (items: typeof rows) => (
    <div className="space-y-2">{items.map((r) => <FollowUpItem key={r.id} f={r} visit={r.visit} today={today} showVisit />)}</div>
  );

  return (
    <div>
      <PageHeader title="Follow-up Tracker" back="/" />
      <div className="mb-4 grid grid-cols-3 gap-1 rounded-lg bg-slate-200 p-1" role="tablist">
        {tabs.map(([k, label, n]) => (
          <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}
            className={`min-h-[44px] rounded-md px-2 text-sm font-semibold ${tab === k ? 'bg-white text-brand-900 shadow' : 'text-slate-600'}`}>
            {label} ({n})
          </button>
        ))}
      </div>
      {tab === 'due' && (
        overdue.length + dueToday.length === 0 ? <Empty title="Nothing overdue or due today">Good work. Upcoming follow-ups are in the next tab.</Empty> : (
          <div className="space-y-5">
            {overdue.length > 0 && <section><h2 className="mb-2 font-bold text-red-700">Overdue ({overdue.length})</h2>{list(overdue)}</section>}
            {dueToday.length > 0 && <section><h2 className="mb-2 font-bold text-amber-800">Due today ({dueToday.length})</h2>{list(dueToday)}</section>}
          </div>
        )
      )}
      {tab === 'upcoming' && (
        upcoming.length === 0 ? <Empty title="No upcoming follow-ups" /> : (
          <div className="space-y-5">
            {upcoming.some((r) => r.dueDate <= nextWeek) && <section><h2 className="mb-2 font-bold">Next 7 days</h2>{list(upcoming.filter((r) => r.dueDate <= nextWeek))}</section>}
            {upcoming.some((r) => r.dueDate > nextWeek) && <section><h2 className="mb-2 font-bold">Later</h2>{list(upcoming.filter((r) => r.dueDate > nextWeek))}</section>}
          </div>
        )
      )}
      {tab === 'completed' && (completed.length === 0 ? <Empty title="No completed follow-ups yet" /> : list(completed))}
      <p className="mt-6 text-xs text-slate-500">
        Reminders appear here and on the dashboard when you open the app. Phone push notifications are not implemented, so check this screen daily.
      </p>
    </div>
  );
}
