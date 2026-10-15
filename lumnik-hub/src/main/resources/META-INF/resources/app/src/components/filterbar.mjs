import { html, useState } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { kindOf, operatorsFor, inputTypeFor, buildFilter, chipLabel, resolveOp } from '../filters.mjs';

// B5: a typed filter bar — pick a column (métier label), an operator its kind offers, and a value
// control matching the kind (text, number, date/time, or a selection from allowedValues). The
// control only composes the hub grammar (filters.mjs); there is no client-side validation, the hub
// is the one honest referee for what a filter means (see views.mjs's header comment). A bad filter
// never blanks the rows already on screen: fiches.mjs keeps the last good render and passes the
// server's refusal in here, verbatim, via `refusal`. Several chips = AND, enforced by the hub.
export function FilterBar({ columns, filters, onAdd, onRemove, refusal }) {
  const [colName, setColName] = useState(null); // null = the first column
  const [op, setOp] = useState(null); // null = the kind's first operator
  const [value, setValue] = useState('');

  const column = columns.find(c => c.name === colName) || columns[0];
  if (!column) return null;
  const kind = kindOf(column);
  const ops = operatorsFor(column);
  const activeOp = resolveOp(column, op);
  const type = inputTypeFor(kind);

  function pickColumn(name) {
    setColName(name);
    setOp(null);
    setValue('');
  }

  function submit() {
    const raw = buildFilter(column, activeOp, value);
    if (!raw) return;
    onAdd(raw);
    setValue('');
  }

  return html`
    <div class="filterbar">
      <div class="filterbar-row">
        <select aria-label="Colonne" value=${column.name} onChange=${e => pickColumn(e.target.value)}>
          ${columns.map(c => html`<option value=${c.name}>${c.label || c.name}</option>`)}
        </select>
        <select aria-label="Opérateur" value=${activeOp} disabled=${ops.length === 1}
                onChange=${e => setOp(e.target.value)}>
          ${ops.map(o => html`<option value=${o.op}>${o.label}</option>`)}
        </select>
        ${type === 'select'
          ? html`
            <select aria-label="Valeur" value=${value} onChange=${e => setValue(e.target.value)}>
              <option value="">—</option>
              ${column.allowedValues.map(v => html`<option value=${v}>${v}</option>`)}
            </select>`
          : html`
            <input aria-label="Valeur" type=${type} value=${value}
                   onInput=${e => setValue(e.target.value)}
                   onKeyDown=${e => { if (e.key === 'Enter') submit(); }} />`}
        <button onClick=${submit}>Ajouter</button>
      </div>
      ${filters.length ? html`
        <div class="filterbar-chips">
          ${filters.map((f, i) => html`
            <span class="chip" role="button" tabindex="0" aria-label=${'Retirer le filtre ' + chipLabel(f, columns)}
                  onClick=${() => onRemove(i)}
                  onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRemove(i); } }}>
              ${chipLabel(f, columns)} ✕
            </span>`)}
        </div>
      ` : null}
      ${refusal ? html`<${Refusal} kind=${refusal.kind} message=${refusal.message} />` : null}
    </div>
  `;
}
