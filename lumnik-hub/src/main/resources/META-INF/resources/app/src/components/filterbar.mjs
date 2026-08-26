import { html, useState } from '../html.mjs';
import { Refusal } from './refusal.mjs';

// B5: the filter bar speaks the hub grammar verbatim (col=val · col>num · col<iso-date) — no
// client-side validation, the hub is the one honest referee for what a filter means (see
// views.mjs's header comment). A bad filter never blanks the rows already on screen: fiches.mjs
// keeps the last good render and passes the server's refusal in here, verbatim, via `refusal`.
export function FilterBar({ filters, onAdd, onRemove, refusal }) {
  const [text, setText] = useState('');

  function submit() {
    const v = text.trim();
    if (!v) return;
    onAdd(v);
    setText('');
  }

  return html`
    <div class="filterbar">
      <div class="filterbar-row">
        <input
          value=${text}
          placeholder="col=val · col>num · col<date"
          onInput=${e => setText(e.target.value)}
          onKeyDown=${e => { if (e.key === 'Enter') submit(); }}
        />
        <button onClick=${submit}>Ajouter</button>
      </div>
      ${filters.length ? html`
        <div class="filterbar-chips">
          ${filters.map((f, i) => html`<span class="chip" onClick=${() => onRemove(i)}>${f} ✕</span>`)}
        </div>
      ` : null}
      ${refusal ? html`<${Refusal} kind=${refusal.kind} message=${refusal.message} />` : null}
    </div>
  `;
}
