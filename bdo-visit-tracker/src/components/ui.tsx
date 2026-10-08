import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import type { VisitStatus } from '../lib/types';

// ---------------------------------------------------------------- toasts

type Toast = { id: number; kind: 'success' | 'error' | 'info'; text: string };
const ToastCtx = createContext<(kind: Toast['kind'], text: string) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((kind: Toast['kind'], text: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'error' ? 7000 : 3500);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 top-2 z-50 flex flex-col items-center gap-2 px-3" aria-live="polite">
        {toasts.map((t) => (
          <div
            key={t.id}
            role={t.kind === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto w-full max-w-md rounded-lg px-4 py-3 text-sm font-medium shadow-lg ${
              t.kind === 'success' ? 'bg-green-700 text-white' : t.kind === 'error' ? 'bg-red-700 text-white' : 'bg-slate-800 text-white'
            }`}
          >
            {t.text}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

// ---------------------------------------------------------------- dialog

export function Dialog({ open, title, children, onClose }: { open: boolean; title: string; children: ReactNode; onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    ref.current?.querySelector<HTMLElement>('input,textarea,button')?.focus();
    return () => window.removeEventListener('keydown', k);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center bg-black/40 sm:items-center" onClick={onClose}>
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-t-2xl bg-white p-5 shadow-xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-3 text-lg font-bold">{title}</h2>
        {children}
      </div>
    </div>
  );
}

export function ConfirmDialog(props: {
  open: boolean; title: string; message: ReactNode; confirmLabel: string; danger?: boolean; onConfirm: () => void; onCancel: () => void;
}) {
  return (
    <Dialog open={props.open} title={props.title} onClose={props.onCancel}>
      <div className="mb-5 text-slate-700">{props.message}</div>
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button className="btn-secondary" onClick={props.onCancel}>Cancel</button>
        <button className={props.danger ? 'btn-danger' : 'btn-primary'} onClick={props.onConfirm}>{props.confirmLabel}</button>
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------- form bits

export function Field({ label, error, hint, required, children, htmlFor }: {
  label: string; error?: string; hint?: string; required?: boolean; children: ReactNode; htmlFor?: string;
}) {
  return (
    <div>
      <label className="label" htmlFor={htmlFor}>
        {label}
        {required && <span className="text-red-600"> *</span>}
      </label>
      {children}
      {hint && !error && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
      {error && <p className="mt-1 text-sm text-red-700" role="alert">{error}</p>}
    </div>
  );
}

export function PageHeader({ title, back, actions }: { title: string; back?: string; actions?: ReactNode }) {
  return (
    <div className="mb-4 flex items-center gap-2">
      {back && (
        <a href={'#' + back} className="btn-ghost -ml-2 px-2" aria-label="Back">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M15 18l-6-6 6-6" /></svg>
        </a>
      )}
      <h1 className="flex-1 text-xl font-bold text-brand-900">{title}</h1>
      {actions}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="card py-10 text-center">
      <p className="text-lg font-semibold text-slate-700">{title}</p>
      {children && <div className="mt-2 text-slate-500">{children}</div>}
    </div>
  );
}

export const Loading = () => (
  <div className="flex justify-center py-10" role="status" aria-label="Loading">
    <div className="h-8 w-8 animate-spin rounded-full border-4 border-brand-100 border-t-brand-800" />
  </div>
);

const STATUS_STYLE: Record<VisitStatus, string> = {
  Open: 'bg-slate-200 text-slate-800',
  'Follow-up Pending': 'bg-amber-100 text-amber-900',
  'In Progress': 'bg-blue-100 text-blue-900',
  Converted: 'bg-green-100 text-green-900',
  Closed: 'bg-slate-300 text-slate-700',
};
export const StatusBadge = ({ status }: { status: VisitStatus }) => <span className={`badge ${STATUS_STYLE[status]}`}>{status}</span>;

export const rupees = (n: number) => '₹' + Math.round(n).toLocaleString('en-IN');
