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

// The doors an abstention names, as THIS surface's gesture — null when it has none, and a token
// this build does not know maps to null too, so it is passed over rather than rendered raw (#515).
//
// The wording is what the button DOES: it opens the fiches. The disagreements are shown on each
// fiche's own detail, one tap further, so "voir les désaccords" would promise a screen this
// button does not reach. `lifecycle` gets nothing at all — the PWA has no lifecycle surface, and
// the sentence in `answer` has already told the métier the declaration exists.
export function doorGesture(door) {
  if (door === DOOR_CONFLICTS) return 'Ouvrir les fiches — les désaccords sont sur chaque fiche';
  return null;
}

// P6 — the freshness dial, in métier words. Absent input -> null: the surface stays
// silent rather than showing a fabricated age (the server already states absence by
// omitting dataAsOf when the scope has no completed run).
export function dataAsOfLabel(iso, now = Date.now()) {
  if (!iso) return null;
  return `données : ${since(iso, now)}`;
}
