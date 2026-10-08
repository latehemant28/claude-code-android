import { useEffect, useState } from 'react';
import { isIOSDevice, isNative, isStandalone } from '../lib/platform';

type BIPEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }> };
let deferred: BIPEvent | null = null;
if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferred = e as BIPEvent;
  });
}

const KEY = 'installHintDismissed';
const dismissed = () => {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
};

/**
 * Asks browser users to install the app. On iPhone this matters for data safety: Safari may
 * clear data of websites not opened for 7 days, but apps added to the Home Screen are exempt.
 */
export function InstallHint() {
  const [hidden, setHidden] = useState(() => isNative() || isStandalone() || dismissed());
  const [canPrompt, setCanPrompt] = useState(!!deferred);
  useEffect(() => {
    const on = () => setCanPrompt(true);
    window.addEventListener('beforeinstallprompt', on);
    return () => window.removeEventListener('beforeinstallprompt', on);
  }, []);
  if (hidden) return null;
  const ios = isIOSDevice();
  if (!ios && !canPrompt) return null;

  const close = () => {
    try {
      localStorage.setItem(KEY, '1');
    } catch {
      /* ignore */
    }
    setHidden(true);
  };

  return (
    <div className={`rounded-xl border p-4 text-sm ${ios ? 'border-amber-300 bg-amber-50 text-amber-950' : 'border-brand-100 bg-brand-50 text-brand-900'}`} role="note">
      {ios ? (
        <>
          <p className="font-bold">Add this app to your Home Screen</p>
          <p className="mt-1">
            In Safari tap <b>Share</b> <span aria-hidden>(□↑)</span> → <b>Add to Home Screen</b>, then open it from the new icon.
            Safari can delete data of websites not opened for 7 days; the Home Screen app keeps your visits safe and works offline.
          </p>
        </>
      ) : (
        <>
          <p className="font-bold">Install the app</p>
          <p className="mt-1">Opens full-screen from your home screen and works offline.</p>
          <button
            className="btn-primary mt-3"
            onClick={async () => {
              await deferred?.prompt();
              deferred = null;
              close();
            }}
          >
            Install
          </button>
        </>
      )}
      <button className="mt-2 block text-xs underline" onClick={close}>Don't show again</button>
    </div>
  );
}
