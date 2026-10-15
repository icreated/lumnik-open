// Links declared on a View's cells (`spec.links`, #1050), pure. The hub parsed and validated them and
// hands them over with the rendered page; this module only turns one declared link plus one clicked
// ROW into the thing to follow:
//   - to another View: the filters to install, in the hub's own grammar (`col=value`) — the hub
//     stays the referee of whether they are legal for that View;
//   - to an external page: an https address whose placeholders are percent-encoded values.
// A value that is missing or blank gives NO link: a half-built filter or address is never produced.

const PLACEHOLDER = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g;

/** The link declared on `column`, or null. */
export function linkOn(links, column) {
  return (links || []).find((l) => l.column === column) || null;
}

function valueOf(row, name) {
  const v = row && row[name];
  if (v === null || v === undefined) return null;
  const s = String(v).trim(); // legacy CHAR columns come padded; the hub trims a filter value too
  return s === '' ? null : s;
}

// encodeURIComponent leaves '.' alone, and a browser resolves a '.' / '..' segment — even '%2e%2e' — so
// such a value would climb out of its path: it makes the link not exist rather than point elsewhere.
function encodeSegment(v) {
  return v === '.' || v === '..' ? null : encodeURIComponent(v);
}

/** The template with each {column} replaced by `encode(row value)`; null when any value is missing. */
function fill(template, row, encode) {
  let missing = false;
  const out = template.replace(PLACEHOLDER, (_, name) => {
    const v = valueOf(row, name);
    const out = v === null ? null : encode(v);
    if (out === null) { missing = true; return ''; }
    return out;
  });
  return missing ? null : out;
}

/**
 * What following `link` from `row` means, or null when it cannot be followed:
 *   { kind: 'view', targetView, filters: ['col=value', …] } | { kind: 'external', href }
 */
export function resolveLink(link, row) {
  if (link.url) {
    const href = fill(link.url, row, encodeSegment);
    // The hub only accepts https templates; checked again here because this is where a click turns into a navigation.
    return href !== null && href.startsWith('https://') ? { kind: 'external', href } : null;
  }
  if (link.targetView) {
    const filters = [];
    for (const [column, template] of Object.entries(link.filters || {})) {
      const value = fill(template, row, (v) => v);
      if (value === null) return null;
      filters.push(`${column}=${value}`);
    }
    return filters.length ? { kind: 'view', targetView: link.targetView, filters } : null;
  }
  return null;
}

/**
 * Where a resolved view link goes: the target is looked up ONLY among the views this app was given
 * (the hub lists the open métier's views and nothing else). Not found → a refusal, never an open
 * without its filters.
 */
export function followView(resolved, viewList) {
  const entry = (viewList || []).find((v) => v.name === resolved.targetView);
  if (!entry) {
    return { ok: false, message: `Lien impossible : la vue « ${resolved.targetView} » n'est pas disponible dans ce métier.` };
  }
  return { ok: true, entry, filters: resolved.filters };
}

/**
 * What to do with a followed link's candidate pager once it reports: keep waiting while it loads; drop
 * it when a newer gesture (or leaving the tab) outdated it, whatever it brought; take it over when its
 * first page arrived; refuse otherwise. The page on screen is only ever replaced by 'commit'.
 */
export function settleProbe(state, stale) {
  if (state.loading) return 'wait';
  if (stale) return 'drop';
  return state.result ? 'commit' : 'refuse';
}
