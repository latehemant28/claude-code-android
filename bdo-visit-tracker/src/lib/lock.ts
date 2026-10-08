import { deleteKV, getKV, setKV } from './repo';

/**
 * App passcode. Only a salted PBKDF2-SHA-256 hash is stored, never the passcode itself.
 * This keeps casual users of an unlocked phone out of the records; the phone's own screen
 * lock and encryption remain the main protection for data stored on the device.
 */
interface LockRecord {
  salt: string;
  hash: string;
  iterations: number;
}

const ITERATIONS = 210_000;
const enc = new TextEncoder();
const toHex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const fromHex = (h: string) => new Uint8Array(h.match(/../g)!.map((x) => parseInt(x, 16)));

async function derive(passcode: string, salt: Uint8Array, iterations: number) {
  const key = await crypto.subtle.importKey('raw', enc.encode(passcode), 'PBKDF2', false, ['deriveBits']);
  return toHex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256));
}

export const hasPasscode = async () => !!(await getKV<LockRecord>('lock'));

export function passcodeProblem(p: string): string | null {
  if (p.length < 6) return 'Use at least 6 characters (digits or letters).';
  if (/^(\d)\1+$/.test(p) || '0123456789'.includes(p) || '9876543210'.includes(p)) return 'Avoid repeated or sequential digits like 111111 or 123456.';
  return null;
}

export async function setPasscode(p: string) {
  const problem = passcodeProblem(p);
  if (problem) throw new Error(problem);
  const salt = crypto.getRandomValues(new Uint8Array(16));
  await setKV('lock', { salt: toHex(salt.buffer), hash: await derive(p, salt, ITERATIONS), iterations: ITERATIONS } satisfies LockRecord);
}

export async function verifyPasscode(p: string): Promise<boolean> {
  const rec = await getKV<LockRecord>('lock');
  if (!rec) return true;
  const h = await derive(p, fromHex(rec.salt), rec.iterations);
  // constant-time-ish comparison
  let diff = h.length ^ rec.hash.length;
  for (let i = 0; i < Math.min(h.length, rec.hash.length); i++) diff |= h.charCodeAt(i) ^ rec.hash.charCodeAt(i);
  return diff === 0;
}

export const removePasscode = () => deleteKV('lock');

/** Failed-attempt throttle: after 5 failures, wait 30 s, doubling on each further failure. */
export async function lockoutRemainingMs(): Promise<number> {
  const s = (await getKV<{ fails: number; until: number }>('lockFails')) ?? { fails: 0, until: 0 };
  return Math.max(0, s.until - Date.now());
}
export async function recordAttempt(ok: boolean) {
  if (ok) return deleteKV('lockFails');
  const s = (await getKV<{ fails: number; until: number }>('lockFails')) ?? { fails: 0, until: 0 };
  const fails = s.fails + 1;
  const until = fails >= 5 ? Date.now() + 30_000 * 2 ** (fails - 5) : 0;
  await setKV('lockFails', { fails, until });
}
