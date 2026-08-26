import { html } from '../html.mjs';
import { cell } from '../format.mjs';

// B4: tablet/display table (NEW rendering vs the spike — same data as <Cards/>, server column
// labels as a sticky header, every cell through cell()). Always mounted alongside <Cards/>;
// CSS (.only-wide) decides visibility per the responsive matrix (design doc section F).
export function Table({ columns, rows, onOpen }) {
  return html`
    <table class="data only-wide">
      <thead>
        <tr>${columns.map(c => html`<th>${c.label || c.name}</th>`)}</tr>
      </thead>
      <tbody>
        ${rows.map(row => {
          const key = String(row[columns[0].name] ?? '');
          return html`
            <tr role="button" tabindex="0" onClick=${() => onOpen(key)}
                onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(key); } }}>
              ${columns.map((c, i) => html`<td class=${i === 0 ? 'key' : ''}>${cell(row[c.name])}</td>`)}
            </tr>
          `;
        })}
      </tbody>
    </table>
  `;
}
