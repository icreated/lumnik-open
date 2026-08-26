import { html, Fragment, useEffect } from '../html.mjs';
import { cell } from '../format.mjs';
import { Refusal } from './refusal.mjs';

// B6: detail rendering ported from the spike's openDetail (m/index.html ~L435-470) — fields,
// the amber conflicts ledger, the "sources agree" line. `data` is a small state machine fiches.mjs
// drives: {status:'loading'} while the detail call is in flight, {status:'error',kind,message} on
// failure, {status:'ready', payload} with the server payload.
//
// Phone/tablet gets the bottom sheet (#veil/#sheet; body.sheet-open drives the CSS transition —
// mirrors the spike's classList dance via an effect keyed on mount/unmount). ≥900px gets the
// pinned .panel instead (design doc matrix F). `wide` is decided once in fiches.mjs via
// matchMedia so only one of the two ever mounts — a sheet AND a panel rendering the same content
// twice would be a silent duplicate for focus/screen readers, not just a visual redundancy.
export function Detail({ data, onClose, wide }) {
  useEffect(() => {
    if (wide) return; // the panel has no veil/transition dance to drive
    document.body.classList.add('sheet-open');
    return () => document.body.classList.remove('sheet-open');
  }, [wide]);

  const body = renderBody(data);

  if (wide) {
    return html`
      <div class="panel">
        <button class="panel-close" onClick=${onClose} aria-label="Fermer">✕</button>
        ${body}
      </div>
    `;
  }
  return html`
    <${Fragment}>
      <div id="veil" onClick=${onClose}></div>
      <div id="sheet"><div class="grab"></div><div id="sheet-body">${body}</div></div>
    <//>
  `;
}

function renderBody(data) {
  if (data.status === 'loading') return html`<p>…</p>`;
  if (data.status === 'error') return html`<${Refusal} kind=${data.kind} message=${data.message} />`;
  const d = data.payload;
  return html`
    <${Fragment}>
      <h2>${d.title || ''}</h2>
      <div class="subkey">${d.key}</div>
      ${(d.fields || []).map(f => html`
        <div class="field">
          <div class="lbl">${f.label || f.name}</div>
          <div class="val">${cell(f.value)}</div>
        </div>
      `)}
      ${d.conflicts && d.conflicts.length
        ? html`
          <div class="conflicts">
            <h3>⚡ Les sources ne sont pas d’accord</h3>
            ${d.conflicts.map(cf => html`
              <div class="conflict">
                <div class="col">${cf.label || cf.column}</div>
                ${cf.sources.map(s => html`<div class="src">${s.src} : <b>${s.value}</b></div>`)}
              </div>
            `)}
          </div>
        `
        : d.conflictLedger
          ? html`<div class="agree">✓ Toutes les sources sont d’accord sur cette fiche.</div>`
          : null}
    <//>
  `;
}
