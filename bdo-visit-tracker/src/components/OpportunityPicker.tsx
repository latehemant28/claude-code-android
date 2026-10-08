import { useState } from 'react';

/** Multi-select chips: tap common/learned opportunities, or type a new one. */
export function OpportunityPicker({ value, onChange, suggestions, invalid }: {
  value: string[]; onChange: (v: string[]) => void; suggestions: string[]; invalid?: boolean;
}) {
  const [text, setText] = useState('');
  const has = (s: string) => value.some((v) => v.toLowerCase() === s.toLowerCase());
  const add = (s: string) => {
    const t = s.trim().replace(/\s+/g, ' ');
    if (t && !has(t)) onChange([...value, t]);
    setText('');
  };
  const remaining = suggestions.filter((s) => !has(s));
  const typed = text.trim().toLowerCase();
  const shown = typed ? remaining.filter((s) => s.toLowerCase().includes(typed)) : remaining;

  return (
    <div className={`rounded-lg border p-3 ${invalid ? 'border-red-500' : 'border-slate-300'} bg-white`}>
      {value.length > 0 && (
        <ul className="mb-3 flex flex-wrap gap-2" aria-label="Selected opportunities">
          {value.map((v) => (
            <li key={v} className="chip border-brand-600 bg-brand-800 text-white">
              {v}
              <button type="button" className="-mr-1 ml-1 px-1 text-lg leading-none" aria-label={`Remove ${v}`} onClick={() => onChange(value.filter((x) => x !== v))}>
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex gap-2">
        <input
          id="opportunities"
          className="input"
          placeholder="Type an opportunity or tap one below"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add(text);
            }
          }}
        />
        <button type="button" className="btn-secondary shrink-0" onClick={() => add(text)} disabled={!text.trim()}>
          Add
        </button>
      </div>
      {shown.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {shown.slice(0, 16).map((s) => (
            <button type="button" key={s} className="chip border-slate-300 bg-slate-50 text-slate-800 hover:bg-brand-50" onClick={() => add(s)}>
              + {s}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
