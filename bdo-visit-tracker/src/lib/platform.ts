import { Capacitor } from '@capacitor/core';

/**
 * Where the app is running and how files leave it. The same web code runs as:
 *  - a website / home-screen web app (Chrome on Android, Safari on iPhone), or
 *  - a native app (Capacitor) on Android or iOS.
 * Browsers save files with a download link; native apps cannot, so there files are written to
 * the app's Documents folder and handed to the system share sheet (Save to Files, Drive, Mail...).
 */
export const isNative = () => Capacitor.isNativePlatform();
export const platform = () => Capacitor.getPlatform() as 'web' | 'android' | 'ios';

export const isIOSDevice = () =>
  /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

/** Running from the home screen (installed web app) rather than a browser tab. */
export const isStandalone = () =>
  window.matchMedia?.('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;

async function blobToBase64(b: Blob): Promise<string> {
  const bytes = new Uint8Array(await b.arrayBuffer());
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Native only: writes the file and returns its URI. Documents is visible in the iOS Files app. */
async function writeNative(blob: Blob, fileName: string): Promise<{ uri: string; where: string }> {
  const { Filesystem, Directory } = await import('@capacitor/filesystem');
  const data = await blobToBase64(blob);
  const path = `BDO Visit Tracker/${fileName}`;
  try {
    const r = await Filesystem.writeFile({ path, data, directory: Directory.Documents, recursive: true });
    return { uri: r.uri, where: platform() === 'ios' ? 'Files app › On My iPhone › BDO Visit Tracker' : 'Documents › BDO Visit Tracker' };
  } catch {
    // Some Android versions refuse shared-storage writes; the app cache always works.
    const r = await Filesystem.writeFile({ path: fileName, data, directory: Directory.Cache, recursive: true });
    return { uri: r.uri, where: '' };
  }
}

async function shareNative(uri: string, title: string) {
  const { Share } = await import('@capacitor/share');
  try {
    await Share.share({ title, files: [uri], dialogTitle: title });
    return 'shared' as const;
  } catch (e) {
    if (/cancel/i.test(String((e as Error)?.message ?? e))) return 'cancelled' as const;
    throw e;
  }
}

/**
 * Saves a generated file. Returns a short message describing where it went.
 * Web: browser download. Native: saved in Documents (share sheet opens if that is not possible).
 */
export async function saveFile(blob: Blob, fileName: string, title: string): Promise<string> {
  if (isNative()) {
    const { uri, where } = await writeNative(blob, fileName);
    if (where) return `Saved to ${where}.`;
    await shareNative(uri, title);
    return 'Choose where to save the file.';
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
  return isIOSDevice() ? 'If Safari asks, tap Download, then find it in the Files app › Downloads.' : 'Saved to your Downloads folder.';
}

/** Opens the share sheet (WhatsApp, Mail, Drive, Save to Files...). Must be called from a tap. */
export async function shareFile(blob: Blob, fileName: string, title: string): Promise<'shared' | 'unsupported' | 'cancelled'> {
  if (isNative()) {
    const { uri } = await writeNative(blob, fileName);
    return shareNative(uri, title);
  }
  const file = new File([blob], fileName, { type: blob.type });
  const nav = navigator as Navigator & { canShare?: (d: ShareData) => boolean };
  if (!nav.share || !nav.canShare?.({ files: [file] })) return 'unsupported';
  try {
    await nav.share({ files: [file], title });
    return 'shared';
  } catch (e) {
    if ((e as DOMException)?.name === 'AbortError') return 'cancelled';
    throw e;
  }
}

/** Opens an attachment for viewing. */
export async function openFile(blob: Blob, fileName: string) {
  if (isNative()) {
    await shareFile(blob, fileName, fileName);
    return;
  }
  const url = URL.createObjectURL(blob);
  // iOS home-screen apps cannot open new windows reliably; navigate a link instead.
  const a = document.createElement('a');
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener';
  if (isIOSDevice()) a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
