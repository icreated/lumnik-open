import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ROW_QUERY, WIDE_QUERY, rowRepresentation } from '../../main/resources/META-INF/resources/app/src/layout.mjs';

const APP = '../../main/resources/META-INF/resources/app/';
const read = f => readFileSync(new URL(APP + f, import.meta.url), 'utf8');

test('a viewport gets exactly one row representation', () => {
  assert.equal(rowRepresentation(false), 'cards');
  assert.equal(rowRepresentation(true), 'table');
});

test('the queries match the breakpoints the stylesheet declares', () => {
  const css = read('styles.css');
  for (const q of [ROW_QUERY, WIDE_QUERY]) {
    assert.ok(css.includes('@media ' + q), `styles.css has no @media ${q}`);
  }
});

test('rows are no longer hidden by CSS: both representations were mounted before', () => {
  const css = read('styles.css');
  assert.ok(!/\.only-(phone|wide)/.test(css), 'dead toggle classes left in styles.css');
  for (const f of ['src/components/cards.mjs', 'src/components/table.mjs']) {
    assert.ok(!/only-(phone|wide)/.test(read(f)), `${f} still carries a CSS visibility toggle`);
  }
});

test('fiches mounts one representation chosen by the viewport', () => {
  const src = read('src/components/fiches.mjs');
  assert.equal((src.match(/<\$\{Cards\}/g) || []).length, 1);
  assert.equal((src.match(/<\$\{Table\}/g) || []).length, 1);
  assert.ok(/rowRepresentation\(/.test(src), 'fiches does not pick through rowRepresentation');
});
