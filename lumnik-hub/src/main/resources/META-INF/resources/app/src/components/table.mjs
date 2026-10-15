import { html } from '../html.mjs';
import { cell } from '../format.mjs';
import { CellLink } from './celllink.mjs';

// B4: tablet/display table (NEW rendering vs the spike — same data as <Cards/>, server column
// labels as a sticky header, every cell through cell()). Mounted only from the
// tablet breakpoint up (layout.mjs picks; <Cards/> is the other side).
export function Table({ columns, rows, links, onFollow, onOpen }) {
  return html`
    <table class="data">
      <thead>
        <tr>${columns.map(c => html`<th>${c.label || c.name}</th>`)}</tr>
      </thead>
      <tbody>
        ${rows.map(row => {
          const key = String(row[columns[0].name] ?? '');
          return html`
            <tr role="button" tabindex="0" onClick=${() => onOpen(key)}
                onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(key); } }}>
              ${columns.map((c, i) => html`<td class=${i === 0 ? 'key' : ''}><${CellLink} links=${links} column=${c.name} row=${row} text=${cell(row[c.name])} onFollow=${onFollow} /></td>`)}
            </tr>
          `;
        })}
      </tbody>
    </table>
  `;
}
