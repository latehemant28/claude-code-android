import { useState } from 'react';
import { addDays, formatDate, formatTimestamp } from '../lib/dates';
import { completeFollowUp, deleteFollowUp, reopenFollowUp, rescheduleFollowUp } from '../lib/repo';
import type { FollowUp, Visit } from '../lib/types';
import { ConfirmDialog, Dialog, Field, useToast } from './ui';

export function FollowUpItem({ f, visit, today, showVisit }: { f: FollowUp; visit?: Visit; today: string; showVisit?: boolean }) {
  const toast = useToast();
  const [mode, setMode] = useState<'none' | 'complete' | 'reschedule' | 'delete'>('none');
  const [notes, setNotes] = useState('');
  const [result, setResult] = useState('');
  const [date, setDate] = useState(addDays(today, 3));
  const [reason, setReason] = useState('');
  const pending = f.status === 'Pending';
  const overdue = pending && f.dueDate < today;
  const dueToday = pending && f.dueDate === today;

  const run = async (fn: () => Promise<unknown>, msg: string) => {
    try {
      await fn();
      toast('success', msg);
      setMode('none');
    } catch (e) {
      toast('error', (e as Error).message);
    }
  };

  return (
    <div className={`rounded-lg border p-3 ${overdue ? 'border-red-300 bg-red-50' : dueToday ? 'border-amber-300 bg-amber-50' : pending ? 'border-slate-200 bg-white' : 'border-green-200 bg-green-50'}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-semibold">{f.action}</p>
          {showVisit && visit && (
            <a href={`#/visits/${visit.id}`} className="block truncate text-sm text-brand-800 underline">
              {visit.departmentName} — visit {formatDate(visit.visitDate)}
            </a>
          )}
          {f.responsible && <p className="text-sm text-slate-600">Responsible: {f.responsible}</p>}
        </div>
        <div className="shrink-0 text-right text-sm">
          <p className={`font-semibold ${overdue ? 'text-red-700' : dueToday ? 'text-amber-800' : ''}`}>
            {overdue ? 'Overdue · ' : dueToday ? 'Today · ' : ''}{formatDate(f.dueDate)}
          </p>
          <p className="text-slate-500">{f.status}</p>
        </div>
      </div>
      {f.status === 'Completed' && (f.result || f.completionNotes) && (
        <div className="mt-2 text-sm text-slate-700">
          {f.result && <p><b>Result:</b> {f.result}</p>}
          {f.completionNotes && <p><b>Notes:</b> {f.completionNotes}</p>}
          {f.completedAt && <p className="text-xs text-slate-500">Completed {formatTimestamp(f.completedAt)}</p>}
        </div>
      )}
      {f.rescheduleHistory.length > 0 && (
        <p className="mt-1 text-xs text-slate-500">
          Rescheduled {f.rescheduleHistory.length}×, last from {formatDate(f.rescheduleHistory[f.rescheduleHistory.length - 1].from)}
          {f.rescheduleHistory[f.rescheduleHistory.length - 1].reason && ` (${f.rescheduleHistory[f.rescheduleHistory.length - 1].reason})`}
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {pending ? (
          <>
            <button className="btn-primary min-h-[40px] px-3 py-1 text-sm" onClick={() => setMode('complete')}>Mark completed</button>
            <button className="btn-secondary min-h-[40px] px-3 py-1 text-sm" onClick={() => setMode('reschedule')}>Reschedule</button>
          </>
        ) : (
          <button className="btn-secondary min-h-[40px] px-3 py-1 text-sm" onClick={() => run(() => reopenFollowUp(f.id!), 'Follow-up reopened.')}>Reopen</button>
        )}
        <button className="btn-ghost min-h-[40px] px-3 py-1 text-sm text-red-700" onClick={() => setMode('delete')}>Delete</button>
      </div>

      <Dialog open={mode === 'complete'} title="Complete follow-up" onClose={() => setMode('none')}>
        <div className="space-y-3">
          <p className="text-sm text-slate-600">{f.action}</p>
          <Field label="Result" hint="e.g. 40 salary accounts agreed, proposal accepted">
            <input className="input" value={result} onChange={(e) => setResult(e.target.value)} />
          </Field>
          <Field label="Completion notes">
            <textarea className="input" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-secondary" onClick={() => setMode('none')}>Cancel</button>
            <button className="btn-primary" onClick={() => run(() => completeFollowUp(f.id!, notes, result), 'Follow-up marked completed.')}>Mark completed</button>
          </div>
        </div>
      </Dialog>

      <Dialog open={mode === 'reschedule'} title="Reschedule follow-up" onClose={() => setMode('none')}>
        <div className="space-y-3">
          <Field label="New due date" required>
            <input type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <div className="flex flex-wrap gap-2">
            {[1, 3, 7, 15].map((n) => (
              <button key={n} className="chip border-slate-300" onClick={() => setDate(addDays(today, n))}>+{n} day{n > 1 && 's'}</button>
            ))}
          </div>
          <Field label="Reason" hint="Optional, e.g. official on leave">
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-secondary" onClick={() => setMode('none')}>Cancel</button>
            <button className="btn-primary" disabled={!date} onClick={() => run(() => rescheduleFollowUp(f.id!, date, reason), `Rescheduled to ${formatDate(date)}.`)}>Reschedule</button>
          </div>
        </div>
      </Dialog>

      <ConfirmDialog open={mode === 'delete'} title="Delete follow-up?" message={`"${f.action}" will be permanently deleted.`} confirmLabel="Delete" danger
        onCancel={() => setMode('none')} onConfirm={() => run(() => deleteFollowUp(f.id!), 'Follow-up deleted.')} />
    </div>
  );
}
