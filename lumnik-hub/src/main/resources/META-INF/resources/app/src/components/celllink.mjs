import { html } from '../html.mjs';
import { linkOn, resolveLink } from '../links.mjs';

// One cell value, drawn as a link when its column declares one and this row can fill it — otherwise
// as the plain text it always was. Only the linked value becomes a control (a real <a> / <button>,
// so Tab, Enter and the focus ring come for free); it sits inside a row that opens the record, hence
// stopPropagation on click and key: following a link must not also open the sheet.
export function CellLink({ links, column, row, text, onFollow }) {
  const link = linkOn(links, column);
  const resolved = link ? resolveLink(link, row) : null;
  if (!resolved) return text;
  const stop = (e) => e.stopPropagation();
  if (resolved.kind === 'external') {
    return html`<a class="cell-link" href=${resolved.href} target="_blank" rel="noopener noreferrer"
                   onClick=${stop} onKeyDown=${stop}>${text}</a>`;
  }
  return html`<button class="cell-link" type="button"
                      onClick=${(e) => { e.stopPropagation(); onFollow(link, row); }}
                      onKeyDown=${stop}>${text}</button>`;
}
