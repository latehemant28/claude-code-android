import { useEffect, useRef, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { Loading, ToastProvider } from './components/ui';
import { requestPersistentStorage } from './lib/db';
import { hasPasscode } from './lib/lock';
import { listFollowUpsWithVisits } from './lib/repo';
import { match, useRoute } from './lib/router';
import { useSettings } from './lib/useSettings';
import { todayISO } from './lib/dates';
import { Dashboard } from './pages/Dashboard';
import { ExportPage } from './pages/ExportPage';
import { FollowUps } from './pages/FollowUps';
import { LockScreen } from './pages/LockScreen';
import { Reports } from './pages/Reports';
import { SettingsPage } from './pages/Settings';
import { VisitDetail } from './pages/VisitDetail';
import { VisitForm } from './pages/VisitForm';
import { VisitList } from './pages/VisitList';
import type { Settings } from './lib/types';

type LockState = 'checking' | 'setup' | 'locked' | 'open';

export default function App() {
  const settings = useSettings();
  const [lock, setLock] = useState<LockState>('checking');
  const hiddenAt = useRef<number | null>(null);
  const lastActive = useRef(Date.now());

  useEffect(() => {
    hasPasscode().then((has) => setLock(has ? 'locked' : 'setup'));
  }, []);

  // Auto-lock after the configured minutes in the background or without interaction.
  useEffect(() => {
    if (lock !== 'open' || !settings) return;
    const limit = settings.autoLockMinutes * 60_000;
    const touch = () => (lastActive.current = Date.now());
    const vis = () => {
      if (document.hidden) hiddenAt.current = Date.now();
      else if (hiddenAt.current && Date.now() - hiddenAt.current > limit) setLock('locked');
    };
    const tick = setInterval(() => !document.hidden && Date.now() - lastActive.current > limit && setLock('locked'), 15_000);
    touch();
    ['pointerdown', 'keydown', 'scroll'].forEach((e) => window.addEventListener(e, touch, { passive: true }));
    document.addEventListener('visibilitychange', vis);
    return () => {
      clearInterval(tick);
      ['pointerdown', 'keydown', 'scroll'].forEach((e) => window.removeEventListener(e, touch));
      document.removeEventListener('visibilitychange', vis);
    };
  }, [lock, settings]);

  if (!settings || lock === 'checking') return <Loading />;
  if (lock === 'setup' || lock === 'locked')
    return (
      <LockScreen
        mode={lock === 'setup' ? 'setup' : 'unlock'}
        onUnlock={() => {
          setLock('open');
          requestPersistentStorage();
        }}
      />
    );
  return (
    <ToastProvider>
      <Shell settings={settings} />
    </ToastProvider>
  );
}

function Shell({ settings }: { settings: Settings }) {
  const { path, query } = useRoute();
  const [online, setOnline] = useState(navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(navigator.onLine);
    window.addEventListener('online', on);
    window.addEventListener('offline', on);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', on);
    };
  }, []);
  const today = todayISO(settings.timeZone);
  const dueCount = useLiveQuery(async () => (await listFollowUpsWithVisits()).filter((f) => f.visit && f.status === 'Pending' && f.dueDate <= today).length, [today]);

  let page: React.ReactNode;
  let m: Record<string, string> | null;
  if (path === '/' || path === '') page = <Dashboard settings={settings} />;
  else if (path === '/visits/new') page = <VisitForm key="new" settings={settings} />;
  else if ((m = match('/visits/:id/edit', path))) page = <VisitForm key={`e${m.id}`} id={Number(m.id)} settings={settings} />;
  else if ((m = match('/visits/:id', path))) page = <VisitDetail id={Number(m.id)} settings={settings} />;
  else if (path === '/visits') page = <VisitList settings={settings} initialFollowUp={(query.get('followUp') as never) || undefined} />;
  else if (path === '/followups') page = <FollowUps settings={settings} />;
  else if (path === '/export') {
    const ids = query.get('ids')?.split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0);
    page = <ExportPage key={query.toString()} settings={settings} ids={ids?.length ? ids : undefined} />;
  } else if (path === '/reports') page = <Reports settings={settings} />;
  else if (path === '/settings') page = <SettingsPage settings={settings} />;
  else page = <p className="card">Page not found. <a className="text-brand-800 underline" href="#/">Go to dashboard</a></p>;

  const hideNav = path === '/visits/new' || path.endsWith('/edit');
  const nav: [string, string, string, number?][] = [
    ['/', 'Home', 'M3 11l9-8 9 8v10a1 1 0 01-1 1h-5v-7H9v7H4a1 1 0 01-1-1z'],
    ['/visits', 'Visits', 'M4 6h16M4 12h16M4 18h10'],
    ['/visits/new', 'Add', 'M12 5v14M5 12h14'],
    ['/followups', 'Follow-ups', 'M12 8v4l3 3M21 12a9 9 0 11-18 0 9 9 0 0118 0z', dueCount],
    ['/export', 'Excel', 'M12 3v12m0 0l-4-4m4 4l4-4M4 19h16'],
  ];

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-10 bg-brand-800 pt-[env(safe-area-inset-top)] text-white shadow">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-4 py-3">
          <a href="#/" className="flex-1 font-bold tracking-tight">BDO Visit Tracker</a>
          {!online && <span className="badge bg-amber-400 text-amber-950">Offline — saving on device</span>}
          <a href="#/settings" className="rounded-lg p-2 hover:bg-white/10" aria-label="Settings">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z" /></svg>
          </a>
        </div>
      </header>
      <main className={`mx-auto max-w-3xl px-4 py-4 ${hideNav ? '' : 'pb-24'}`}>{page}</main>
      {!hideNav && (
        <nav className="fixed inset-x-0 bottom-0 z-10 border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)]" aria-label="Main">
          <ul className="mx-auto grid max-w-3xl grid-cols-5">
            {nav.map(([href, label, d, badge]) => {
              const active = href === '/' ? path === '/' || path === '' : path === href || (href === '/visits' && /^\/visits\/\d+/.test(path));
              const isAdd = href === '/visits/new';
              return (
                <li key={href}>
                  <a href={'#' + href} aria-current={active ? 'page' : undefined}
                    className={`relative flex min-h-[60px] flex-col items-center justify-center gap-0.5 text-xs font-medium ${active ? 'text-brand-800' : 'text-slate-500'}`}>
                    <span className={isAdd ? 'flex h-10 w-10 items-center justify-center rounded-full bg-brand-800 text-white shadow' : ''}>
                      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={isAdd ? 3 : 2} strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
                    </span>
                    {!isAdd && label}
                    {isAdd && <span className="sr-only">Add visit</span>}
                    {!!badge && <span className="absolute right-3 top-1.5 min-w-[18px] rounded-full bg-red-600 px-1 text-center text-[11px] font-bold text-white">{badge}</span>}
                  </a>
                </li>
              );
            })}
          </ul>
        </nav>
      )}
    </div>
  );
}
