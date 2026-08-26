import { test } from 'node:test';
import assert from 'node:assert/strict';
import { since, cell, dataAsOfLabel } from '../../main/resources/META-INF/resources/app/src/format.mjs';

test('since(): 30s → "il y a 1 min" (Math.max(1,...) floors sub-minute to 1)', () => {
  const now = Date.parse('2026-07-12T10:00:30Z');
  assert.equal(since('2026-07-12T10:00:00Z', now), 'il y a 1 min');
});

test('since(): 59 min → minute branch, rounded', () => {
  const now = Date.parse('2026-07-12T10:59:00Z');
  assert.equal(since('2026-07-12T10:00:00Z', now), 'il y a 59 min');
});

test('since(): 2h → hour branch, rounded', () => {
  const now = Date.parse('2026-07-12T12:00:00Z');
  assert.equal(since('2026-07-12T10:00:00Z', now), 'il y a 2 h');
});

test('since(): 3j → day branch, rounded', () => {
  const now = Date.parse('2026-07-15T10:00:00Z');
  assert.equal(since('2026-07-12T10:00:00Z', now), 'il y a 3 j');
});

test('since(): defaults now to Date.now() when omitted', () => {
  const iso = new Date(Date.now() - 5000).toISOString();
  assert.equal(since(iso), 'il y a 1 min');
});

test('cell(): null → em dash', () => {
  assert.equal(cell(null), '—');
});

test('cell(): undefined → em dash', () => {
  assert.equal(cell(undefined), '—');
});

test('cell(): 0 → "0", not the em dash (falsy but present)', () => {
  assert.equal(cell(0), '0');
});

test('cell(): a string value passes through as a String()', () => {
  assert.equal(cell('x'), 'x');
});

test('dataAsOfLabel: names the freshness in métier words, absent stays absent', () => {
  const now = Date.parse('2026-08-25T12:00:00Z');
  // P6: the dial the meeting question needed — relative, in the PWA's voice.
  assert.equal(dataAsOfLabel('2026-08-25T09:00:00Z', now), 'données : il y a 3 h');
  assert.equal(dataAsOfLabel('2026-08-23T09:00:00Z', now), 'données : il y a 2 j');
  // no completed run -> no label, never a fabricated one (absence is stated by silence here)
  assert.equal(dataAsOfLabel(null, now), null);
  assert.equal(dataAsOfLabel(undefined, now), null);
  assert.equal(dataAsOfLabel('', now), null);
});
