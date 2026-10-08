import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { FollowUpItem } from '../components/FollowUpItem';
import { ConfirmDialog, Dialog, Empty, Field, Loading, PageHeader, StatusBadge, rupees, useToast } from '../components/ui';
import { addDays, formatDate, formatTime, formatTimestamp, todayISO } from '../lib/dates';
import { navigate } from '../lib/router';
import { addAttachment, addFollowUp, deleteAttachment, deleteVisit, followUpsForVisit, getVisit, listAttachments } from '../lib/repo';
import type { Attachment, Settings } from '../lib/types';

export function VisitDetail({ id, settings }: { id: number; settings: Settings }) {
  const toast = useToast();
  const today = todayISO(settings.timeZone);
  const v = useLiveQuery(() => getVisit(id).then((x) => x ?? null), [id]);
  const fus = useLiveQuery(() => followUpsForVisit(id), [id]);
  const atts = useLiveQuery(() => listAttachments(id), [id]);
  const [confirmDel, setConfirmDel] = useState(false);
  const [addingFU, setAddingFU] = useState(false);
  const [fu, setFu] = useState({ action: '', responsible: '', dueDate: addDays(today, 3) });
  const [delAtt, setDelAtt] = useState<Attachment | null>(null);

  if (v === undefined) return <Loading />;
  if (v === null) return <><PageHeader title="Visit" back="/visits" /><Empty title="Visit not found">It may have been deleted.</Empty></>;

  const row = (label: string, value: React.ReactNode) =>
    value || value === 0 ? (
      <div className="grid grid-cols-[8.5rem_1fr] gap-2 border-b border-slate-100 py-2 last:border-0">
        <dt className="text-sm text-slate-500">{label}</dt>
        <dd className="whitespace-pre-wrap break-words">{value}</dd>
      </div>
    ) : null;

  const open = (a: Attachment) => {
    const url = URL.createObjectURL(a.data);
    window.open(url, '_blank', 'noopener');
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  };

  return (
    <div>
      <PageHeader title="Visit Details" back="/visits" actions={<a className="btn-primary" href={`#/visits/${id}/edit`}>Edit</a>} />
      <div className="card mb-3">
        <div className="mb-2 flex items-start justify-between gap-2">
          <h2 className="text-lg font-bold text-brand-900">{v.departmentName}</h2>
          <StatusBadge status={v.status} />
        </div>
        <dl>
          {row('Official met', `${v.officialName}${v.officialDesignation ? `, ${v.officialDesignation}` : ''}`)}
          {row('Date & time', `${formatDate(v.visitDate)} at ${formatTime(v.visitTime)}`)}
          {row('Opportunities', <ul className="list-disc pl-4">{v.opportunities.map((o) => <li key={o}>{o}</li>)}</ul>)}
          {row('Remarks', v.remarks)}
          {row('Zone', v.zone)}
          {row('Branch', v.branch)}
          {row('Purpose', v.purpose)}
          {row('Location', v.location)}
          {row('Contact', v.contactNumber && <a className="text-brand-800 underline" href={`tel:${v.contactNumber}`}>{v.contactNumber}</a>)}
          {row('Business generated', v.businessGenerated)}
          {row('Accounts sourced', v.accountsSourced || null)}
          {row('Deposits mobilised', v.depositsMobilized ? rupees(v.depositsMobilized) : null)}
        </dl>
        <p className="mt-3 text-xs text-slate-500">
          Record #{v.id} · created {formatTimestamp(v.createdAt, settings.timeZone)} · last updated {formatTimestamp(v.updatedAt, settings.timeZone)}
        </p>
      </div>

      <section className="card mb-3">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-semibold">Follow-ups</h2>
          <button className="btn-ghost min-h-[40px] text-sm" onClick={() => setAddingFU(true)}>+ Add</button>
        </div>
        {!fus ? <Loading /> : fus.length === 0 ? <p className="text-sm text-slate-500">No follow-ups for this visit.</p> : (
          <div className="space-y-2">{fus.map((f) => <FollowUpItem key={f.id} f={f} today={today} />)}</div>
        )}
      </section>

      <section className="card mb-3">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-semibold">Attachments</h2>
          <label className="btn-ghost min-h-[40px] cursor-pointer text-sm">
            + Attach
            <input type="file" className="sr-only" multiple accept="image/*,application/pdf,.doc,.docx,.xls,.xlsx"
              onChange={async (e) => {
                for (const file of Array.from(e.target.files ?? [])) {
                  try {
                    await addAttachment(id, file);
                    toast('success', `Attached ${file.name}.`);
                  } catch (err) {
                    toast('error', (err as Error).message);
                  }
                }
                e.target.value = '';
              }} />
          </label>
        </div>
        {!atts?.length ? <p className="text-sm text-slate-500">No documents attached. Photos and PDFs are stored on this device only.</p> : (
          <ul className="divide-y">
            {atts.map((a) => (
              <li key={a.id} className="flex items-center gap-2 py-2">
                <button className="min-w-0 flex-1 truncate text-left text-brand-800 underline" onClick={() => open(a)}>{a.name}</button>
                <span className="text-xs text-slate-500">{(a.size / 1024).toFixed(0)} KB</span>
                <button className="px-2 text-sm text-red-700" onClick={() => setDelAtt(a)} aria-label={`Delete ${a.name}`}>Delete</button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="grid gap-2 sm:grid-cols-2">
        <a className="btn-secondary" href={`#/export?ids=${id}`}>Export this visit to Excel</a>
        <button className="btn-secondary text-red-700" onClick={() => setConfirmDel(true)}>Delete visit</button>
      </div>

      <ConfirmDialog
        open={confirmDel}
        title="Delete this visit?"
        message={<>The visit to <b>{v.departmentName}</b> on {formatDate(v.visitDate)}, its follow-ups and attachments will be permanently deleted. This cannot be undone.</>}
        confirmLabel="Delete permanently"
        danger
        onCancel={() => setConfirmDel(false)}
        onConfirm={async () => {
          await deleteVisit(id);
          toast('success', 'Visit deleted.');
          navigate('/visits', true);
        }}
      />
      <ConfirmDialog open={!!delAtt} title="Delete attachment?" message={delAtt?.name} confirmLabel="Delete" danger onCancel={() => setDelAtt(null)}
        onConfirm={async () => { await deleteAttachment(delAtt!.id!); setDelAtt(null); toast('success', 'Attachment deleted.'); }} />

      <Dialog open={addingFU} title="Add follow-up" onClose={() => setAddingFU(false)}>
        <div className="space-y-3">
          <Field label="Action" required><input className="input" value={fu.action} onChange={(e) => setFu({ ...fu, action: e.target.value })} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Due date" required><input type="date" className="input" value={fu.dueDate} onChange={(e) => setFu({ ...fu, dueDate: e.target.value })} /></Field>
            <Field label="Responsible"><input className="input" value={fu.responsible} onChange={(e) => setFu({ ...fu, responsible: e.target.value })} /></Field>
          </div>
          <div className="flex justify-end gap-2">
            <button className="btn-secondary" onClick={() => setAddingFU(false)}>Cancel</button>
            <button className="btn-primary" disabled={!fu.action.trim() || !fu.dueDate} onClick={async () => {
              await addFollowUp(id, fu);
              setAddingFU(false);
              setFu({ action: '', responsible: '', dueDate: addDays(today, 3) });
              toast('success', 'Follow-up added.');
            }}>Add follow-up</button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
