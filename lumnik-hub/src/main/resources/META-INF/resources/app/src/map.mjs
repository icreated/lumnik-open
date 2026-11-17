// A view on a map (#2057). Pure logic, no DOM: components/map.mjs draws what these functions decide.
//
// The hub decides which rows are placed: a row's `_map` is {lat, lon} or null. Nothing here re-checks a
// coordinate — two clients must show the same rows on the map, so the one verdict is the hub's.

// The switch is offered when the view asks for a map and its point is usable; a map whose point is
// not usable any more says why, and the list stays.
export function mapState(result) {
  const map = result && result.map;
  if (!map) return { offered: false, error: null };
  if (map.error) return { offered: false, error: map.error };
  return { offered: true, error: null };
}

// Rows split on the hub's verdict alone. `placed` keeps the row beside its point, so a tap opens it.
export function splitRows(rows) {
  const placed = [];
  const unplaced = [];
  for (const row of rows || []) {
    const spot = row && row._map;
    if (spot) placed.push({ row, lat: spot.lat, lon: spot.lon });
    else unplaced.push(row);
  }
  return { placed, unplaced };
}

// [[south, west], [north, east]] over the placed points, or null with none to frame.
export function bounds(placed) {
  if (!placed.length) return null;
  let s = 90, w = 180, n = -90, e = -180;
  for (const p of placed) {
    s = Math.min(s, p.lat); n = Math.max(n, p.lat);
    w = Math.min(w, p.lon); e = Math.max(e, p.lon);
  }
  return [[s, w], [n, e]];
}

// "2 fiches sans position" — never a row dropped in silence. Empty when every row is placed.
export function unplacedLabel(count) {
  if (!count) return '';
  return count === 1 ? '1 fiche sans position' : `${count} fiches sans position`;
}

// The unpaged read stops at the hub's cap: the map says so rather than pretend to be complete.
export function capLabel(result) {
  if (!result || !result.truncated) return '';
  return `${result.rows.length} premières fiches — il y en a d'autres, affinez avec un filtre`;
}

// The parallels and meridians of the neutral background, every `step` degrees, as polylines.
export function graticule(step = 10) {
  const lines = [];
  for (let lat = -80; lat <= 80; lat += step) lines.push([[lat, -180], [lat, 180]]);
  for (let lon = -180; lon <= 180; lon += step) lines.push([[-85, lon], [85, lon]]);
  return lines;
}

// Leaflet writes an attribution as HTML; the operator's line is text, so it is escaped first.
export function escapeText(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// The map's read, apart from the screen (#2057). The newest read wins; a read that lands once the map is
// hidden (back to the list, the tab left) is kept for the next opening but never writes the header count:
// that count belongs to whatever is on screen. reset() drops everything — a view change, an unmount.
//   read(filters) → Promise<result>   onState({status, filters, result?|kind?, message?})   onCount(text)
export function createMapReader({ read, onState, onCount }) {
  let seq = 0;
  let shown = false;
  let state = null;

  const countOf = (result) => `${result.rows.length}${result.truncated ? '+' : ''}`;
  const settle = (next) => { state = next; onState(next); };

  async function load(filters) {
    const mine = ++seq;
    settle({ status: 'loading', filters });
    try {
      const result = await read(filters);
      if (mine !== seq) return;
      settle({ status: 'ready', filters, result });
      if (shown) onCount(countOf(result));
    } catch (e) {
      if (mine !== seq) return;
      settle({ status: 'error', filters, kind: (e && e.kind) || 'error', message: (e && e.message) || String(e) });
    }
  }

  // Shows the map under these filters: the read is reused while they stand (Liste → Carte → Liste → Carte reads once).
  function show(filters) {
    shown = true;
    if (!state || state.status === 'error' || state.filters !== filters) return load(filters);
    if (state.status === 'ready') onCount(countOf(state.result));
    return undefined;
  }

  // The filters changed: a map on screen reads again; a hidden one reads at its next show().
  function refilter(filters) {
    return shown ? load(filters) : undefined;
  }

  function hide() { shown = false; }
  function reset() { seq += 1; shown = false; state = null; }

  return { show, hide, refilter, reset };
}
