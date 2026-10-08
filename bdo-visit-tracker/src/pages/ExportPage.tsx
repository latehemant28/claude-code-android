import { useEffect, useMemo, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { PeriodPicker } from '../components/PeriodPicker';
import { Field, Loading, PageHeader, useToast } from '../components/ui';
import { downloadBlob, shareFile } from '../lib/backup';
import { formatDate, todayISO } from '../lib/dates';
import { reportColumns, reportFileName, reportRow } from '../lib/reportLayout';
import { resolvePeriod, type Period } from '../lib/periods';
import { listVisits, saveSettings } from '../lib/repo';
import { reportGaps } from '../lib/validation';
import type { Settings } from '../lib/types';

export function ExportPage({ settings, ids }: { settings: Settings; ids?: number[] }) {
  const toast = useToast();
  const today = todayISO(settings.timeZone);
  const [useSelection, setUseSelection] = useState(!!ids?.length);
  const [period, setPeriod] = useState<Period>({ kind: 'month' });
  const [includeTime, setIncludeTime] = useState(settings.includeTimeColumn);
  const [ackGaps, setAckGaps] = useState(false);
  const [busy, setBusy] = useState(false);
  const [file, setFile] = useState<{ blob: Blob; name: string } | null>(null);
  const [sig, setSig] = useState({ bdmName: settings.bdmName, bdmDesignation: settings.bdmDesignation, zmName: settings.zmName, zmDesignation: settings.zmDesignation });
  const [showSig, setShowSig] = useState(!settings.bdmName);

  const r = useSelection
    ? { from: undefined, to: undefined, label: '', slug: 'selected-visits' }
    : resolvePeriod(period, today);
  const visits = useLiveQuery(
    () => listVisits(useSelection ? { ids, sort: 'asc' } : { from: r.from, to: r.to, sort: 'asc' }),
    [useSelection, ids?.join(','), r.from, r.to],
  );
  // For selections, the report period spans the selected visits' dates.
  const periodLabel = useMemo(() => {
    if (!useSelection) return r.label;
    if (!visits?.length) return 'Selected visits';
    const a = visits[0].visitDate;
    const b = visits[visits.length - 1].visitDate;
    return a === b ? formatDate(a) : `${formatDate(a)} to ${formatDate(b)}`;
  }, [useSelection, r.label, visits]);

  const gaps = useMemo(() => (visits ?? []).map((v) => ({ v, missing: reportGaps(v) })).filter((g) => g.missing.length), [visits]);
  const custInvalid = !useSelection && period.kind === 'custom' && period.from && period.to && period.from > period.to;

  useEffect(() => {
    setFile(null);
    setAckGaps(false);
  }, [visits, includeTime, sig]);

  const generate = async () => {
    if (!visits?.length) return;
    setBusy(true);
    try {
      const s = await saveSettings({ ...sig, includeTimeColumn: includeTime });
      const { buildReportWorkbook, workbookToBlob } = await import('../lib/excel');
      const wb = buildReportWorkbook(visits, s, { periodLabel, includeTime });
      const blob = await workbookToBlob(wb);
      const slug = useSelection && visits.length ? `${visits[0].visitDate}_to_${visits[visits.length - 1].visitDate}_selected` : r.slug;
      const name = reportFileName(slug, includeTime);
      setFile({ blob, name });
      downloadBlob(blob, name);
      toast('success', `Excel report created with ${visits.length} visit${visits.length > 1 ? 's' : ''}: ${name}`);
    } catch (e) {
      toast('error', `Could not create the Excel file: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  const share = async () => {
    if (!file) return;
    try {
      const res = await shareFile(file.blob, file.name, settings.reportTitle);
      if (res === 'unsupported') toast('info', 'Sharing files is not supported in this browser. Use Download, then share the file from your Downloads folder.');
    } catch (e) {
      toast('error', `Sharing failed: ${(e as Error).message}`);
    }
  };

  const cols = reportColumns(includeTime);
  return (
    <div className="space-y-3 pb-6">
      <PageHeader title="Export to Excel" back="/" />

      <section className="card space-y-3">
        <h2 className="font-semibold">1. Choose visits</h2>
        {ids?.length ? (
          <div className="flex gap-2">
            <button className={useSelection ? 'btn-primary flex-1' : 'btn-secondary flex-1'} onClick={() => setUseSelection(true)}>Selected ({ids.length})</button>
            <button className={!useSelection ? 'btn-primary flex-1' : 'btn-secondary flex-1'} onClick={() => setUseSelection(false)}>By date</button>
          </div>
        ) : (
          <p className="text-xs text-slate-500">To export hand-picked visits, use “Select” on the All Visits screen.</p>
        )}
        {!useSelection && (
          <PeriodPicker value={period} onChange={setPeriod} today={today} kinds={['today', 'week', 'month', 'pickMonth', 'custom', 'fy', 'all']} idPrefix="export" />
        )}
        {custInvalid && <p className="text-sm text-red-700" role="alert">The “From” date is after the “To” date. Swap them.</p>}
      </section>

      <section className="card space-y-3">
        <h2 className="font-semibold">2. Report options</h2>
        <label className="flex min-h-[44px] cursor-pointer items-center gap-3">
          <input type="checkbox" id="includeTime" className="h-6 w-6 accent-brand-800" checked={includeTime} onChange={(e) => setIncludeTime(e.target.checked)} />
          <span>Include a “Time of Visit” column <span className="block text-xs text-slate-500">Off = standard 7-column Annexure I. Times stay saved either way.</span></span>
        </label>
        <button className="flex w-full min-h-[44px] items-center justify-between font-medium" aria-expanded={showSig} onClick={() => setShowSig((x) => !x)}>
          Signature names {sig.bdmName || sig.zmName ? `(${[sig.bdmName, sig.zmName].filter(Boolean).join(' / ')})` : '(not set)'}
          <span aria-hidden>{showSig ? '−' : '+'}</span>
        </button>
        {showSig && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Business Development Manager — name"><input className="input" value={sig.bdmName} onChange={(e) => setSig({ ...sig, bdmName: e.target.value })} /></Field>
            <Field label="BDM — designation / branch line" hint="Optional"><input className="input" value={sig.bdmDesignation} onChange={(e) => setSig({ ...sig, bdmDesignation: e.target.value })} /></Field>
            <Field label="DGM & Zonal Manager — name"><input className="input" value={sig.zmName} onChange={(e) => setSig({ ...sig, zmName: e.target.value })} /></Field>
            <Field label="Zonal Manager — designation / zone line" hint="Optional"><input className="input" value={sig.zmDesignation} onChange={(e) => setSig({ ...sig, zmDesignation: e.target.value })} /></Field>
            <p className="text-xs text-slate-500 sm:col-span-2">Saved for future reports. Leave names blank to sign by hand.</p>
          </div>
        )}
      </section>

      <section className="card space-y-3">
        <h2 className="font-semibold">3. Check &amp; preview</h2>
        {!visits ? <Loading /> : (
          <>
            <div className="rounded-lg bg-brand-50 p-3 text-sm" data-testid="export-summary">
              <p><b>Period:</b> {periodLabel}</p>
              <p><b>Visits included:</b> {visits.length}</p>
              <p><b>Columns:</b> {cols.length} ({includeTime ? 'with time' : 'standard'})</p>
            </div>
            {visits.length === 0 && <p className="text-sm text-slate-600">No visits in this period. Choose a different period.</p>}
            {gaps.length > 0 && (
              <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm" role="alert">
                <p className="mb-2 font-semibold text-amber-900">{gaps.length} visit{gaps.length > 1 && 's'} {gaps.length > 1 ? 'have' : 'has'} empty report fields:</p>
                <ul className="mb-2 space-y-1">
                  {gaps.map(({ v, missing }) => (
                    <li key={v.id}>
                      <a className="text-brand-800 underline" href={`#/visits/${v.id}/edit`}>{v.departmentName || '(no department)'} — {formatDate(v.visitDate)}</a>: missing {missing.join(', ')}
                    </li>
                  ))}
                </ul>
                <label className="flex items-center gap-2">
                  <input type="checkbox" className="h-5 w-5" checked={ackGaps} onChange={(e) => setAckGaps(e.target.checked)} /> Export anyway with blank cells
                </label>
              </div>
            )}
            {visits.length > 0 && (
              <div className="max-h-[60vh] overflow-auto rounded border border-slate-300 bg-white p-2" aria-label="Report preview">
                <div className="min-w-[760px] text-[12px] text-black">
                  <p className="text-center text-sm font-bold">{settings.reportTitle}</p>
                  <p className="mb-2 text-center italic">{[settings.organisationName, `Period: ${periodLabel}`, `Total visits: ${visits.length}`].filter(Boolean).join('  |  ')}</p>
                  <table className="report-preview w-full border-collapse">
                    <thead><tr className="bg-[#D9E1F2]">{cols.map((c) => <th key={c.header} style={{ width: `${c.width}ch` }}>{c.header}</th>)}</tr></thead>
                    <tbody>
                      {visits.map((v, i) => (
                        <tr key={v.id}>
                          {reportRow(v, i, includeTime).map((cell, c) => <td key={c} className={cols[c].align === 'center' ? 'text-center' : ''}>{cell}</td>)}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="mt-10 flex justify-between px-4 text-center">
                    <div className="w-56 border-t border-black pt-1">{sig.bdmName && <p>({sig.bdmName})</p>}<p className="font-bold">{settings.bdmSignatureLabel}</p><p>{sig.bdmDesignation}</p></div>
                    <div className="w-56 border-t border-black pt-1">{sig.zmName && <p>({sig.zmName})</p>}<p className="font-bold">{settings.zmSignatureLabel}</p><p>{sig.zmDesignation}</p></div>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </section>

      <section className="card space-y-2">
        <h2 className="font-semibold">4. Generate</h2>
        <button className="btn-primary w-full" disabled={busy || !visits?.length || !!custInvalid || (gaps.length > 0 && !ackGaps)} onClick={generate}>
          {busy ? 'Creating Excel file…' : `Generate & download Excel (${visits?.length ?? 0} visits)`}
        </button>
        {file && (
          <div className="grid grid-cols-2 gap-2">
            <button className="btn-secondary" onClick={() => downloadBlob(file.blob, file.name)}>Download again</button>
            <button className="btn-secondary" onClick={share}>Share…</button>
          </div>
        )}
        {file && <p className="break-all text-xs text-slate-500">Saved as {file.name} in your Downloads folder.</p>}
      </section>
    </div>
  );
}
