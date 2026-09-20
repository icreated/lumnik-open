import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  readCachedRegistry, cacheRegistry, resolveCached, resolveFromRegistry, applyIdentity, applyTheme,
} from '../../main/resources/META-INF/resources/app/src/appRegistry.mjs';

function fakeStorage(initial = {}) {
  const m = new Map(Object.entries(initial));
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, v),
    removeItem: (k) => m.delete(k),
    _map: m,
  };
}

test('cacheRegistry then readCachedRegistry round-trips the entries', () => {
  const storage = fakeStorage();
  const entries = [{ name: 'clients', title: 'Clients', scope: 'clients', icon: 'clients',
    accentColor: '#8b7ff0', backgroundColor: '#14121f', default: true }];
  cacheRegistry(entries, storage);
  assert.deepEqual(readCachedRegistry(storage), entries);
});

test('readCachedRegistry: empty storage degrades to an empty list, not a throw', () => {
  assert.deepEqual(readCachedRegistry(fakeStorage()), []);
});

test('readCachedRegistry: corrupt JSON degrades to an empty list, not a throw', () => {
  assert.deepEqual(readCachedRegistry(fakeStorage({ 'lumnik.app.registry': '{not json' })), []);
});

test('readCachedRegistry: a non-array value degrades to an empty list', () => {
  assert.deepEqual(readCachedRegistry(fakeStorage({ 'lumnik.app.registry': '{"oops":true}' })), []);
});

test('cacheRegistry: a storage that throws on write is swallowed, not propagated', () => {
  const storage = { setItem: () => { throw new Error('quota exceeded'); } };
  assert.doesNotThrow(() => cacheRegistry([{ name: 'clients' }], storage));
});

test('resolveCached: empty cache, ?m=clients resolves to the built-in Clients identity', () => {
  const r = resolveCached('?m=clients', fakeStorage());
  assert.equal(r.name, 'clients');
  assert.equal(r.title, 'Clients');
  assert.equal(r.scope, 'clients');
  assert.equal(r.manifest, 'manifest-clients.json');
  assert.equal(r.iconFile, 'icon-192.png');
});

test('resolveCached: empty cache, ?m=stock resolves to the built-in Stock identity', () => {
  const r = resolveCached('?m=stock', fakeStorage());
  assert.equal(r.name, 'stock');
  assert.equal(r.manifest, 'manifest-stock.json');
  assert.equal(r.iconFile, 'icon-stock-192.png');
});

test('resolveCached: empty cache, absent ?m= guesses Clients (today\'s hardcoded fallback)', () => {
  assert.equal(resolveCached('', fakeStorage()).name, 'clients');
});

test('resolveCached: empty cache, an unrecognised tag falls to neutral generic — never Clients', () => {
  const r = resolveCached('?m=lumilait', fakeStorage());
  assert.equal(r.name, 'generic');
  assert.equal(r.title, 'lumnik');
  assert.equal(r.manifest, 'manifest-generic.json');
  assert.equal(r.iconFile, 'icon-generic-192.png');
});

test('resolveCached: a cached entry for this exact tag wins over every fallback', () => {
  const storage = fakeStorage();
  cacheRegistry([{ name: 'lumilait', title: 'Lumilait', scope: 'lumilait', icon: 'generic',
    accentColor: '#4fd1a5', backgroundColor: '#f4f1ea', default: false }], storage);
  const r = resolveCached('?m=lumilait', storage);
  assert.equal(r.title, 'Lumilait');
  assert.equal(r.accentColor, '#4fd1a5');
});

test('resolveCached: absent ?m= with a cached default wins over the Clients guess', () => {
  const storage = fakeStorage();
  cacheRegistry([
    { name: 'clients', title: 'Clients', scope: 'clients', icon: 'clients',
      accentColor: '#8b7ff0', backgroundColor: '#14121f', default: false },
    { name: 'crise', title: 'Crise', scope: 'crise', icon: 'alert',
      accentColor: '#f0796b', backgroundColor: '#14121f', default: true },
  ], storage);
  assert.equal(resolveCached('', storage).name, 'crise');
});

test('resolveCached: a cache holding other tags does not leak into an unrelated request', () => {
  const storage = fakeStorage();
  cacheRegistry([{ name: 'lumilait', title: 'Lumilait', scope: 'lumilait', icon: 'generic',
    accentColor: '#4fd1a5', backgroundColor: '#f4f1ea', default: false }], storage);
  const r = resolveCached('?m=nope', storage);
  assert.equal(r.name, 'generic');
});

const CLIENTS = { name: 'clients', title: 'Clients', scope: 'clients', icon: 'clients',
  accentColor: '#8b7ff0', backgroundColor: '#14121f', default: true };
const LUMILAIT = { name: 'lumilait', title: 'Lumilait', scope: 'lumilait', icon: 'generic',
  accentColor: '#4fd1a5', backgroundColor: '#f4f1ea', default: false };
const CRISE = { name: 'crise', title: 'Crise', scope: 'crise', icon: 'alert',
  accentColor: '#f0796b', backgroundColor: '#14121f', default: true };

test('resolveFromRegistry: ?m= matching an entry wears it', () => {
  const r = resolveFromRegistry([CLIENTS, LUMILAIT], '?m=lumilait');
  assert.equal(r.screen, 'tabs');
  assert.equal(r.entry.title, 'Lumilait');
  assert.equal(r.entry.manifest, 'manifest-generic.json');
});

test('resolveFromRegistry: ?m= absent resolves to the declared default', () => {
  const r = resolveFromRegistry([{ ...CLIENTS, default: false }, CRISE], '');
  assert.equal(r.screen, 'tabs');
  assert.equal(r.entry.name, 'crise');
});

test('resolveFromRegistry: ?m= absent, no default, exactly one entry — resolve to it', () => {
  const solo = { ...CLIENTS, default: false };
  const r = resolveFromRegistry([solo], '');
  assert.equal(r.screen, 'tabs');
  assert.equal(r.entry.name, 'clients');
});

// The neutral identity 'refused' carries, so a caller (app.mjs) never has to hand-roll its
// own copy of appRegistry.mjs's NEUTRAL/withDerived — one source, no divergence risk.
const NEUTRAL_RESOLVED = {
  name: 'generic', title: 'lumnik', scope: null, icon: 'generic',
  accentColor: '#8b7ff0', backgroundColor: '#14121f', default: false,
  manifest: 'manifest-generic.json', iconFile: 'icon-generic-192.png',
};

test('resolveFromRegistry: ?m= absent, no default, more than one entry — refused, neutral identity', () => {
  const noDefault = [{ ...CLIENTS, default: false }, { ...LUMILAIT, default: false }];
  assert.deepEqual(resolveFromRegistry(noDefault, ''), { screen: 'refused', entry: NEUTRAL_RESOLVED });
});

test('resolveFromRegistry: ?m= naming nothing in the registry is refused, never a fallthrough', () => {
  assert.deepEqual(resolveFromRegistry([CLIENTS], '?m=nope'), { screen: 'refused', entry: NEUTRAL_RESOLVED });
});

test('resolveFromRegistry: an empty registry (out-of-scope caller) is refused for any ?m=, neutral identity', () => {
  assert.deepEqual(resolveFromRegistry([], '?m=clients'), { screen: 'refused', entry: NEUTRAL_RESOLVED });
  assert.deepEqual(resolveFromRegistry([], ''), { screen: 'refused', entry: NEUTRAL_RESOLVED });
});

function fakeDoc(initial = {}) {
  const style = { props: {}, setProperty(k, v) { this.props[k] = v; } };
  const nodes = {
    'link[rel="manifest"]': { href: initial.manifest || '' },
    'link[rel="apple-touch-icon"]': { href: initial.touchIcon || '' },
    'link[rel="icon"]': { href: initial.favicon || '' },
    'meta[name="theme-color"]': { content: initial.themeColor || '' },
  };
  return {
    title: '',
    documentElement: { style },
    querySelector: (sel) => nodes[sel] || null,
    _nodes: nodes,
  };
}

test('applyIdentity: sets title, manifest, apple-touch-icon and favicon from the entry', () => {
  const doc = fakeDoc();
  applyIdentity({ ...LUMILAIT, manifest: 'manifest-generic.json', iconFile: 'icon-generic-192.png' }, doc);
  assert.equal(doc.title, 'Lumilait');
  assert.equal(doc._nodes['link[rel="manifest"]'].href, 'manifest-generic.json');
  assert.equal(doc._nodes['link[rel="apple-touch-icon"]'].href, 'icon-generic-192.png');
  assert.equal(doc._nodes['link[rel="icon"]'].href, 'icon-generic-192.png');
});

test('applyIdentity: a doc missing a head tag degrades quietly, not a throw', () => {
  const doc = { title: '', querySelector: () => null };
  assert.doesNotThrow(() => applyIdentity({ ...CLIENTS, manifest: 'x', iconFile: 'y' }, doc));
});

test('applyTheme: sets --accent and the meta theme-color tint, leaves --bg untouched', () => {
  const doc = fakeDoc();
  applyTheme(CRISE, doc);
  assert.equal(doc.documentElement.style.props['--accent'], '#f0796b');
  assert.equal(doc._nodes['meta[name="theme-color"]'].content, '#14121f');
  assert.deepEqual(Object.keys(doc.documentElement.style.props), ['--accent']);
});
