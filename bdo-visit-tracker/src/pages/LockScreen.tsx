import { useEffect, useState } from 'react';
import { ConfirmDialog } from '../components/ui';
import { db } from '../lib/db';
import { lockoutRemainingMs, passcodeProblem, recordAttempt, setPasscode, verifyPasscode } from '../lib/lock';

export function LockScreen({ mode, onUnlock }: { mode: 'setup' | 'unlock'; onUnlock: () => void }) {
  const [p1, setP1] = useState('');
  const [p2, setP2] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [wait, setWait] = useState(0);
  const [erase, setErase] = useState(false);

  useEffect(() => {
    lockoutRemainingMs().then(setWait);
  }, []);
  useEffect(() => {
    if (wait <= 0) return;
    const t = setTimeout(() => setWait((w) => Math.max(0, w - 1000)), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr('');
    try {
      if (mode === 'setup') {
        const prob = passcodeProblem(p1);
        if (prob) return setErr(prob);
        if (p1 !== p2) return setErr('The two passcodes do not match. Type the same passcode twice.');
        await setPasscode(p1);
        onUnlock();
      } else {
        const remaining = await lockoutRemainingMs();
        if (remaining > 0) return setWait(remaining);
        const ok = await verifyPasscode(p1);
        await recordAttempt(ok);
        if (ok) onUnlock();
        else {
          setP1('');
          setErr('Wrong passcode. Try again.');
          setWait(await lockoutRemainingMs());
        }
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-brand-900 p-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-2xl bg-white p-6 shadow-xl">
        <div className="text-center">
          <img src="./icon-192.png" alt="" className="mx-auto mb-3 h-16 w-16 rounded-2xl" />
          <h1 className="text-xl font-bold text-brand-900">BDO Visit Tracker</h1>
          <p className="mt-1 text-sm text-slate-600">
            {mode === 'setup'
              ? 'Set a passcode to protect your visit records. You will need it each time you open the app.'
              : 'Enter your passcode to continue.'}
          </p>
        </div>
        <input id="passcode" type="password" autoComplete={mode === 'setup' ? 'new-password' : 'current-password'} className="input text-center text-xl tracking-widest"
          placeholder="Passcode" value={p1} onChange={(e) => setP1(e.target.value)} autoFocus aria-label="Passcode" />
        {mode === 'setup' && (
          <input id="passcode2" type="password" autoComplete="new-password" className="input text-center text-xl tracking-widest"
            placeholder="Repeat passcode" value={p2} onChange={(e) => setP2(e.target.value)} aria-label="Repeat passcode" />
        )}
        {err && <p className="text-sm text-red-700" role="alert">{err}</p>}
        {wait > 0 && <p className="text-sm text-amber-800" role="alert">Too many wrong attempts. Try again in {Math.ceil(wait / 1000)} s.</p>}
        <button type="submit" className="btn-primary w-full" disabled={busy || !p1 || wait > 0}>
          {mode === 'setup' ? 'Set passcode' : 'Unlock'}
        </button>
        {mode === 'setup' ? (
          <p className="text-xs text-slate-500">There is no passcode recovery. If you forget it, the only option is to erase the data on this device, so keep regular backups.</p>
        ) : (
          <button type="button" className="w-full text-center text-xs text-slate-500 underline" onClick={() => setErase(true)}>Forgot passcode?</button>
        )}
      </form>
      <ConfirmDialog open={erase} title="Forgot passcode" danger confirmLabel="Erase all data on this device"
        message="The passcode cannot be recovered. You can erase all visit records on this device and start again, then restore your latest backup file from Settings. Unsaved records will be lost permanently."
        onCancel={() => setErase(false)}
        onConfirm={async () => {
          await db.delete();
          window.location.reload();
        }} />
    </div>
  );
}
