import { useId, useMemo, useState } from 'react';

export interface Option {
  label: string;
  sub?: string;
}

/**
 * Text input with a searchable suggestion list. Typing a value not in the list is allowed:
 * the list offers "Add as new" and the typed text is kept.
 */
export function Combobox(props: {
  id: string;
  value: string;
  onChange: (v: string) => void;
  onPick?: (o: Option) => void;
  options: Option[];
  placeholder?: string;
  invalid?: boolean;
  newLabel?: string;
}) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const listId = useId();
  const q = props.value.trim().toLowerCase();
  const filtered = useMemo(() => {
    const terms = q.split(/\s+/).filter(Boolean);
    return props.options.filter((o) => terms.every((t) => `${o.label} ${o.sub ?? ''}`.toLowerCase().includes(t))).slice(0, 8);
  }, [props.options, q]);
  const exact = props.options.some((o) => o.label.toLowerCase() === q);

  const pick = (o: Option) => {
    props.onChange(o.label);
    props.onPick?.(o);
    setOpen(false);
    setActive(-1);
  };

  return (
    <div className="relative">
      <input
        id={props.id}
        className={`input ${props.invalid ? 'input-error' : ''}`}
        value={props.value}
        placeholder={props.placeholder}
        autoComplete="off"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-invalid={props.invalid || undefined}
        onChange={(e) => {
          props.onChange(e.target.value);
          setOpen(true);
          setActive(-1);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (!open) return;
          if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, filtered.length - 1)); }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, -1)); }
          else if (e.key === 'Enter' && active >= 0) { e.preventDefault(); pick(filtered[active]); }
          else if (e.key === 'Escape') setOpen(false);
        }}
      />
      {open && (filtered.length > 0 || (q && !exact)) && (
        <ul id={listId} role="listbox" className="absolute z-30 mt-1 max-h-72 w-full overflow-y-auto rounded-lg border border-slate-200 bg-white py-1 shadow-lg">
          {filtered.map((o, i) => (
            <li
              key={o.label + (o.sub ?? '')}
              role="option"
              aria-selected={i === active}
              className={`cursor-pointer px-3 py-2.5 ${i === active ? 'bg-brand-50' : 'hover:bg-slate-50'}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => pick(o)}
            >
              <div className="font-medium">{o.label}</div>
              {o.sub && <div className="text-xs text-slate-500">{o.sub}</div>}
            </li>
          ))}
          {q && !exact && (
            <li
              role="option"
              aria-selected={false}
              className="cursor-pointer border-t border-slate-100 px-3 py-2.5 text-brand-800 hover:bg-slate-50"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => setOpen(false)}
            >
              + {props.newLabel ?? 'Add'} “{props.value.trim()}”
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
