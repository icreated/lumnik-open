import { test } from 'node:test';
import assert from 'node:assert/strict';
import { kindOf, operatorsFor, resolveOp, inputTypeFor, buildFilter, chipLabel } from '../../main/resources/META-INF/resources/app/src/filters.mjs';

const ORDERED = ['=', '>=', '<=', '>', '<'];
const OPS_BY_KIND = { choice: ['='], text: ['~', '='], number: ORDERED, date: ORDERED, datetime: ORDERED };
const KIND_OF_TYPE = { numeric: 'number', date: 'date', 'timestamp with time zone': 'datetime', text: 'text', money: 'text' };
// A column as the hub serves it: kind and operators are the hub's answer, not derived here.
const col = (name, pgType, allowedValues = [], label = name) => {
  const kind = allowedValues.length ? 'choice' : (KIND_OF_TYPE[pgType] || 'text');
  return { name, label, pgType, allowedValues, kind, operators: OPS_BY_KIND[kind] };
};

test("kindOf reads the hub's kind — it never guesses from the type name", () => {
  assert.equal(kindOf(col('segment', 'text', ['GOLD'])), 'choice');
  assert.equal(kindOf(col('amount', 'numeric')), 'number');
  assert.equal(kindOf({ name: 'p', pgType: 'numeric', kind: 'text', operators: ['~', '='] }), 'text');
});

test('kindOf: a response without the metadata falls back to text, never throws', () => {
  assert.equal(kindOf({ name: 'c' }), 'text');
  assert.equal(kindOf({ name: 'c', pgType: 'numeric' }), 'text');
});

test('operatorsFor: exactly the operators the hub announced, in its order', () => {
  assert.deepEqual(operatorsFor(col('a', 'numeric')).map(o => o.op), ORDERED);
  assert.deepEqual(operatorsFor(col('p', 'money')).map(o => o.op), ['~', '=']);   // no ordering for money
  assert.deepEqual(operatorsFor({ name: 'x', kind: 'number', operators: ['=', '>'] }).map(o => o.op), ['=', '>']);
  assert.deepEqual(operatorsFor({ name: 'c' }).map(o => o.op), ['~', '=']);       // metadata missing
  assert.equal(operatorsFor(col('a', 'numeric'))[1].label, '≥');
});

test('inputTypeFor', () => {
  assert.equal(inputTypeFor('number'), 'number');
  assert.equal(inputTypeFor('date'), 'date');
  assert.equal(inputTypeFor('datetime'), 'datetime-local');
  assert.equal(inputTypeFor('text'), 'text');
  assert.equal(inputTypeFor('choice'), 'select');
});

test('buildFilter: column + op + value in the hub grammar', () => {
  assert.equal(buildFilter(col('city', 'text'), '~', 'Lyon'), 'city~Lyon');
  assert.equal(buildFilter(col('amount', 'numeric'), '>=', '10.5'), 'amount>=10.5');
  assert.equal(buildFilter(col('due', 'date'), '<', '2026-01-31'), 'due<2026-01-31');
  assert.equal(buildFilter(col('segment', 'text', ['GOLD']), '=', 'GOLD'), 'segment=GOLD');
});

test('buildFilter: the value is trimmed; blank means no filter', () => {
  assert.equal(buildFilter(col('city', 'text'), '~', '  Lyon '), 'city~Lyon');
  assert.equal(buildFilter(col('city', 'text'), '~', '   '), null);
  assert.equal(buildFilter(col('city', 'text'), '~', undefined), null);
});

test('buildFilter: no client-side referee — text is passed verbatim, even quoted', () => {
  assert.equal(buildFilter(col('city', 'text'), '=', "'Lyon'"), "city='Lyon'");
  assert.equal(buildFilter(col('city', 'text'), '~', 'a>b=c'), 'city~a>b=c');
});

test('chipLabel: business label + operator word + value; unknown filters stay raw', () => {
  const cols = [col('amount', 'numeric', [], 'Montant'), col('city', 'text', [], 'Ville')];
  assert.equal(chipLabel('amount>=10', cols), 'Montant ≥ 10');
  assert.equal(chipLabel('city~Lyon', cols), 'Ville contient Lyon');
  assert.equal(chipLabel('city=Lyon', cols), 'Ville = Lyon');
  assert.equal(chipLabel('mystery=1', cols), 'mystery=1');
  assert.equal(chipLabel('amount>=10', []), 'amount>=10');
});

test('resolveOp: keeps the chosen operator only while the column still offers it', () => {
  assert.equal(resolveOp(col('a', 'numeric'), '>='), '>=');
  assert.equal(resolveOp(col('s', 'text', ['GOLD']), '>='), '=');   // switched to a selection column
  assert.equal(resolveOp(col('c', 'text'), null), '~');             // nothing chosen yet → the first announced
  assert.equal(resolveOp(col('d', 'date'), '~'), '=');              // stale after a view change
  assert.equal(resolveOp(col('p', 'money'), '>'), '~');             // the hub does not order money
});
test("inputTypeFor: an unknown kind degrades to a text box", () => assert.equal(inputTypeFor("barcode"), "text"));

test('an empty operator list degrades like a missing one — the bar never has nothing to offer', () => {
  assert.deepEqual(operatorsFor({ name: 'c', kind: 'number', operators: [] }).map(o => o.op), ['~', '=']);
  assert.equal(resolveOp({ name: 'c', operators: [] }, '>'), '~');
});
