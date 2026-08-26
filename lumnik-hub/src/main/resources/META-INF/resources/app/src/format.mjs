// Display formatting for the real PWA at /app/ — ported verbatim from the spike's since()
// (m/index.html, ~L474), with `now` injected so tests can pin it instead of racing Date.now().

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

// P6 — the freshness dial, in métier words. Absent input -> null: the surface stays
// silent rather than showing a fabricated age (the server already states absence by
// omitting dataAsOf when the scope has no completed run).
export function dataAsOfLabel(iso, now = Date.now()) {
  if (!iso) return null;
  return `données : ${since(iso, now)}`;
}
