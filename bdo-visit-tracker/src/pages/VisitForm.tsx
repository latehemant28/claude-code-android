import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { Combobox, type Option } from '../components/Combobox';
import { OpportunityPicker } from '../components/OpportunityPicker';
import { ConfirmDialog, Dialog, Field, Loading, PageHeader, useToast } from '../components/ui';
import { addDays, formatDate, formatTime, formatTimestamp, nowLocal } from '../lib/dates';
import { navigate } from '../lib/router';
import {
  deleteKV, distinctValues, findPossibleDuplicates, followUpsForVisit, getKV, getVisit, listDepartments, listOfficials,
  listOpportunityTypes, saveVisit, setKV,
} from '../lib/repo';
import { COMMON_OPPORTUNITIES, VISIT_STATUSES, type FollowUpDraft, type Settings, type Visit, type VisitInput } from '../lib/types';
import { normaliseKey, normaliseVisit, validateVisit, ValidationError, type FieldErrors } from '../lib/validation';

type FU = FollowUpDraft & { status?: string; key: string };
interface FormState {
  v: VisitInput;
  followUps: FU[];
}
const DRAFT_KEY = 'draft:new';
let fuSeq = 0;
const newFU = (due = ''): FU => ({ key: `n${++fuSeq}`, action: '', responsible: '', dueDate: due });

function blankVisit(s: Settings): VisitInput {
  const now = nowLocal(s.timeZone);
  return {
    departmentName: '', officialName: '', officialDesignation: '', contactNumber: '', visitDate: now.date, visitTime: now.time,
    opportunities: [], remarks: '', zone: s.defaultZone, branch: s.defaultBranch, location: '', purpose: '',
    followUpRequired: false, status: 'Open', businessGenerated: '', depositsMobilized: 0, accountsSourced: 0,
  };
}

export function VisitForm({ id, settings }: { id?: number; settings: Settings }) {
  const toast = useToast();
  const today = nowLocal(settings.timeZone).date;
  const [state, setState] = useState<FormState | null>(null);
  const [original, setOriginal] = useState<Visit | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saving, setSaving] = useState(false);
  const [draftInfo, setDraftInfo] = useState<string | null>(null);
  const [dupes, setDupes] = useState<Visit[] | null>(null);
  const [saved, setSaved] = useState<Visit | null>(null);
  const [showMore, setShowMore] = useState(false);
  const dirty = useRef(false);

  const departments = useLiveQuery(listDepartments, []) ?? [];
  const officials = useLiveQuery(listOfficials, []) ?? [];
  const oppTypes = useLiveQuery(listOpportunityTypes, []) ?? [];
  const distinct = useLiveQuery(distinctValues, []);

  // Load the visit (edit) or a saved draft / blank form (new)
  useEffect(() => {
    let alive = true;
    (async () => {
      if (id !== undefined) {
        const v = await getVisit(id);
        if (!alive) return;
        if (!v) {
          toast('error', 'That visit was not found. It may have been deleted.');
          navigate('/visits', true);
          return;
        }
        const fus = await followUpsForVisit(id);
        const { id: _i, createdAt: _c, updatedAt: _u, departmentId: _d, officialId: _o, ...rest } = v;
        void _i; void _c; void _u; void _d; void _o;
        setOriginal(v);
        setState({ v: rest, followUps: fus.map((f) => ({ id: f.id, key: `e${f.id}`, action: f.action, responsible: f.responsible, dueDate: f.dueDate, status: f.status })) });
        setShowMore(!!(v.location || v.contactNumber || v.purpose || v.businessGenerated || v.accountsSourced || v.depositsMobilized || v.status !== 'Open'));
      } else {
        const draft = await getKV<FormState & { savedAt: string }>(DRAFT_KEY);
        if (!alive) return;
        if (draft?.v) {
          setState({ v: draft.v, followUps: draft.followUps ?? [] });
          setDraftInfo(draft.savedAt);
        } else setState({ v: blankVisit(settings), followUps: [] });
      }
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Autosave drafts of new visits
  useEffect(() => {
    if (id !== undefined || !state || !dirty.current) return;
    const t = setTimeout(() => setKV(DRAFT_KEY, { ...state, savedAt: new Date().toISOString() }), 600);
    return () => clearTimeout(t);
  }, [state, id]);

  const set = useCallback(<K extends keyof VisitInput>(k: K, val: VisitInput[K]) => {
    dirty.current = true;
    setState((s) => (s ? { ...s, v: { ...s.v, [k]: val } } : s));
    setErrors((e) => {
      if (!e[k as string]) return e;
      const { [k as string]: _, ...rest } = e;
      void _;
      return rest;
    });
  }, []);
  const setFUs = (fn: (f: FU[]) => FU[]) => {
    dirty.current = true;
    setState((s) => (s ? { ...s, followUps: fn(s.followUps) } : s));
  };

  const deptOptions: Option[] = useMemo(() => departments.map((d) => ({ label: d.name, sub: [d.zone, d.branch].filter(Boolean).join(' · ') })), [departments]);
  const officialOptions: Option[] = useMemo(() => {
    const dk = normaliseKey(state?.v.departmentName ?? '');
    const sorted = [...officials].sort((a, b) => Number(normaliseKey(b.departmentName) === dk) - Number(normaliseKey(a.departmentName) === dk));
    return sorted.map((o) => ({ label: o.name, sub: `${o.designation} — ${o.departmentName}` }));
  }, [officials, state?.v.departmentName]);
  const zoneOptions: Option[] = useMemo(() => (distinct?.zones ?? []).map((z) => ({ label: z })), [distinct]);
  const oppSuggestions = useMemo(() => {
    const m = new Map<string, string>();
    for (const o of [...oppTypes.map((t) => t.name), ...COMMON_OPPORTUNITIES]) if (!m.has(o.toLowerCase())) m.set(o.toLowerCase(), o);
    return [...m.values()];
  }, [oppTypes]);

  if (!state) return <Loading />;
  const { v, followUps } = state;

  const discardDraft = async () => {
    await deleteKV(DRAFT_KEY);
    dirty.current = false;
    setDraftInfo(null);
    setErrors({});
    setState({ v: blankVisit(settings), followUps: [] });
  };

  const doSave = async () => {
    setSaving(true);
    try {
      const res = await saveVisit(v, followUps.map(({ id: fid, action, responsible, dueDate }) => ({ id: fid, action, responsible, dueDate })), id);
      dirty.current = false;
      if (id === undefined) {
        await deleteKV(DRAFT_KEY);
        setSaved(res.visit);
      } else {
        toast('success', 'Changes saved.');
        navigate(`/visits/${id}`);
      }
    } catch (e) {
      if (e instanceof ValidationError) {
        setErrors(e.errors);
        focusFirstError(e.errors);
        toast('error', 'Some fields need attention. They are marked in red.');
      } else toast('error', `Could not save: ${(e as Error).message}. Your entry is still on screen; try again.`);
    } finally {
      setSaving(false);
    }
  };

  const onSubmit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const errs = validateVisit(normaliseVisit(v), followUps.filter((f) => f.action.trim() || f.dueDate), today);
    if (Object.keys(errs).length) {
      setErrors(errs);
      focusFirstError(errs);
      toast('error', `Please fix ${Object.keys(errs).length} field${Object.keys(errs).length > 1 ? 's' : ''} marked in red.`);
      return;
    }
    const d = await findPossibleDuplicates(normaliseVisit(v), id);
    if (d.length) setDupes(d);
    else doSave();
  };

  const startAnother = () => {
    setSaved(null);
    setDraftInfo(null);
    setErrors({});
    // keep zone/branch for back-to-back visits in the same area
    setState({ v: { ...blankVisit(settings), zone: v.zone, branch: v.branch }, followUps: [] });
    window.scrollTo(0, 0);
  };

  const e = errors;
  return (
    <form onSubmit={onSubmit} noValidate className="pb-28">
      <PageHeader title={id ? 'Edit Visit' : 'Add Visit'} back={id ? `/visits/${id}` : '/'} />

      {draftInfo && (
        <div className="mb-4 flex items-center gap-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          <span className="flex-1">Restored your unsaved entry from {formatTimestamp(draftInfo, settings.timeZone)}.</span>
          <button type="button" className="btn-secondary min-h-[36px] px-3 py-1 text-sm" onClick={discardDraft}>Start fresh</button>
        </div>
      )}

      <div className="space-y-4">
        <section className="card space-y-4">
          <Field label="Name of Department / Organisation" required error={e.departmentName} htmlFor="departmentName">
            <Combobox
              id="departmentName"
              value={v.departmentName}
              onChange={(x) => set('departmentName', x)}
              onPick={(o) => {
                const d = departments.find((x) => x.name === o.label);
                if (d) {
                  if (d.zone) set('zone', d.zone);
                  if (d.branch) set('branch', d.branch);
                }
              }}
              options={deptOptions}
              placeholder="e.g. Office of the Collector, Raipur"
              invalid={!!e.departmentName}
              newLabel="New department"
            />
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Official Met — Name" required error={e.officialName} htmlFor="officialName">
              <Combobox
                id="officialName"
                value={v.officialName}
                onChange={(x) => set('officialName', x)}
                onPick={(o) => {
                  const off = officials.find((x) => x.name === o.label && `${x.designation} — ${x.departmentName}` === o.sub);
                  if (off) {
                    set('officialDesignation', off.designation);
                    if (off.contactNumber && !v.contactNumber) set('contactNumber', off.contactNumber);
                    if (!v.departmentName) set('departmentName', off.departmentName);
                  }
                }}
                options={officialOptions}
                placeholder="e.g. Shri R. K. Sharma"
                invalid={!!e.officialName}
                newLabel="New official"
              />
            </Field>
            <Field label="Designation" required error={e.officialDesignation} htmlFor="officialDesignation">
              <input id="officialDesignation" className={`input ${e.officialDesignation ? 'input-error' : ''}`} value={v.officialDesignation}
                onChange={(x) => set('officialDesignation', x.target.value)} placeholder="e.g. Treasury Officer" />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Date of Visit" required error={e.visitDate} htmlFor="visitDate">
              <input id="visitDate" type="date" max={today} className={`input ${e.visitDate ? 'input-error' : ''}`} value={v.visitDate}
                onChange={(x) => set('visitDate', x.target.value)} />
            </Field>
            <Field label="Time of Visit" required error={e.visitTime} htmlFor="visitTime">
              <input id="visitTime" type="time" className={`input ${e.visitTime ? 'input-error' : ''}`} value={v.visitTime}
                onChange={(x) => set('visitTime', x.target.value)} />
            </Field>
          </div>
          {id === undefined && (
            <p className="-mt-2 text-xs text-slate-500">
              Filled with the current date and time ({settings.timeZone}). Change them if the visit happened earlier.{' '}
              <button type="button" className="font-semibold text-brand-800 underline" onClick={() => { const n = nowLocal(settings.timeZone); set('visitDate', n.date); set('visitTime', n.time); }}>
                Use now
              </button>
            </p>
          )}
        </section>

        <section className="card space-y-4">
          <Field label="Opportunities Identified" required error={e.opportunities}>
            <OpportunityPicker value={v.opportunities} onChange={(x) => set('opportunities', x)} suggestions={oppSuggestions} invalid={!!e.opportunities} />
          </Field>
          <Field label="Remarks / Discussion Summary" required error={e.remarks} htmlFor="remarks">
            <textarea id="remarks" rows={4} className={`input ${e.remarks ? 'input-error' : ''}`} value={v.remarks}
              onChange={(x) => set('remarks', x.target.value)} placeholder="What was discussed, what was agreed, next steps" />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Zone" required error={e.zone} htmlFor="zone">
              <Combobox id="zone" value={v.zone} onChange={(x) => set('zone', x)} options={zoneOptions} placeholder="e.g. Raipur" invalid={!!e.zone} newLabel="New zone" />
            </Field>
            <Field label="Branch" hint="If applicable" htmlFor="branch">
              <input id="branch" className="input" value={v.branch} onChange={(x) => set('branch', x.target.value)} />
            </Field>
          </div>
        </section>

        <section className="card space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold">Follow-up</h2>
            <label className="flex min-h-[44px] cursor-pointer items-center gap-2">
              <span className="text-sm">Follow-up required</span>
              <input
                type="checkbox"
                id="followUpRequired"
                className="h-6 w-6 accent-brand-800"
                checked={v.followUpRequired}
                onChange={(x) => {
                  set('followUpRequired', x.target.checked);
                  if (x.target.checked) {
                    if (v.status === 'Open') set('status', 'Follow-up Pending');
                    if (!followUps.some((f) => f.status !== 'Completed')) setFUs((f) => [...f, newFU(addDays(today, 3))]);
                  }
                }}
              />
            </label>
          </div>
          {e.followUps && <p className="text-sm text-red-700" role="alert">{e.followUps}</p>}
          {followUps.map((f, i) => {
            const done = f.status === 'Completed';
            // validation indexes follow-ups after blank rows are dropped
            const ei = followUps.filter((x) => x.action.trim() || x.dueDate).indexOf(f);
            return (
              <div key={f.key} className={`space-y-3 rounded-lg border p-3 ${done ? 'border-green-200 bg-green-50' : 'border-slate-200 bg-slate-50'}`}>
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold">Follow-up {i + 1}{done && ' — completed'}</span>
                  {!done && (
                    <button type="button" className="text-sm font-semibold text-red-700" onClick={() => setFUs((l) => l.filter((x) => x.key !== f.key))}>
                      Remove
                    </button>
                  )}
                </div>
                <Field label="Action" required error={e[`followUps.${ei}.action`]}>
                  <input className="input" disabled={done} value={f.action} placeholder="e.g. Submit salary account proposal"
                    onChange={(x) => setFUs((l) => l.map((y) => (y.key === f.key ? { ...y, action: x.target.value } : y)))} />
                </Field>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Due date" required error={e[`followUps.${ei}.dueDate`]}>
                    <input type="date" className="input" disabled={done} value={f.dueDate}
                      onChange={(x) => setFUs((l) => l.map((y) => (y.key === f.key ? { ...y, dueDate: x.target.value } : y)))} />
                  </Field>
                  <Field label="Responsible">
                    <input className="input" disabled={done} value={f.responsible} placeholder="Self"
                      onChange={(x) => setFUs((l) => l.map((y) => (y.key === f.key ? { ...y, responsible: x.target.value } : y)))} />
                  </Field>
                </div>
                {!done && (
                  <div className="flex flex-wrap gap-2">
                    {[1, 3, 7, 15].map((n) => (
                      <button type="button" key={n} className="chip border-slate-300 bg-white"
                        onClick={() => setFUs((l) => l.map((y) => (y.key === f.key ? { ...y, dueDate: addDays(v.visitDate || today, n) } : y)))}>
                        +{n} day{n > 1 ? 's' : ''}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
          <button type="button" className="btn-secondary w-full" onClick={() => setFUs((l) => [...l, newFU(addDays(today, 3))])}>
            + Add follow-up action
          </button>
        </section>

        <section className="card">
          <button type="button" className="flex w-full min-h-[44px] items-center justify-between font-semibold" aria-expanded={showMore} onClick={() => setShowMore((s) => !s)}>
            More details (status, business, location, contact)
            <span aria-hidden>{showMore ? '−' : '+'}</span>
          </button>
          {showMore && (
            <div className="mt-4 space-y-4">
              <Field label="Status" error={e.status} htmlFor="status">
                <select id="status" className="input" value={v.status} onChange={(x) => set('status', x.target.value as VisitInput['status'])}>
                  {VISIT_STATUSES.map((s) => <option key={s}>{s}</option>)}
                </select>
              </Field>
              <Field label="Visit purpose" htmlFor="purpose">
                <input id="purpose" className="input" value={v.purpose} onChange={(x) => set('purpose', x.target.value)} placeholder="e.g. Salary account tie-up" />
              </Field>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Visit location" htmlFor="location">
                  <input id="location" className="input" value={v.location} onChange={(x) => set('location', x.target.value)} />
                </Field>
                <Field label="Contact number" hint="Optional" error={e.contactNumber} htmlFor="contactNumber">
                  <input id="contactNumber" type="tel" inputMode="tel" className={`input ${e.contactNumber ? 'input-error' : ''}`} value={v.contactNumber}
                    onChange={(x) => set('contactNumber', x.target.value)} />
                </Field>
              </div>
              <div className="rounded-lg bg-slate-50 p-3 text-xs text-slate-600">
                Record only business actually generated here. Opportunities above are leads, not business.
              </div>
              <Field label="Business generated" hint="e.g. 25 salary accounts opened" htmlFor="businessGenerated">
                <input id="businessGenerated" className="input" value={v.businessGenerated} onChange={(x) => set('businessGenerated', x.target.value)} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Accounts sourced" error={e.accountsSourced} htmlFor="accountsSourced">
                  <input id="accountsSourced" type="number" inputMode="numeric" min={0} step={1} className="input" value={v.accountsSourced || ''}
                    onChange={(x) => set('accountsSourced', x.target.value === '' ? 0 : Number(x.target.value))} />
                </Field>
                <Field label="Deposits mobilised (₹)" error={e.depositsMobilized} htmlFor="depositsMobilized">
                  <input id="depositsMobilized" type="number" inputMode="decimal" min={0} className="input" value={v.depositsMobilized || ''}
                    onChange={(x) => set('depositsMobilized', x.target.value === '' ? 0 : Number(x.target.value))} />
                </Field>
              </div>
            </div>
          )}
        </section>

        {original && (
          <p className="px-1 text-xs text-slate-500">
            Record created {formatTimestamp(original.createdAt, settings.timeZone)} · Last updated {formatTimestamp(original.updatedAt, settings.timeZone)}.
            These are when the entry was saved, not when the visit happened.
          </p>
        )}
        {id === undefined && <p className="px-1 text-xs text-slate-500">You can attach documents from the visit page after saving.</p>}
      </div>

      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-200 bg-white/95 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur">
        <div className="mx-auto max-w-3xl">
          <button type="submit" className="btn-primary w-full text-lg" disabled={saving}>
            {saving ? 'Saving…' : id ? 'Save Changes' : 'Save Visit'}
          </button>
        </div>
      </div>

      <ConfirmDialog
        open={!!dupes}
        title="Possible duplicate entry"
        message={
          <div>
            <p className="mb-2">You already have {dupes?.length === 1 ? 'a visit' : `${dupes?.length} visits`} to <b>{v.departmentName}</b> on {formatDate(v.visitDate)}:</p>
            <ul className="mb-2 list-disc pl-5 text-sm">
              {dupes?.map((d) => <li key={d.id}>{formatTime(d.visitTime)} — met {d.officialName}</li>)}
            </ul>
            <p>If this is a separate visit, save it anyway. Nothing will be deleted.</p>
          </div>
        }
        confirmLabel="Save anyway"
        onCancel={() => setDupes(null)}
        onConfirm={() => {
          setDupes(null);
          doSave();
        }}
      />

      <Dialog open={!!saved} title="Visit saved" onClose={() => navigate('/visits')}>
        <p className="mb-1 text-slate-700">
          <b>{saved?.departmentName}</b> on {saved && formatDate(saved.visitDate)} at {saved && formatTime(saved.visitTime)} has been saved on this device.
        </p>
        <div className="mt-5 grid gap-2">
          <button type="button" className="btn-primary" onClick={startAnother}>+ Add another visit</button>
          <button type="button" className="btn-secondary" onClick={() => navigate('/visits')}>View saved visits</button>
          <button type="button" className="btn-ghost" onClick={() => navigate(`/visits/${saved?.id}`)}>Open this visit (attach documents)</button>
        </div>
      </Dialog>
    </form>
  );
}

function focusFirstError(errs: FieldErrors) {
  const first = Object.keys(errs)[0];
  if (!first) return;
  const el = document.getElementById(first.split('.')[0]);
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) el.focus({ preventScroll: true });
}
