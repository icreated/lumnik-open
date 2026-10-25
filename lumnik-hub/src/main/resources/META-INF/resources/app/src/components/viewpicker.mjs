import { html } from '../html.mjs';

// B1: reachable at boot when the app has >1 views, and again later via the ⇄ switch chip
// in fiches.mjs — same list either way. Reuses the existing card look (.card/.top/.title/.key)
// rather than inventing new CSS: title big (the view's title), name mono small (the view's
// technical name, same treatment as a fiche's key).
export function ViewPicker({ views, onSelect }) {
  return html`
    <div class="viewpicker">
      ${views.map(v => html`
        <div class="card" role="button" tabindex="0" onClick=${() => onSelect(v)}
             onKeyDown=${e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(v); } }}>
          <div class="top">
            <div class="title">${v.title || v.name}</div>
          </div>
          <span class="key">${v.name}</span>
        </div>
      `)}
    </div>
  `;
}
