// The Search tab's logic, pure: whether the tab exists, the request, the sentences, the shape of a
// hit and of the record sheet it opens. The component (components/search.mjs) only draws what this
// returns. Nothing here names an edition, a module or a provider: the hub says what is there.

export const MAX_Q = 200;

/**
 * Whether the tab is worth showing, from the hub's own answers. The probe decides PRESENCE only:
 *   no index the caller may read → hidden        (nothing to search)
 *   unavailable / not installed  → hidden        (no provider)
 *   degraded, with an index      → degraded      (shown, the screen says why)
 *   otherwise                    → ok
 * A read that FAILS is not an answer: the caller keeps the tab (absence is proven, never assumed).
 */
export function availability(capability, indexes) {
  if (!Array.isArray(indexes) || indexes.length === 0) return 'hidden';
  const state = capability && capability.state;
  if (state === 'unavailable') return 'hidden';
  if (state === 'degraded') return 'degraded';
  return 'ok';
}

/**
 * The registry entry's probe: the hub's two answers, read through `api`, folded by availability().
 * Resolves false when the tab has no reason to exist; REJECTS when a read fails, so the caller keeps
 * the tab (a network blip proves nothing about absence).
 */
export async function probeSearch(api) {
  const [capability, indexes] = await Promise.all([
    api.json('/api/search/capability', { method: 'GET' }),
    api.json('/api/search-indexes', { method: 'GET' }),
  ]);
  return availability(capability, indexes) !== 'hidden';
}

/** The refusal to type a query the hub would refuse, or null. Checked before any call. */
export function checkQuery(q) {
  const t = String(q == null ? '' : q).trim();
  return t.length >= 1 && t.length <= MAX_Q ? null : `La recherche doit faire de 1 à ${MAX_Q} caractères.`;
}

/**
 * The query string of one search. The app's scope is always sent when there is one: the hub then
 * narrows to this métier, and this client never widens. Throws on a query checkQuery refuses.
 */
export function queryString({ q, scope, facets, cursor, limit }) {
  const refusal = checkQuery(q);
  if (refusal) throw new Error(refusal);
  const p = new URLSearchParams();
  p.set('q', String(q).trim());
  if (scope) p.set('scope', scope);
  if (limit) p.set('limit', String(limit));
  if (cursor) p.set('cursor', cursor);
  for (const [field, value] of Object.entries(facets || {})) p.set(`facet.${field}`, value);
  return p.toString();
}

/** The Search screen at rest: no query, no facet, no result, no error, no open record. */
export function clearedSearch() {
  return { q: '', facets: {}, state: { status: 'idle' }, detail: null };
}

/** Whether Clear has anything to do: false on the screen clearedSearch() describes. */
export function canClear({ q, facets, state, detail }) {
  return Boolean(q) || Object.keys(facets || {}).length > 0
    || (state && state.status !== 'idle') || Boolean(detail);
}

const WAIT = (s) => (s == null ? '' : ` (dans ${s} s)`);

/** What to tell the user about a failed call: { kind: 'scope' | 'error', message }. */
export function classify(err) {
  const status = err && err.status;
  if (err && err.kind === 'scope') {
    return { kind: 'scope', message: "Accès refusé : ce métier n'est pas autorisé pour cette recherche." };
  }
  if (status === 400) return { kind: 'error', message: `Recherche refusée : ${err.message}` };
  if (status === 429) {
    return { kind: 'error', message: `Trop de recherches à la suite : réessaie dans un instant${WAIT(err.retryAfter)}.` };
  }
  if (status === 501) return { kind: 'error', message: "La recherche n'est pas installée sur ce hub." };
  if (status === 503) {
    const reason = err.body && err.body.reason ? ` — ${err.body.reason}` : '';
    return { kind: 'error', message: `La recherche est momentanément indisponible${reason}. Réessaie${WAIT(err.retryAfter)}.` };
  }
  return { kind: 'error', message: (err && err.message) || 'erreur inconnue' };
}

/** A key as one stable line, whatever order the map arrives in (a composite key included). */
export function stableKey(key) {
  return Object.keys(key || {}).sort().map((k) => `${k}=${key[k]}`).join(' · ');
}

// The providers send MARKED-UP snippets: PostgreSQL <b>…</b> (ts_headline), OpenSearch <em>…</em>.
// A snippet with no tag is a field that did not match (ts_headline returns the start of the value).
const TAGGED = /<(b|em)>([\s\S]*?)<\/\1>/g;
const escapeRegExp = (t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * A value cut into runs, the words a provider tagged marked wherever they occur in it
 * (case-insensitively). Pure text in, pure text out: the component puts each run in a text node,
 * so no markup from the data is ever interpreted. The marks are found in the value itself, so a
 * letter whose lower case is longer (İ) cannot shift them.
 */
export function segments(value, fragments) {
  const text = String(value == null ? '' : value);
  const terms = new Set();
  for (const fragment of fragments || []) {
    for (const m of String(fragment).matchAll(TAGGED)) if (m[2]) terms.add(m[2]);
  }
  if (!terms.size || !text) return [{ text, hit: false }];
  const marks = new Array(text.length).fill(false);
  for (const term of terms) {
    for (const m of text.matchAll(new RegExp(escapeRegExp(term), 'giu'))) {
      for (let i = m.index; i < m.index + m[0].length; i++) marks[i] = true;
    }
  }
  const out = [];
  for (let i = 0; i < text.length; i++) {
    const last = out[out.length - 1];
    if (last && last.hit === marks[i]) last.text += text[i];
    else out.push({ text: text[i], hit: marks[i] });
  }
  return out;
}

/**
 * One hit, ready to draw. `link` is the hub's canonicalLink, followed as given. The heading is the
 * business title the hub resolved from the index's declared titleField (and that field then leaves the
 * chips, so it is not said twice); with none declared it is the entity name, as it always was.
 */
export function hitView(hit, labels) {
  const highlights = hit.highlights || {};
  const titled = Boolean(hit.title);
  const titleField = titled ? hit.titleField : null;
  const heading = titled ? hit.title : hit.entity;
  return {
    entity: hit.entity,
    key: stableKey(hit.key),
    link: hit.canonicalLink,
    titleField,
    // #1051: the hub's external-link templates, shaped for links.mjs (a link sits on a `column`), and the
    // hit's returned fields as the row they fill from — every placeholder is one of them
    links: (hit.links || []).map((l) => ({ column: l.field, url: l.url })),
    row: hit.fields || {},
    heading,
    // under a business title: which entity and which key; under the entity name itself: the key alone
    sub: titled ? `${hit.entity} · ${stableKey(hit.key)}` : stableKey(hit.key),
    headingSegments: titleField ? segments(heading, highlights[titleField]) : [{ text: heading, hit: false }],
    fields: Object.entries(hit.fields || {}).filter(([name]) => name !== titleField).map(([name, value]) => ({
      name, label: labelOf(labels, hit.entity, name), value, segments: segments(value, highlights[name]),
    })),
  };
}

// ── the entity's column labels (#1055) ───────────────────────────────────────────────────────────
// The hub sends `fieldLabels` {entity: {column: label}} for the entities of the page, only for columns the
// entity labels. Everything else keeps its raw name: a label is a display name, never a key.

/** The label the entity gives `field`, else the raw name. */
export function labelOf(labels, entity, field) {
  const label = labels && labels[entity] && labels[entity][field];
  return typeof label === 'string' && label.trim() !== '' ? label : field;
}

/**
 * A facet is not per entity: it is labelled only when every entity of the page that labels that column gives it
 * the same label. Two that disagree, or none, show the raw name.
 */
export function facetLabel(labels, entities, field) {
  const given = new Set();
  for (const entity of entities || []) {
    const l = labelOf(labels, entity, field);
    if (l !== field) given.add(l);
  }
  return given.size === 1 ? [...given][0] : field;
}

/** Labels of a later page added to what earlier pages gave; an entity already known keeps its labels. */
export function mergeLabels(before, after) {
  const merged = { ...(before || {}) };
  for (const [entity, labels] of Object.entries(after || {})) if (!(entity in merged)) merged[entity] = labels;
  return merged;
}

/** The facets the hub returned, as chips; `active` is {field: value} for the filters already applied. */
export function facetChips(facets, active) {
  return Object.entries(facets || {}).map(([field, values]) => ({
    field,
    values: values.map((v) => ({ value: v.value, count: v.count, active: (active || {})[field] === v.value })),
  }));
}

/** How fresh the index is, in a line; empty when the hub did not say. */
export function asOfLine(asOf) {
  if (!asOf) return '';
  const d = new Date(asOf);
  return Number.isNaN(d.getTime()) ? '' : `Index à jour au ${d.toLocaleString('fr-FR')}`;
}

// ── the canonical read → the record sheet ────────────────────────────────────────────────────────
// GET <canonicalLink> answers an ARRAY of canonical rows plus a `_conflicts` ledger per row:
// {"<column>": [{"src", "value"}, ...]}, "{}" when the sources agree. It may arrive as text or as an
// object. The sheet is the same {status, payload} the Fiches detail uses, so Detail draws it as is.

function readLedger(raw, entity, labels) {
  let ledger = raw;
  if (typeof raw === 'string') {
    try { ledger = JSON.parse(raw); } catch { return null; }
  }
  if (ledger && typeof ledger === 'object' && typeof ledger.value === 'string' && !Array.isArray(ledger)) {
    try { ledger = JSON.parse(ledger.value); } catch { return null; } // a driver wrapper around the text
  }
  if (!ledger || typeof ledger !== 'object' || Array.isArray(ledger)) return null;
  const conflicts = [];
  for (const [column, sources] of Object.entries(ledger)) {
    if (!Array.isArray(sources)) return null;
    conflicts.push({ column, label: labelOf(labels, entity, column), sources: sources.map((s) => ({ src: s && s.src, value: s && s.value })) });
  }
  return conflicts;
}

export function recordToDetail(entity, key, rows, labels) {
  if (!Array.isArray(rows)) return { status: 'error', kind: 'error', message: 'Réponse illisible pour cette fiche.' };
  if (rows.length === 0) return { status: 'error', kind: 'error', message: 'Fiche introuvable : elle a changé depuis la recherche.' };
  if (rows.length > 1) {
    return { status: 'error', kind: 'error',
      message: `Anomalie : plusieurs fiches (${rows.length}) portent cette clé. Aucune n'est affichée.` };
  }
  const row = rows[0];
  const conflicts = readLedger(row._conflicts, entity, labels);
  return {
    status: 'ready',
    payload: {
      title: entity,
      key: stableKey(key),
      fields: Object.entries(row).filter(([name]) => name !== '_conflicts')
        .map(([name, value]) => ({ name, label: labelOf(labels, entity, name), value })),
      conflicts: conflicts || [],
      conflictLedger: conflicts !== null,
    },
  };
}
