// Page navigation for a paged View read. Pure logic, no DOM: fiches.mjs renders what getState()
// says. The hub owns the cursors (opaque, bound to the query); this module only remembers the
// ones it was handed, so "Previous" replays a cursor the hub already issued and never invents one.
//
// What it guarantees:
//  - bounded state: one page of rows plus at most MAX_TRAIL cursors, however far the user pages;
//  - a failure keeps the last good page, its position and the way forward — nothing is blanked;
//  - a response that is no longer the newest request (a filter changed meanwhile) is dropped
//    whole, success or failure, so an old page can never overwrite a newer one.

export const PAGE_SIZE = 50;     // the hub default; inside its 1..200 contract
export const MAX_TRAIL = 100;    // cursors kept for "Previous"; older ones fall off, page 1 stays reachable

export function createPager(load) {
  let result = null;      // rows of the last GOOD page
  let pageNo = 1;
  let cursor = null;      // cursor that produced `result` (null = first page)
  let next = null;        // cursor the hub issued for the following page
  let hasMore = false;
  let trail = [];         // cursors of the pages before this one, most recent last
  let error = null;       // {kind, message} of the last failed move, null after a success
  let errorOrigin = null; // 'reset' | 'move' — which gesture the error belongs to
  let loading = false;
  let seq = 0;            // id of the newest request
  const listeners = new Set();

  function getState() {
    return {
      result, pageNo, hasMore, error, errorOrigin, loading,
      canNext: next !== null,
      canPrev: trail.length > 0,
      canFirst: pageNo > 1,
      trailLength: trail.length,
    };
  }
  const emit = () => { const s = getState(); listeners.forEach(fn => fn(s)); };

  // Runs one request. `commit(r)` moves the position only when the page really arrived.
  async function run(target, commit, origin) {
    const mine = ++seq;
    loading = true;
    emit();
    try {
      const r = await load(target);
      if (mine !== seq) return;
      commit();
      result = r;
      cursor = target;
      next = r.nextCursor ?? null;
      hasMore = !!r.hasMore;
      error = null;
      errorOrigin = null;
    } catch (e) {
      if (mine !== seq) return;
      error = { kind: e?.kind || 'error', message: e?.message || String(e) };
      errorOrigin = origin;
    }
    loading = false;
    emit();
  }

  function reset() {
    return run(null, () => { pageNo = 1; trail = []; }, 'reset');
  }

  return {
    getState,
    reset,
    first: reset,
    next() {
      if (loading || next === null) return;
      const from = cursor;
      return run(next, () => {
        pageNo++;
        trail.push(from);
        if (trail.length > MAX_TRAIL) trail.shift();
      }, 'move');
    },
    prev() {
      if (loading || trail.length === 0) return;
      return run(trail[trail.length - 1], () => { pageNo--; trail.pop(); }, 'move');
    },
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
  };
}

// The copy under a page. A capped page is never presented as the whole answer.
export function truncationLabel({ pageNo, hasMore }) {
  if (hasMore) {
    return pageNo > 1
      ? `Page ${pageNo} — d'autres résultats existent`
      : `Première page — d'autres résultats existent`;
  }
  return pageNo > 1 ? `Page ${pageNo} — dernière page` : null;
}
