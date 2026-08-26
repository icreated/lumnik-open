import { html } from '../html.mjs';
import { cell } from '../format.mjs';

// B3: phone card list — ported verbatim from the spike's renderList (m/index.html ~L407-433).
// Always mounted alongside <Table/> (B3/B4 — same data, CSS picks which one shows via
// .only-phone/.only-wide); never re-fetches, just renders whatever fiches.mjs hands it.
export function Cards({ columns, rows, onOpen }) {
  return html`
    <div class="only-phone">
      ${rows.map(row => {
        const key = String(row[columns[0].name] ?? '');
        const title = String(row[columns[1]?.name] ?? key);
        return html`
          <div class="card" role="button" tabindex="0" onClick=${() => onOpen(key)}
               onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(key); } }}>
            <div class="top">
              <div class="title">${title}</div>
              <span class="key">${key}</span>
            </div>
            <div class="meta">
              ${columns.slice(2).map(c => html`
                <span class="chip">${(c.label || c.name) + ' '}<b>${cell(row[c.name])}</b></span>
              `)}
            </div>
          </div>
        `;
      })}
    </div>
  `;
}
