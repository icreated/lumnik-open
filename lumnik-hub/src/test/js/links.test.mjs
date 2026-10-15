import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { linkOn, resolveLink, followView, settleProbe } from '../../main/resources/META-INF/resources/app/src/links.mjs';

const toArticles = { column: 'name', targetView: 'articles', filters: { category_id: '{category_id}' } };
const toErp = { column: 'category_id', url: 'https://erp.example.com/categories/{category_id}?tab=1' };
const row = { category_id: 1000021, name: 'Pompes', active: true };

test('linkOn: the link declared on a column, or null', () => {
  assert.equal(linkOn([toArticles, toErp], 'name'), toArticles);
  assert.equal(linkOn([toArticles], 'active'), null);
  assert.equal(linkOn(undefined, 'name'), null);
});

test('resolveLink (view): the placeholder takes the row value and the filter is the hub grammar col=value', () => {
  assert.deepEqual(resolveLink(toArticles, row), { kind: 'view', targetView: 'articles', filters: ['category_id=1000021'] });
});

test('resolveLink (view): literal text around a placeholder is kept, every filter is built', () => {
  const l = { column: 'name', targetView: 'a', filters: { code: 'CAT-{category_id}', kind: 'std' } };
  assert.deepEqual(resolveLink(l, row).filters, ['code=CAT-1000021', 'kind=std']);
});

test('resolveLink (view): a value is passed as is — the hub grammar is the referee, not this module', () => {
  assert.deepEqual(resolveLink(toArticles, { category_id: "a=b'c" }).filters, ["category_id=a=b'c"]);
});

test('resolveLink: a missing, null or blank value gives no link at all, never a half-built filter or URL', () => {
  for (const bad of [{}, { category_id: null }, { category_id: '' }, { category_id: '   ' }]) {
    assert.equal(resolveLink(toArticles, bad), null);
    assert.equal(resolveLink(toErp, bad), null);
  }
});

test('resolveLink (external): the value is percent-encoded, so it cannot leave its segment', () => {
  const r = resolveLink(toErp, { category_id: 'a/b?x=1#frag &é' });
  assert.equal(r.kind, 'external');
  assert.equal(r.href, 'https://erp.example.com/categories/a%2Fb%3Fx%3D1%23frag%20%26%C3%A9?tab=1');
  assert.equal(new URL(r.href).host, 'erp.example.com');
});

test('resolveLink (external): a number is written as is, a boolean as its word', () => {
  assert.equal(resolveLink(toErp, row).href, 'https://erp.example.com/categories/1000021?tab=1');
  assert.equal(resolveLink({ column: 'active', url: 'https://x.example.com/{active}' }, row).href, 'https://x.example.com/true');
});

test('resolveLink (external): anything that is not https is never followed, whatever the hub sent', () => {
  for (const url of ['http://x.example.com/{category_id}', 'javascript:alert({category_id})', '//x.example.com/{category_id}']) {
    assert.equal(resolveLink({ column: 'name', url }, row), null);
  }
});

test('resolveLink: a link with neither destination, or a view link without filters, resolves to nothing', () => {
  assert.equal(resolveLink({ column: 'name' }, row), null);
  assert.equal(resolveLink({ column: 'name', targetView: 'a', filters: {} }, row), null);
});

test('followView: the target is looked up ONLY among the views the app was given', () => {
  const list = [{ name: 'categories', title: 'Catégories' }, { name: 'articles', title: 'Catalogue des articles' }];
  const out = followView(resolveLink(toArticles, row), list);
  assert.equal(out.ok, true);
  assert.equal(out.entry, list[1]);
  assert.deepEqual(out.filters, ['category_id=1000021']);
});

test('followView: a target the app cannot see is an honest refusal naming it, never an unfiltered open', () => {
  const out = followView(resolveLink(toArticles, row), [{ name: 'categories' }]);
  assert.equal(out.ok, false);
  assert.match(out.message, /articles/);
  assert.equal(out.entry, undefined);
  assert.equal(out.filters, undefined);
});

test('Fiches: a followed link is probed on a candidate pager and committed only when its first page arrived', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/fiches.mjs', import.meta.url), 'utf8');
  assert.match(src, /followView\(/);
  assert.match(src, /linkRefusal/);
  assert.match(src, /onFollow=\$\{followLink\}/);
});

test('Cards and Table draw a link only as its own control, and stop the row from also opening the record', async () => {
  for (const f of ['cards', 'table', 'celllink']) {
    const src = await readFile(new URL(`../../main/resources/META-INF/resources/app/src/components/${f}.mjs`, import.meta.url), 'utf8');
    if (f === 'celllink') {
      assert.match(src, /rel="noopener noreferrer"/);
      assert.match(src, /target="_blank"/);
      assert.match(src, /const stop = \(e\) => e\.stopPropagation\(\)/, 'the anchor stops the row');
      assert.match(src, /onClick=\$\{stop\} onKeyDown=\$\{stop\}/);
      assert.match(src, /onClick=\$\{\(e\) => \{ e\.stopPropagation\(\); onFollow\(link, row\); \}\}/, 'the button stops the row');
      assert.match(src, /type="button"/);
    } else {
      assert.match(src, /CellLink/);
    }
  }
});

// ── review pass ──────────────────────────────────────────────────────────────────────────────────
test('resolveLink: a padded legacy CHAR value is trimmed before it is used, in a filter and in an address', () => {
  assert.deepEqual(resolveLink(toArticles, { category_id: '  ABC   ' }).filters, ['category_id=ABC']);
  assert.equal(resolveLink(toErp, { category_id: 'ABC   ' }).href, 'https://erp.example.com/categories/ABC?tab=1');
});

test('resolveLink (external): a dot segment is never built — a browser would climb out of the path', () => {
  for (const v of ['.', '..', ' .. ']) assert.equal(resolveLink(toErp, { category_id: v }), null);
  assert.equal(resolveLink(toErp, { category_id: 'a.b' }).href, 'https://erp.example.com/categories/a.b?tab=1');
});

test('settleProbe: wait while loading; a stale probe is dropped whatever it brought; else commit or refuse', () => {
  assert.equal(settleProbe({ loading: true }, false), 'wait');
  assert.equal(settleProbe({ loading: true }, true), 'wait');
  assert.equal(settleProbe({ loading: false, result: { rows: [] } }, true), 'drop');
  assert.equal(settleProbe({ loading: false, error: { message: 'x' } }, true), 'drop');
  assert.equal(settleProbe({ loading: false, result: { rows: [] } }, false), 'commit');
  assert.equal(settleProbe({ loading: false, error: { message: 'x' } }, false), 'refuse');
  assert.equal(settleProbe({ loading: false }, false), 'refuse');
});

test('Fiches: the probe is settled by settleProbe, and leaving the tab silences a pending one', async () => {
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/fiches.mjs', import.meta.url), 'utf8');
  assert.match(src, /settleProbe\(s, mine !== linkSeq\.current\)/);
  assert.match(src, /useEffect\(\(\) => \(\) => \{ linkSeq\.current \+= 1;/);
  assert.match(src, /function applyFilters\(next\) \{\s*linkSeq\.current \+= 1;[^\n]*\n\s*setLinkRefusal\(null\);/);
});

test('the same resolver serves any surface that has a row of named values — a search hit\'s fields included (#1051)', () => {
  const hit = { entity: 'Product', fields: { reference: '2195.300.037', description: 'Pompe' } };
  const link = { column: 'reference', url: 'https://erp.example.com/articles/{reference}' };
  assert.equal(linkOn([link], 'reference'), link);
  assert.equal(resolveLink(link, hit.fields).href, 'https://erp.example.com/articles/2195.300.037');
  assert.equal(resolveLink(link, { description: 'Pompe' }), null);
});
