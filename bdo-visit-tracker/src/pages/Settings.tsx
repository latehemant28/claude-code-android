import { useEffect, useState } from 'react';
import { ConfirmDialog, Dialog, Field, PageHeader, useToast } from '../components/ui';
import { createBackup, parseBackup, restoreBackup, type Backup } from '../lib/backup';
import { saveFile } from '../lib/platform';
import { db, requestPersistentStorage } from '../lib/db';
import { todayISO } from '../lib/dates';
import { passcodeProblem, setPasscode, verifyPasscode } from '../lib/lock';
import { getKV, saveSettings, setKV } from '../lib/repo';
import type { Settings as S } from '../lib/types';

export function SettingsPage({ settings }: { settings: S }) {
  const toast = useToast();
  const [form, setForm] = useState(settings);
  const [storage, setStorage] = useState<{ persisted: boolean | null; usage?: number; quota?: number }>({ persisted: null });
  const [lastBackup, setLastBackup] = useState<string | undefined>();
  const [pending, setPending] = useState<Backup | null>(null);
  const [pc, setPc] = useState<{ open: boolean; cur: string; next: string; again: string; err: string }>({ open: false, cur: '', next: '', again: '', err: '' });
  const [counts, setCounts] = useState({ visits: 0, followUps: 0 });

  useEffect(() => {
    (async () => {
      const est = await navigator.storage?.estimate?.();
      const persisted = navigator.storage?.persisted ? await navigator.storage.persisted() : null;
      setStorage({ persisted, usage: est?.usage, quota: est?.quota });
      setLastBackup(await getKV<string>('lastBackupAt'));
      setCounts({ visits: await db.visits.count(), followUps: await db.followUps.count() });
    })();
  }, []);

  const set = <K extends keyof S>(k: K, v: S[K]) => setForm((f) => ({ ...f, [k]: v }));
  const saveReport = async () => {
    await saveSettings(form);
    toast('success', 'Settings saved.');
  };

  const doBackup = async () => {
    const b = await createBackup(true);
    const blob = new Blob([JSON.stringify(b)], { type: 'application/json' });
    const where = await saveFile(blob, `bdo-visits-backup-${todayISO(settings.timeZone)}.json`, 'BDO Visit Tracker backup');
    await setKV('lastBackupAt', b.exportedAt);
    setLastBackup(b.exportedAt);
    toast('success', `Backup created (${b.visits.length} visits). ${where} Keep it somewhere safe and private.`);
  };

  const fullExcel = async () => {
    const { buildFullDataWorkbook, workbookToBlob } = await import('../lib/excel');
    const wb = buildFullDataWorkbook(await db.visits.orderBy('visitDate').toArray(), await db.followUps.toArray(), settings.timeZone);
    toast('info', await saveFile(await workbookToBlob(wb), `bdo-visits-all-data-${todayISO(settings.timeZone)}.xlsx`, 'All visit data'));
  };

  const pickFile = async (f?: File) => {
    if (!f) return;
    try {
      setPending(parseBackup(await f.text()));
    } catch (e) {
      toast('error', (e as Error).message);
    }
  };
  const restore = async (mode: 'merge' | 'replace') => {
    try {
      const r = await restoreBackup(pending!, mode);
      toast('success', `Restore complete: ${r.added} visits added${r.skipped ? `, ${r.skipped} already present and skipped` : ''}.`);
      setPending(null);
      setCounts({ visits: await db.visits.count(), followUps: await db.followUps.count() });
    } catch (e) {
      toast('error', `Restore failed, nothing was changed: ${(e as Error).message}`);
    }
  };

  const changePasscode = async () => {
    if (!(await verifyPasscode(pc.cur))) return setPc({ ...pc, err: 'Current passcode is wrong.' });
    const p = passcodeProblem(pc.next);
    if (p) return setPc({ ...pc, err: p });
    if (pc.next !== pc.again) return setPc({ ...pc, err: 'The new passcodes do not match.' });
    await setPasscode(pc.next);
    setPc({ open: false, cur: '', next: '', again: '', err: '' });
    toast('success', 'Passcode changed.');
  };

  const mb = (n?: number) => (n === undefined ? '?' : `${(n / 1024 / 1024).toFixed(1)} MB`);
  const daysSinceBackup = lastBackup ? Math.floor((Date.now() - Date.parse(lastBackup)) / 86400000) : null;

  return (
    <div className="space-y-4 pb-6">
      <PageHeader title="Settings" back="/" />

      <section className="card space-y-3">
        <h2 className="font-semibold">Report &amp; signatures</h2>
        <Field label="Report title"><input className="input" value={form.reportTitle} onChange={(e) => set('reportTitle', e.target.value)} /></Field>
        <Field label="Office / zone line (optional)" hint="Shown under the title, e.g. Raipur Zone"><input className="input" value={form.organisationName} onChange={(e) => set('organisationName', e.target.value)} /></Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Your name (BDM)"><input className="input" value={form.bdmName} onChange={(e) => set('bdmName', e.target.value)} /></Field>
          <Field label="Your designation line"><input className="input" value={form.bdmDesignation} onChange={(e) => set('bdmDesignation', e.target.value)} /></Field>
          <Field label="Reporting manager's name"><input className="input" value={form.zmName} onChange={(e) => set('zmName', e.target.value)} /></Field>
          <Field label="Reporting manager's designation line"><input className="input" value={form.zmDesignation} onChange={(e) => set('zmDesignation', e.target.value)} /></Field>
          <Field label="Left signature caption"><input className="input" value={form.bdmSignatureLabel} onChange={(e) => set('bdmSignatureLabel', e.target.value)} /></Field>
          <Field label="Right signature caption"><input className="input" value={form.zmSignatureLabel} onChange={(e) => set('zmSignatureLabel', e.target.value)} /></Field>
          <Field label="Default zone for new visits"><input className="input" value={form.defaultZone} onChange={(e) => set('defaultZone', e.target.value)} /></Field>
          <Field label="Default branch for new visits"><input className="input" value={form.defaultBranch} onChange={(e) => set('defaultBranch', e.target.value)} /></Field>
        </div>
        <label className="flex min-h-[44px] items-center gap-3">
          <input type="checkbox" className="h-6 w-6 accent-brand-800" checked={form.includeTimeColumn} onChange={(e) => set('includeTimeColumn', e.target.checked)} />
          Include “Time of Visit” column in Excel by default
        </label>
        <Field label="Auto-lock after (minutes in background / idle)">
          <select className="input" value={form.autoLockMinutes} onChange={(e) => set('autoLockMinutes', Number(e.target.value))}>
            {[1, 2, 5, 10, 15, 30].map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
        </Field>
        <p className="text-xs text-slate-500">Time zone: {form.timeZone}</p>
        <button className="btn-primary w-full" onClick={saveReport}>Save settings</button>
      </section>

      <section className="card space-y-3">
        <h2 className="font-semibold">Backup &amp; restore</h2>
        <p className="text-sm text-slate-600">
          {counts.visits} visits and {counts.followUps} follow-ups are stored on this device only.{' '}
          {lastBackup ? `Last backup: ${daysSinceBackup === 0 ? 'today' : `${daysSinceBackup} day(s) ago`}.` : <b className="text-red-700">No backup taken yet.</b>}
        </p>
        <p className="text-xs text-slate-500">
          If you clear browser data, uninstall the browser or lose the phone, records without a backup cannot be recovered. Take a backup weekly and store it as your bank's data policy allows.
        </p>
        <button className="btn-primary w-full" onClick={doBackup}>Download full backup (.json)</button>
        <button className="btn-secondary w-full" onClick={fullExcel}>Download all data as Excel (all fields)</button>
        <label className="btn-secondary w-full cursor-pointer">
          Restore from backup file…
          <input type="file" accept="application/json,.json" className="sr-only" onChange={(e) => { pickFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
      </section>

      <section className="card space-y-2">
        <h2 className="font-semibold">Security &amp; storage</h2>
        <p className="text-sm text-slate-600">
          Persistent storage: {storage.persisted === null ? 'not supported by this browser' : storage.persisted ? 'granted (browser will not auto-delete data)' : 'not granted yet'}
          {' · '}Using {mb(storage.usage)}
        </p>
        {storage.persisted === false && (
          <button className="btn-secondary w-full" onClick={async () => setStorage({ ...storage, persisted: !!(await requestPersistentStorage()) })}>
            Request persistent storage
          </button>
        )}
        <button className="btn-secondary w-full" onClick={() => setPc({ ...pc, open: true })}>Change passcode</button>
      </section>

      <Dialog open={!!pending} title="Restore backup" onClose={() => setPending(null)}>
        <p className="mb-4 text-slate-700">
          Backup from {pending && new Date(pending.exportedAt).toLocaleString('en-IN')} with {pending?.visits.length} visits and {pending?.followUps.length} follow-ups.
        </p>
        <div className="grid gap-2">
          <button className="btn-primary" onClick={() => restore('merge')}>Merge (keep current data, add missing visits)</button>
          <ReplaceButton onConfirm={() => restore('replace')} />
          <button className="btn-ghost" onClick={() => setPending(null)}>Cancel</button>
        </div>
      </Dialog>

      <Dialog open={pc.open} title="Change passcode" onClose={() => setPc({ ...pc, open: false })}>
        <div className="space-y-3">
          <Field label="Current passcode"><input type="password" className="input" value={pc.cur} onChange={(e) => setPc({ ...pc, cur: e.target.value, err: '' })} /></Field>
          <Field label="New passcode" hint="At least 6 characters"><input type="password" className="input" value={pc.next} onChange={(e) => setPc({ ...pc, next: e.target.value, err: '' })} /></Field>
          <Field label="New passcode again" error={pc.err}><input type="password" className="input" value={pc.again} onChange={(e) => setPc({ ...pc, again: e.target.value, err: '' })} /></Field>
          <div className="flex justify-end gap-2">
            <button className="btn-secondary" onClick={() => setPc({ ...pc, open: false })}>Cancel</button>
            <button className="btn-primary" onClick={changePasscode}>Change</button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

function ReplaceButton({ onConfirm }: { onConfirm: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn-secondary text-red-700" onClick={() => setOpen(true)}>Replace everything on this device</button>
      <ConfirmDialog open={open} title="Replace all data?" danger confirmLabel="Replace all data"
        message="All visits, follow-ups and attachments currently on this device will be deleted and replaced by the backup. Take a backup of the current data first if you may need it."
        onCancel={() => setOpen(false)} onConfirm={() => { setOpen(false); onConfirm(); }} />
    </>
  );
}
