import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {
  availability, checkQuery, queryString, classify, stableKey, segments, hitView, facetChips, asOfLine,
  recordToDetail, probeSearch, clearedSearch, canClear, labelOf, facetLabel, mergeLabels,
} from '../../main/resources/META-INF/resources/app/src/search.mjs';

// ── availability: the probe decides PRESENCE only ────────────────────────────────────────────────
test('availability: no index at all hides the tab, whatever the capability says', () => {
  assert.equal(availability({ state: 'available' }, []), 'hidden');
  assert.equal(availability({ state: 'degraded', reason: 'backend_down' }, []), 'hidden');
});

test('availability: not installed hides the tab', () => {
  assert.equal(availability({ state: 'unavailable', reason: 'not_installed' }, [{ name: 'a' }]), 'hidden');
});

test('availability: degraded with an index stays visible and degraded', () => {
  assert.equal(availability({ state: 'degraded', reason: 'backend_down' }, [{ name: 'a' }]), 'degraded');
});

test('availability: available with an index is ok; an unknown state is not treated as absent', () => {
  assert.equal(availability({ state: 'available' }, [{ name: 'a' }]), 'ok');
  assert.equal(availability({ state: 'something_new' }, [{ name: 'a' }]), 'ok');
});

// ── the query ────────────────────────────────────────────────────────────────────────────────────
test('checkQuery: blank and over-long queries are refused before any call', () => {
  assert.match(checkQuery('   '), /1 à 200/);
  assert.match(checkQuery('x'.repeat(201)), /1 à 200/);
  assert.equal(checkQuery('x'.repeat(200)), null);
  assert.equal(checkQuery(' lyon '), null);
});

test('queryString: trims q, always carries the scope, encodes everything', () => {
  const qs = queryString({ q: ' hydrau lique & co ', scope: 'stock' });
  const p = new URLSearchParams(qs);
  assert.equal(p.get('q'), 'hydrau lique & co');
  assert.equal(p.get('scope'), 'stock');
  assert.ok(!qs.includes(' '), 'spaces are encoded');
});

test('queryString: facets become facet.<name>, a cursor and a limit are passed through', () => {
  const p = new URLSearchParams(queryString({ q: 'x', scope: 's', facets: { segment: 'GOLD', city: 'Lyon' }, cursor: 'abc==', limit: 10 }));
  assert.equal(p.get('facet.segment'), 'GOLD');
  assert.equal(p.get('facet.city'), 'Lyon');
  assert.equal(p.get('cursor'), 'abc==');
  assert.equal(p.get('limit'), '10');
});

test('queryString: no scope, no scope parameter (a caller outside an app); an invalid q throws', () => {
  assert.ok(!new URLSearchParams(queryString({ q: 'x' })).has('scope'));
  assert.throws(() => queryString({ q: '' }));
});

// ── refusals ─────────────────────────────────────────────────────────────────────────────────────
test('classify: a scope refusal wears the refusal tone in French', () => {
  const r = classify({ kind: 'scope', status: 403, message: 'scope not granted' });
  assert.equal(r.kind, 'scope');
  assert.match(r.message, /pas autorisé|refusé/i);
});

test('classify: 400 says what the hub said', () => {
  const r = classify({ kind: 'error', status: 400, message: 'q must be 1-200 characters' });
  assert.equal(r.kind, 'error');
  assert.match(r.message, /q must be 1-200 characters/);
});

test('classify: 429 asks to wait, with the delay only when the hub sent one', () => {
  assert.match(classify({ kind: 'error', status: 429, retryAfter: 12, message: 'x' }).message, /12 s/);
  assert.doesNotMatch(classify({ kind: 'error', status: 429, retryAfter: null, message: 'x' }).message, /\d+ s/);
});

test('classify: 503 gives the reason and the delay the hub sent, and never invents one', () => {
  const r = classify({ kind: 'error', status: 503, retryAfter: 60, message: 'search degraded', body: { reason: 'backend_down' } });
  assert.match(r.message, /backend_down/);
  assert.match(r.message, /60 s/);
  const none = classify({ kind: 'error', status: 503, retryAfter: null, message: 'search degraded', body: { reason: 'backend_down' } });
  assert.doesNotMatch(none.message, /\d+ s/);
});

test('classify: 501 says it is not installed; a network failure keeps its own French message', () => {
  assert.match(classify({ kind: 'error', status: 501, message: 'search not installed' }).message, /pas installée/);
  assert.equal(classify({ kind: 'error', message: 'réseau indisponible — vérifie ta connexion' }).message,
    'réseau indisponible — vérifie ta connexion');
});

// ── hits ─────────────────────────────────────────────────────────────────────────────────────────
test('stableKey: a composite key reads the same whatever the map order', () => {
  assert.equal(stableKey({ b: '2', a: '1' }), 'a=1 · b=2');
  assert.equal(stableKey({ code: 'P-1000' }), 'code=P-1000');
});

// The providers send MARKED-UP snippets: PostgreSQL <b>…</b> (ts_headline), OpenSearch <em>…</em>. A
// snippet with no tag is a field that did not match (ts_headline returns the start of the value).
test('segments: the words a provider tagged are marked in the value, whichever tag it uses', () => {
  const want = [{ text: 'Hydraulique ', hit: false }, { text: 'Lyon', hit: true }, { text: ' 1000', hit: false }];
  assert.deepEqual(segments('Hydraulique Lyon 1000', ['Hydraulique <b>Lyon</b> 1000']), want);
  assert.deepEqual(segments('Hydraulique Lyon 1000', ['…<em>Lyon</em>…']), want);
});

test('segments: a snippet with no tag marks nothing (an unmatched field is not a match)', () => {
  assert.deepEqual(segments('Lyon', ['Lyon']), [{ text: 'Lyon', hit: false }]);
  assert.deepEqual(segments('abc', []), [{ text: 'abc', hit: false }]);
  assert.deepEqual(segments('abc', undefined), [{ text: 'abc', hit: false }]);
});

test('segments: every occurrence of a tagged word is marked, case-insensitively', () => {
  assert.deepEqual(segments('lyon et Lyon', ['<b>Lyon</b>']), [
    { text: 'lyon', hit: true }, { text: ' et ', hit: false }, { text: 'Lyon', hit: true }]);
});

test('segments: regex characters in a tagged word are literal; markup in the data stays text', () => {
  assert.deepEqual(segments('a.c abc', ['<b>a.c</b>']), [{ text: 'a.c', hit: true }, { text: ' abc', hit: false }]);
  assert.equal(segments('<b>x</b>', ['<b>x</b>']).map((s) => s.text).join(''), '<b>x</b>');
});

test('segments: a letter whose lower case is longer does not shift the marks', () => {
  // 'İ'.toLowerCase() is two code units; marks must still land on the right letters
  assert.deepEqual(segments('İstanbul Lyon', ['<b>Lyon</b>']), [
    { text: 'İstanbul ', hit: false }, { text: 'Lyon', hit: true }]);
});

test('hitView: entity, stable key, ordered fields with their highlights, the link as the hub sent it', () => {
  const v = hitView({
    entity: 'AcmeCustomer', key: { code: 'P-1000' }, score: 0.5,
    fields: { name: 'Hydraulique Lyon 1000', city: 'Lyon' },
    highlights: { city: ['<b>Lyon</b>'], name: ['Hydraulique Lyon 1000'] },
    canonicalLink: '/api/entities/AcmeCustomer?code=P-1000',
  });
  assert.equal(v.entity, 'AcmeCustomer');
  assert.equal(v.key, 'code=P-1000');
  assert.equal(v.link, '/api/entities/AcmeCustomer?code=P-1000');
  assert.deepEqual(v.fields.map((f) => f.name), ['name', 'city']);
  assert.equal(v.fields.find((f) => f.name === 'city').segments.find((s) => s.hit).text, 'Lyon');
  assert.ok(v.fields.find((f) => f.name === 'name').segments.every((s) => !s.hit), 'an untagged snippet is not a match');
});

test('facetChips: one chip per value, the active one marked', () => {
  const chips = facetChips({ segment: [{ value: 'GOLD', count: 2 }, { value: 'SILVER', count: 1 }] }, { segment: 'GOLD' });
  assert.deepEqual(chips, [{ field: 'segment', values: [
    { value: 'GOLD', count: 2, active: true }, { value: 'SILVER', count: 1, active: false }] }]);
  assert.deepEqual(facetChips({}, {}), []);
});

test('asOfLine: says how fresh the index is, or nothing', () => {
  assert.match(asOfLine('2026-10-13T08:00:00Z'), /2026/);
  assert.equal(asOfLine(null), '');
});

// ── the canonical read → the record sheet ────────────────────────────────────────────────────────
test('recordToDetail: exactly one row becomes a sheet, _conflicts removed from the ordinary fields', () => {
  const d = recordToDetail('AcmeCustomer', { code: 'P-1000' }, [{ code: 'P-1000', name: 'Hydraulique', _conflicts: '{}' }]);
  assert.equal(d.status, 'ready');
  assert.equal(d.payload.title, 'AcmeCustomer');
  assert.equal(d.payload.key, 'code=P-1000');
  assert.deepEqual(d.payload.fields.map((f) => f.name), ['code', 'name']);
  assert.deepEqual(d.payload.conflicts, []);
  assert.equal(d.payload.conflictLedger, true);
});

test('recordToDetail: a decoded ledger lists each divergent column with its sources', () => {
  const ledger = JSON.stringify({ city: [{ src: 'crm', value: 'Lyon' }, { src: 'erp', value: 'Lille' }] });
  const d = recordToDetail('C', { code: '1' }, [{ code: '1', city: 'Lyon', _conflicts: ledger }]);
  assert.deepEqual(d.payload.conflicts, [{ column: 'city', label: 'city', sources: [
    { src: 'crm', value: 'Lyon' }, { src: 'erp', value: 'Lille' }] }]);
});

test('recordToDetail: a ledger that arrives as an object is read the same', () => {
  const d = recordToDetail('C', { code: '1' }, [{ code: '1', _conflicts: { city: [{ src: 'crm', value: 'Lyon' }] } }]);
  assert.equal(d.payload.conflicts.length, 1);
  assert.equal(d.payload.conflictLedger, true);
});

test('recordToDetail: an unreadable ledger is never reported as "no conflict"', () => {
  const d = recordToDetail('C', { code: '1' }, [{ code: '1', _conflicts: '{not json' }]);
  assert.equal(d.status, 'ready');
  assert.deepEqual(d.payload.conflicts, []);
  assert.equal(d.payload.conflictLedger, false);
});

test('recordToDetail: zero rows means the hit went stale', () => {
  const d = recordToDetail('C', { code: '1' }, []);
  assert.equal(d.status, 'error');
  assert.match(d.message, /introuvable/);
});

test('recordToDetail: several rows are an integrity anomaly, never the first one', () => {
  const d = recordToDetail('C', { code: '1' }, [{ code: '1', name: 'a' }, { code: '1', name: 'b' }]);
  assert.equal(d.status, 'error');
  assert.match(d.message, /plusieurs/);
  assert.equal(d.payload, undefined);
});

test('recordToDetail: anything that is not an array is an unreadable answer, not a sheet', () => {
  assert.equal(recordToDetail('C', { code: '1' }, { code: '1' }).status, 'error');
  assert.equal(recordToDetail('C', { code: '1' }, null).status, 'error');
});

// ── the probe: presence from the hub's own two answers ───────────────────────────────────────────
const fakeApi = (answers) => ({
  json: async (path) => {
    const a = answers[path];
    if (a instanceof Error) throw a;
    return a;
  },
});

test('probeSearch: reads the capability and the indexes, and answers with availability', async () => {
  const api = fakeApi({ '/api/search/capability': { state: 'available' }, '/api/search-indexes': [{ name: 'i' }] });
  assert.equal(await probeSearch(api), true);
  assert.equal(await probeSearch(fakeApi({ '/api/search/capability': { state: 'unavailable' }, '/api/search-indexes': [{ name: 'i' }] })), false);
  assert.equal(await probeSearch(fakeApi({ '/api/search/capability': { state: 'available' }, '/api/search-indexes': [] })), false);
  assert.equal(await probeSearch(fakeApi({ '/api/search/capability': { state: 'degraded' }, '/api/search-indexes': [{ name: 'i' }] })), true);
});

test('probeSearch: a failing read rejects, so the caller keeps the tab', async () => {
  const api = fakeApi({ '/api/search/capability': new Error('boom'), '/api/search-indexes': [] });
  await assert.rejects(() => probeSearch(api));
});

// ── Clear: back to the initial idle screen ───────────────────────────────────────────────────────
test('clearedSearch: the whole screen back to idle — query, facets, results, errors, record sheet', () => {
  assert.deepEqual(clearedSearch(), { q: '', facets: {}, state: { status: 'idle' }, detail: null });
});

test('clearedSearch: every call is a fresh object (no shared state between clears)', () => {
  const a = clearedSearch();
  a.facets.x = 'y';
  assert.deepEqual(clearedSearch().facets, {});
});

test('canClear: false on the initial screen, true as soon as anything is left to clear', () => {
  const idle = clearedSearch();
  assert.equal(canClear(idle), false);
  assert.equal(canClear({ ...idle, q: 'a' }), true);
  assert.equal(canClear({ ...idle, facets: { f: 'v' } }), true);
  assert.equal(canClear({ ...idle, state: { status: 'ready', hits: [] } }), true);
  assert.equal(canClear({ ...idle, state: { status: 'error', message: 'm' } }), true);
  assert.equal(canClear({ ...idle, state: { status: 'loading' } }), true);
  assert.equal(canClear({ ...idle, detail: { status: 'loading' } }), true);
});

test('Search component: the clear ✕ is a labelled plain button shown only when there is something to clear, and never searches', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/search.mjs', import.meta.url), 'utf8');
  assert.match(src, /canClear\(/, 'the button is conditioned on canClear');
  assert.match(src, /<button class="clear" type="button"[^>]*aria-label="Effacer la recherche"[^>]*onClick=\$\{clear\}>✕<\/button>/);
  const fn = src.match(/function clear\(\) \{[\s\S]*?\n  \}/);
  assert.ok(fn, 'clear() exists');
  assert.doesNotMatch(fn[0], /\brun\(/, 'clearing sends no search');
  assert.match(fn[0], /ticket\.current \+= 1/, 'a search in flight is dropped');
  assert.match(fn[0], /openTicket\.current \+= 1/, 'a record sheet in flight is dropped');
});

// ── the business title (#1044) ───────────────────────────────────────────────────────────────────
const titled = {
  entity: 'PwaSearchProduct', key: { product_id: '1024944' }, canonicalLink: '/api/entities/PwaSearchProduct?product_id=1024944',
  title: '2195.300.037', titleField: 'reference',
  fields: { reference: '2195.300.037', description: 'Pompe Badu Resort' },
  highlights: { reference: ['<b>2195</b>.300.037'], description: ['<b>Pompe</b> Badu Resort'] },
};

test('hitView: a declared title becomes the heading, with its own highlights; the entity stays as a quiet type', () => {
  const v = hitView(titled);
  assert.equal(v.heading, '2195.300.037');
  assert.deepEqual(v.headingSegments.filter((s) => s.hit).map((s) => s.text), ['2195']);
  assert.equal(v.entity, 'PwaSearchProduct');
  assert.equal(v.key, 'product_id=1024944');
  assert.equal(v.sub, 'PwaSearchProduct · product_id=1024944');
});

test('hitView: when the heading already IS the entity name, the line under it is the key alone', () => {
  const v = hitView({ ...titled, title: null, titleField: null });
  assert.equal(v.heading, 'PwaSearchProduct');
  assert.equal(v.sub, 'product_id=1024944');
});

test('hitView: the title field is not repeated among the ordinary field chips', () => {
  assert.deepEqual(hitView(titled).fields.map((f) => f.name), ['description']);
});

test('hitView: without a title the heading is the entity name and every field stays a chip, as before', () => {
  const v = hitView({ ...titled, title: null, titleField: null });
  assert.equal(v.heading, 'PwaSearchProduct');
  assert.deepEqual(v.headingSegments, [{ text: 'PwaSearchProduct', hit: false }]);
  assert.deepEqual(v.fields.map((f) => f.name), ['reference', 'description']);
});

test('hitView: a response from a hub that predates titles (no title keys at all) is the old card', () => {
  const { title, titleField, ...old } = titled;
  const v = hitView(old);
  assert.equal(v.heading, 'PwaSearchProduct');
  assert.equal(v.fields.length, 2);
});

test('hitView: a title is never taken from the first field when the hub declared none', () => {
  const v = hitView({ ...titled, title: null, titleField: null, fields: { status: 'open', reference: 'X' } });
  assert.equal(v.heading, 'PwaSearchProduct');
});

test('Search component: the card draws the heading and a quiet "entity · key" line', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/search.mjs', import.meta.url), 'utf8');
  assert.match(src, /v\.headingSegments/);
  assert.match(src, /class="sub"[^>]*>\$\{v\.sub\}</);
});

test('hitView: a titleField with no title never makes a field vanish from the card', () => {
  const v = hitView({ ...titled, title: null, titleField: 'reference' });
  assert.equal(v.heading, 'PwaSearchProduct');
  assert.deepEqual(v.fields.map((f) => f.name), ['reference', 'description']);
});

// ── external links on hits (#1051) ───────────────────────────────────────────────────────────────
const linkedHit = {
  entity: 'Product', key: { id: '1' }, canonicalLink: '/api/entities/Product?id=1',
  fields: { reference: '2195.300.037', description: 'Pompe' }, highlights: {},
  links: [{ field: 'reference', url: 'https://erp.example.com/articles/{reference}' }],
};

test('hitView: the hub\'s links become CellLink links on their field, and the hit\'s fields are the row they fill from', () => {
  const v = hitView(linkedHit);
  assert.deepEqual(v.links, [{ column: 'reference', url: 'https://erp.example.com/articles/{reference}' }]);
  assert.deepEqual(v.row, { reference: '2195.300.037', description: 'Pompe' });
});

test('hitView: a hit without links has none — and its other fields are exactly as before', () => {
  const { links, ...plain } = linkedHit;
  const v = hitView(plain);
  assert.deepEqual(v.links, []);
  assert.deepEqual(v.fields.map((f) => f.name), ['reference', 'description']);
});

test('hitView: the title field keeps its link even though it leaves the chips', () => {
  const v = hitView({ ...linkedHit, title: '2195.300.037', titleField: 'reference' });
  assert.deepEqual(v.fields.map((f) => f.name), ['description']);
  assert.equal(v.links[0].column, 'reference');
  assert.equal(v.row.reference, '2195.300.037');
});

test('the Search tab draws a linked value, and a linked title, through CellLink — inside the card that opens the sheet', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/search.mjs', import.meta.url), 'utf8');
  assert.match(src, /import \{ CellLink \} from '\.\/celllink\.mjs'/);
  assert.equal((src.match(/<\$\{CellLink\}/g) || []).length, 2, 'once for the heading, once for the chips');
  assert.match(src, /links=\$\{v\.links\} column=\$\{f\.name\} row=\$\{v\.row\}/);
  assert.match(src, /column=\$\{v\.titleField\}/);
});

// ── the entity's column labels (#1055) ───────────────────────────────────────────────────────────
const LABELS = { Product: { category_description: 'Catégorie', reference: 'Référence' }, Order: { reference: 'N° de commande' } };

test('labelOf: the entity\'s label, else the raw name — never an empty or invented one', () => {
  assert.equal(labelOf(LABELS, 'Product', 'category_description'), 'Catégorie');
  assert.equal(labelOf(LABELS, 'Product', 'family'), 'family');
  assert.equal(labelOf(LABELS, 'Other', 'reference'), 'reference');
  assert.equal(labelOf(undefined, 'Product', 'reference'), 'reference');
  assert.equal(labelOf({ Product: { reference: '' } }, 'Product', 'reference'), 'reference');
});

test('hitView: each chip carries its label, the raw name stays as the field name', () => {
  const v = hitView({ entity: 'Product', key: { id: '1' }, canonicalLink: '/x', fields: { reference: 'A1', family: 'Pompes' }, highlights: {} }, LABELS);
  assert.deepEqual(v.fields.map((f) => [f.name, f.label]), [['reference', 'Référence'], ['family', 'family']]);
});

test('hitView without labels is exactly as before: label = name', () => {
  const v = hitView({ entity: 'Product', key: { id: '1' }, canonicalLink: '/x', fields: { reference: 'A1' }, highlights: {} });
  assert.equal(v.fields[0].label, 'reference');
});

test('recordToDetail: the sheet labels every column the entity names, the others keep their raw name', () => {
  const d = recordToDetail('Product', { id: '1' }, [{ id: 1, reference: 'A1', category_description: 'Pompes', _conflicts: '{}' }], LABELS);
  assert.deepEqual(d.payload.fields.map((f) => [f.name, f.label]), [['id', 'id'], ['reference', 'Référence'], ['category_description', 'Catégorie']]);
});

test('facetLabel: shown only when every entity of the page that has the column agrees', () => {
  assert.equal(facetLabel(LABELS, ['Product'], 'reference'), 'Référence');
  assert.equal(facetLabel(LABELS, ['Product', 'Order'], 'reference'), 'reference', 'two entities disagree: the raw name');
  assert.equal(facetLabel(LABELS, ['Product', 'Order'], 'category_description'), 'Catégorie', 'one entity has it');
  assert.equal(facetLabel(LABELS, ['Product'], 'family'), 'family');
  assert.equal(facetLabel(LABELS, [], 'reference'), 'reference');
});

test('mergeLabels: a later page adds entities and never drops what an earlier one gave', () => {
  const merged = mergeLabels({ Product: { reference: 'Référence' } }, { Order: { reference: 'N°' } });
  assert.deepEqual(Object.keys(merged), ['Product', 'Order']);
  assert.deepEqual(mergeLabels({ A: { x: 'first' } }, { A: { x: 'later' } }), { A: { x: 'first' } }, 'an entity keeps the labels it first came with');
  assert.deepEqual(mergeLabels(undefined, undefined), {});
  assert.deepEqual(mergeLabels({ A: { x: 'X' } }, undefined), { A: { x: 'X' } });
});

test('the Search tab draws labels on chips, facets and the sheet — raw names stay the keys', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/search.mjs', import.meta.url), 'utf8');
  assert.match(src, /\$\{f\.label \+ ' '\}/);
  assert.match(src, /facetLabel\(/);
  assert.match(src, /recordToDetail\(hit\.entity, hit\.rawKey, rows, labels\)/);
  assert.match(src, /mergeLabels\(/);
});

test('recordToDetail: the divergence block names a column by its label too', () => {
  const d = recordToDetail('Product', { id: '1' }, [{ id: 1, reference: 'A1', _conflicts: { reference: [{ src: 'erp', value: 'A1' }, { src: 'crm', value: 'A-1' }] } }], LABELS);
  assert.equal(d.payload.conflicts[0].column, 'reference');
  assert.equal(d.payload.conflicts[0].label, 'Référence');
});
