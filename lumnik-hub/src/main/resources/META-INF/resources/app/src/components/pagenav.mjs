import { html } from '../html.mjs';
import { Refusal } from './refusal.mjs';

// Page controls around a paged View: Fiches draws this same component above and below the results
// (`where`: 'haut' | 'bas', only to name each group for assistive technology), both fed by the one
// pager. Pure view of a pager state (pager.mjs): while a page loads the buttons rest and the rows on
// screen stay; a failed move shows the hub's refusal verbatim here, scoped to the move — the page the
// user is reading is never blanked.
export function PageNav({ state, where, onFirst, onPrev, onNext, refusal }) {
  const { canFirst, canPrev, canNext, loading } = state;
  if (!canFirst && !canPrev && !canNext && !refusal) return null;
  return html`
    <nav class="pagenav" aria-label=${`Pagination (${where})`} aria-busy=${loading ? 'true' : 'false'}>
      <div class="pagenav-row">
        ${loading ? html`<span class="pagenav-busy" role="status" aria-label="Chargement">…</span>` : null}
        <button type="button" aria-label="Première page" disabled=${loading || !canFirst} onClick=${onFirst}>« Début</button>
        <button type="button" aria-label="Page précédente" disabled=${loading || !canPrev} onClick=${onPrev}>‹ Précédent</button>
        <button type="button" aria-label="Page suivante" disabled=${loading || !canNext} onClick=${onNext}>Suivant ›</button>
      </div>
      ${refusal ? html`<${Refusal} kind=${refusal.kind} message=${refusal.message} />` : null}
    </nav>
  `;
}
