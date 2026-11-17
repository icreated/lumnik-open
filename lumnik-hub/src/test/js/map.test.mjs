import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {
  mapState, splitRows, bounds, unplacedLabel, capLabel, graticule, escapeText, createMapReader,
} from '../../main/resources/META-INF/resources/app/src/map.mjs';

test('the switch is offered only for a map whose point is usable', () => {
  assert.deepEqual(mapState({ rows: [] }), { offered: false, error: null });
  assert.deepEqual(mapState({ map: { point: 'site', unplaced: 0 } }), { offered: true, error: null });
  assert.deepEqual(mapState({ map: { point: 'site', error: 'not usable' } }), { offered: false, error: 'not usable' });
});

test('rows split on the hub verdict alone, never on their columns', () => {
  const a = { customer_no: 'C-1', _map: { lat: 45.76, lon: 4.83 } };
  const b = { customer_no: 'C-2', _map: null, latitude: 45.7, longitude: 4.8 };
  const c = { customer_no: 'C-3' };
  const { placed, unplaced } = splitRows([a, b, c]);
  assert.deepEqual(placed, [{ row: a, lat: 45.76, lon: 4.83 }]);
  assert.deepEqual(unplaced, [b, c]);
});

test('bounds frame every placed point, and there is none to frame without one', () => {
  assert.equal(bounds([]), null);
  assert.deepEqual(bounds([{ lat: 45.7, lon: 4.8 }]), [[45.7, 4.8], [45.7, 4.8]]);
  assert.deepEqual(bounds([{ lat: 45.7, lon: 4.8 }, { lat: -33.8, lon: 151.2 }]), [[-33.8, 4.8], [45.7, 151.2]]);
});

test('the unplaced rows are counted in a sentence, and the cap is spoken', () => {
  assert.equal(unplacedLabel(0), '');
  assert.equal(unplacedLabel(1), '1 fiche sans position');
  assert.equal(unplacedLabel(2), '2 fiches sans position');
  assert.equal(capLabel({ rows: [1, 2], truncated: false }), '');
  assert.match(capLabel({ rows: new Array(1000), truncated: true }), /^1000 premières fiches/);
});

test('the neutral background is a grid of parallels and meridians', () => {
  const lines = graticule(10);
  assert.equal(lines.length, 17 + 37);
  assert.deepEqual(lines[0], [[-80, -180], [-80, 180]]);
});

test('the operator attribution reaches Leaflet as text', () => {
  assert.equal(escapeText('© <b>OSM</b> & "co"'), '© &lt;b&gt;OSM&lt;/b&gt; &amp; &quot;co&quot;');
  assert.equal(escapeText(null), '');
});

// #2057 review: a map read must never write the header count once the map is off screen.
function harness() {
  const pending = [];
  const states = [];
  const counts = [];
  const reader = createMapReader({
    read: (filters) => new Promise((resolve, reject) => pending.push({ filters, resolve, reject })),
    onState: (s) => states.push(s.status),
    onCount: (text) => counts.push(text),
  });
  return { reader, pending, states, counts };
}
const flush = () => new Promise((r) => setTimeout(r, 0));
const RESULT = { rows: [{ _map: null }, { _map: { lat: 1, lon: 2 } }], truncated: false };

test('open Carte, back to Liste before the answer: the answer lands without touching the count', async () => {
  const { reader, pending, counts, states } = harness();
  const filters = [];
  reader.show(filters);
  reader.hide();
  pending[0].resolve(RESULT);
  await flush();
  assert.deepEqual(counts, [], 'the list owns the header');
  assert.deepEqual(states, ['loading', 'ready'], 'the answer is kept for the next opening');
  reader.show(filters);
  assert.equal(pending.length, 1, 'reopened under the same filters: no second read');
  assert.deepEqual(counts, ['2'], 'shown again: the map counts its rows');
});

test('the tab left before the answer: nothing lands at all', async () => {
  const { reader, pending, counts, states } = harness();
  reader.show([]);
  reader.reset();
  pending[0].resolve(RESULT);
  await flush();
  assert.deepEqual(counts, []);
  assert.deepEqual(states, ['loading']);
});

test('the newest read wins, and a hidden map reads again only when shown', async () => {
  const { reader, pending, counts } = harness();
  const a = [], b = ['name~x'];
  reader.show(a);
  reader.refilter(b);
  pending[0].resolve({ rows: new Array(5).fill({ _map: null }), truncated: true });
  pending[1].resolve(RESULT);
  await flush();
  assert.deepEqual(counts, ['2']);
  reader.hide();
  assert.equal(reader.refilter(['other']), undefined);
  assert.equal(pending.length, 2);
});

test('Fiches resets its map reader when the tab is left, and hides it when the list is shown', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/fiches.mjs', import.meta.url), 'utf8');
  assert.match(src, /useEffect\(\(\) => \(\) => \{[^\n]*mapReader\.current\.reset\(\);/);
  assert.match(src, /mapReader\.current\.hide\(\);/);
  assert.doesNotMatch(src, /onCount\(`\$\{result/, 'the map count is written by the reader only');
});
