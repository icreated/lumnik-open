// The PWA's own identity, read from the runtime kind:App registry (#571) instead of the
// compiled METIERS map config.mjs carried before. Two phases, because the registry sits
// behind lm_user (GET /api/apps) and the PWA has no token before login:
//
//   Phase 1 (resolveCached) — synchronous, no network, at module load. Wears whatever this
//   browser cached from its last successful phase-2 fetch, or the bit-for-bit-today fallback
//   for clients/stock/absent, or a neutral identity for anything else. Never guesses Clients
//   for an unrecognised tag — that silent fallback is the bug #571 exists to remove.
//
//   Phase 2 (resolveFromRegistry, Task 2) — once the boot effect has a live token and
//   GET /api/apps has answered. Authoritative: resolves ?m= against the real registry, or
//   refuses.
//
// localStorage (not sessionStorage, which holds auth tokens) so a returning visitor's cached
// identity survives across sessions, not just across reloads within one.

const STORAGE_KEY = 'lumnik.app.registry';

// icon IDENTIFIER -> shipped filename. clients/stock keep their pre-existing names (nothing
// renamed); the four runtime identifiers follow the icon-<id>-*.png convention (design doc,
// Icon set section).
const ICON_FILES = {
  clients: 'icon-192.png',
  stock: 'icon-stock-192.png',
  generic: 'icon-generic-192.png',
  event: 'icon-event-192.png',
  alert: 'icon-alert-192.png',
  contract: 'icon-contract-192.png',
};

// The neutral identity: anything the cache has never seen, before phase 2 ever answers.
const NEUTRAL = {
  name: 'generic', title: 'lumnik', scope: null, icon: 'generic',
  accentColor: '#8b7ff0', backgroundColor: '#14121f', default: false,
};

// clients/stock are compiled into the shipped shell (their manifests and icons ship with
// every build), so a first-ever visit to either looks exactly as it does today even with an
// empty cache and no network yet.
const BUILTIN = {
  clients: { name: 'clients', title: 'Clients', scope: 'clients', icon: 'clients',
    accentColor: '#8b7ff0', backgroundColor: '#14121f', default: true },
  stock: { name: 'stock', title: 'Stock', scope: 'stock', icon: 'stock',
    accentColor: '#8b7ff0', backgroundColor: '#14121f', default: false },
};

function withDerived(entry) {
  const manifest = entry.name === 'clients' ? 'manifest-clients.json'
    : entry.name === 'stock' ? 'manifest-stock.json'
    : 'manifest-generic.json';
  return { ...entry, manifest, iconFile: ICON_FILES[entry.icon] || ICON_FILES.generic };
}

/**
 * Whatever GET /api/apps last returned successfully, or [] when nothing has cached yet, or
 * the value is unreadable (corrupt JSON, a non-array, storage denied in a private window) —
 * a cache miss degrades to the fallback in resolveCached, never a throw.
 */
export function readCachedRegistry(storage = localStorage) {
  try {
    const raw = storage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Best-effort — a full or denied localStorage must not break the boot it only caches for. */
export function cacheRegistry(entries, storage = localStorage) {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // quota exceeded or storage denied (private window) — the cache is a convenience, not a
    // requirement; phase 2 still resolves straight from the network response either way.
  }
}

/**
 * Phase 1 — synchronous, no network. The identity the login/offline/refused screens wear, and
 * what index.html paints into <title>/manifest/icons before App() ever mounts. A cached entry
 * for THIS request (the named tag, or the cached default when ?m= is absent) always wins; the
 * fallback below only fires on a genuine cache miss.
 */
export function resolveCached(search, storage = localStorage) {
  const param = new URLSearchParams(search).get('m');
  const cached = readCachedRegistry(storage);
  const remembered = param ? cached.find((e) => e.name === param) : cached.find((e) => e.default);
  if (remembered) return withDerived(remembered);

  const key = param === 'stock' ? 'stock' : (param === 'clients' || !param) ? 'clients' : null;
  return withDerived(key ? BUILTIN[key] : NEUTRAL);
}

/**
 * Phase 2 — after GET /api/apps answers. `entries` is the caller's OWN scope-filtered
 * registry (ScopeGrants already ran server-side): a name absent from it is indistinguishable
 * from a name the server never heard of at all, and both resolve here to 'refused' — the
 * server already refuses to tell the two apart, so the PWA does not try either.
 *
 * A refusal still carries `entry` — the same neutral identity phase 1 falls back to — so the
 * caller (app.mjs) can wear it on the document head without hand-rolling its own copy of
 * NEUTRAL/withDerived. One source, no risk of the two drifting apart.
 */
export function resolveFromRegistry(entries, search) {
  const param = new URLSearchParams(search).get('m');
  if (param) {
    const found = entries.find((e) => e.name === param);
    return found ? { screen: 'tabs', entry: withDerived(found) } : refused();
  }
  const fallback = entries.find((e) => e.default) || (entries.length === 1 ? entries[0] : null);
  return fallback ? { screen: 'tabs', entry: withDerived(fallback) } : refused();
}

function refused() {
  return { screen: 'refused', entry: withDerived(NEUTRAL) };
}

/**
 * Tab title, install manifest, and the two head icons — what both index.html (the phase-1
 * guess) and app.mjs (the phase-2 correction) rewrite before/after first paint.
 */
export function applyIdentity(entry, doc = document) {
  doc.title = entry.title;
  const manifestLink = doc.querySelector('link[rel="manifest"]');
  if (manifestLink) manifestLink.href = entry.manifest;
  const touchIcon = doc.querySelector('link[rel="apple-touch-icon"]');
  if (touchIcon) touchIcon.href = entry.iconFile;
  const favicon = doc.querySelector('link[rel="icon"]');
  if (favicon) favicon.href = entry.iconFile;
}

/**
 * The two declared theme tokens, applied where they actually reach: --accent (both a text
 * colour AND the .bubble.me chat-bubble BACKGROUND — see WcagContrast on the hub side, which
 * is why accentColor carries a contrast rule at all) and the mobile-chrome status-bar tint.
 * The in-app canvas (--bg/--card/--ink) stays fixed dark for every app — deliberately out of
 * scope (design doc, "Theme application").
 */
export function applyTheme(entry, doc = document) {
  doc.documentElement.style.setProperty('--accent', entry.accentColor);
  const themeColor = doc.querySelector('meta[name="theme-color"]');
  if (themeColor) themeColor.content = entry.backgroundColor;
}
