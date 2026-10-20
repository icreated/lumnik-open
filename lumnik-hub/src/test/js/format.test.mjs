import { test } from 'node:test';
import assert from 'node:assert/strict';
import { since, cell, dataAsOfLabel, doorGesture, ledgerValue, disputedBlock, analyticAsk } from '../../main/resources/META-INF/resources/app/src/format.mjs';

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

test('doorGesture renders only the doors this surface actually has', () => {
  // The wording promises what the button reaches — the fiches — not the detail screen one tap
  // further where the disagreements are actually shown (#515).
  assert.equal(doorGesture('conflicts'), 'Ouvrir les fiches — les désaccords sont sur chaque fiche');
  assert.equal(doorGesture('lifecycle'), null);
  // A token from a newer hub is silence, never text rendered raw.
  assert.equal(doorGesture('a-door-from-a-newer-hub'), null);
  assert.equal(doorGesture(undefined), null);
});

test('ledgerValue(): null/undefined → "(empty)"; the real empty string and values stay', () => {
  assert.equal(ledgerValue(null), '(empty)');
  assert.equal(ledgerValue(undefined), '(empty)');
  assert.equal(ledgerValue(''), '');
  assert.equal(ledgerValue('Pumps'), 'Pumps');
});

test('disputedBlock(): nothing to show for an empty or missing list', () => {
  assert.equal(disputedBlock([], false), null);
  assert.equal(disputedBlock(undefined, false), null);
});

test('disputedBlock(): counted and excluded read in French, sources with (empty) for null', () => {
  const b = disputedBlock([
    { key: 'P-1008', column: 'city', label: 'Ville', matched: 'Lyon', retained: 'Lyon', counted: true,
      sources: [{ src: 't_erp', value: 'Lyon' }, { src: 't_crm', value: 'Villeurbanne' }] },
    { key: 'P-1002', column: 'city', label: 'Ville', matched: 'Milan', retained: 'Milano', counted: false,
      sources: [{ src: 't_erp', value: 'Milano' }, { src: 't_crm', value: null }] },
  ], true);
  assert.equal(b.title, '⚡ Désaccord sur une valeur filtrée');
  assert.deepEqual(b.items, [
    { head: 'P-1008 · Ville — comptée avec « Lyon »', sources: 't_erp : Lyon · t_crm : Villeurbanne' },
    { head: 'P-1002 · Ville — écartée, alors qu\'une source indique « Milan »', sources: 't_erp : Milano · t_crm : (empty)' },
  ]);
  assert.equal(b.more, '… et d\'autres fiches');
});

test('disputedBlock(): no "more" line when nothing was cut; label falls back to the column', () => {
  const b = disputedBlock([{ key: 'K', column: 'city', label: '', matched: 'X', retained: 'X', counted: true, sources: [] }], false);
  assert.equal(b.items[0].head, 'K · city — comptée avec « X »');
  assert.equal(b.more, null);
});

test('analyticAsk(): the French-only PWA names its answer language (#879)', () => {
  assert.deepEqual(analyticAsk('SO-000004 statut ?', 'desk'),
    { question: 'SO-000004 statut ?', mode: 'analytic', scope: 'desk', lang: 'fr' });
});

test('analyticAsk(): a follow-up carries the sentence it continues; a standalone question carries none (#1097)', () => {
  assert.deepEqual(analyticAsk('Et HAWARD ?', 'desk', 'Les références Badu ?'),
    { question: 'Et HAWARD ?', mode: 'analytic', scope: 'desk', lang: 'fr', previous: 'Les références Badu ?' });
  assert.deepEqual(analyticAsk('Combien ?', 'desk', null),
    { question: 'Combien ?', mode: 'analytic', scope: 'desk', lang: 'fr' });
});
