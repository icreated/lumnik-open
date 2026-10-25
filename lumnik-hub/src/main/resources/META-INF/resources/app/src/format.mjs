// Display formatting for the real PWA at /app/ — ported verbatim from the spike's since()
// (m/index.html, ~L474), with `now` injected so tests can pin it instead of racing Date.now().

import { DOOR_CONFLICTS } from './door-tokens.mjs';

export function since(iso, now = Date.now()) {
  const s = (now - Date.parse(iso)) / 1000;
  if (s < 3600) return `il y a ${Math.max(1, Math.round(s / 60))} min`;
  if (s < 86400) return `il y a ${Math.round(s / 3600)} h`;
  return `il y a ${Math.round(s / 86400)} j`;
}

// missing values render as an honest em dash, not a blank — 0/'' are present values and pass through
export function cell(v) {
  return v === null || v === undefined ? '—' : String(v);
}

// A ledger value of null is an absent value (a lookup row with nothing to say), distinct from the
// real empty string — never a blank that could pass for one.
export function ledgerValue(v) {
  return v === null || v === undefined ? '(empty)' : String(v);
}

// #874 — the fused records an answer filtered on while their sources disagree, in business French.
// Pure, so the wording is tested here and the component only lays it out.
export function disputedBlock(disputed, truncated) {
  if (!disputed || disputed.length === 0) return null;
  const items = disputed.map(d => {
    const name = `${d.key} · ${d.label || d.column}`;
    const head = d.counted
      ? `${name} — comptée avec « ${d.matched} »`
      : `${name} — écartée, alors qu'une source indique « ${d.matched} »`;
    const sources = (d.sources || []).map(s => `${s.src} : ${ledgerValue(s.value)}`).join(' · ');
    return { head, sources };
  });
  return { title: '⚡ Désaccord sur une valeur filtrée', items, more: truncated ? "… et d'autres fiches" : null };
}

// The doors an abstention names, as THIS surface's gesture — null when it has none, and a token
// this build does not know maps to null too, so it is passed over rather than rendered raw (#515).
//
// The wording is what the button DOES: it opens the fiches. The disagreements are shown on each
// fiche's own detail, one tap further, so "voir les désaccords" would promise a screen this
// button does not reach. `lifecycle` gets nothing at all — the PWA has no lifecycle surface, and
// the sentence in `answer` has already told the business user the declaration exists.
export function doorGesture(door) {
  if (door === DOOR_CONFLICTS) return 'Ouvrir les fiches — les désaccords sont sur chaque fiche';
  return null;
}

// P6 — the freshness dial, in business words. Absent input -> null: the surface stays
// silent rather than showing a fabricated age (the server already states absence by
// omitting dataAsOf when the scope has no completed run).
export function dataAsOfLabel(iso, now = Date.now()) {
  if (!iso) return null;
  return `données : ${since(iso, now)}`;
}

// #879: the body of an analytic ask. This shell is French only, so it names its answer language —
// a terse question ("SO-000004 statut ?") carries none, and the model would fall back to English.
// #1097: `previous` is the sentence a follow-up continues (thread.mjs previousOf), absent for a standalone question.
export function analyticAsk(question, scope, previous) {
  return { question, mode: 'analytic', scope, lang: 'fr', ...(previous ? { previous } : {}) };
}
