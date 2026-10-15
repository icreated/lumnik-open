import { html } from '../html.mjs';
import { cell } from '../format.mjs';
import { CellLink } from './celllink.mjs';

// B3: phone card list — ported verbatim from the spike's renderList (m/index.html ~L407-433).
// Mounted only below the tablet breakpoint (layout.mjs picks; <Table/> is the other side);
// never re-fetches, just renders whatever fiches.mjs hands it.
export function Cards({ columns, rows, links, onFollow, onOpen }) {
  return html`
    <div>
      ${rows.map(row => {
        const key = String(row[columns[0].name] ?? '');
        const title = String(row[columns[1]?.name] ?? key);
        return html`
          <div class="card" role="button" tabindex="0" onClick=${() => onOpen(key)}
               onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(key); } }}>
            <div class="top">
              <div class="title"><${CellLink} links=${links} column=${columns[1]?.name} row=${row} text=${title} onFollow=${onFollow} /></div>
              <span class="key"><${CellLink} links=${links} column=${columns[0].name} row=${row} text=${key} onFollow=${onFollow} /></span>
            </div>
            <div class="meta">
              ${columns.slice(2).map(c => html`
                <span class="chip">${(c.label || c.name) + ' '}<b><${CellLink} links=${links} column=${c.name} row=${row} text=${cell(row[c.name])} onFollow=${onFollow} /></b></span>
              `)}
            </div>
          </div>
        `;
      })}
    </div>
  `;
}
