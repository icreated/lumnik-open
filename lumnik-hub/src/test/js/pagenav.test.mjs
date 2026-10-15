import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const app = (p) => readFile(new URL(`../../main/resources/META-INF/resources/app/${p}`, import.meta.url), 'utf8');

// #1049: the same pager state drawn above and below the results, the active view titled.
test('Fiches draws ONE PageNav definition twice — above and below the results — so there is one pager and one wiring', async () => {
  const src = await app('src/components/fiches.mjs');
  assert.equal((src.match(/<\$\{PageNav\}/g) || []).length, 1, 'the controls are defined once');
  assert.match(src, /pageNav\('haut'\)/);
  assert.match(src, /pageNav\('bas'\)/);
  const top = src.indexOf("pageNav('haut')"), rows = src.indexOf('<${Table}'), bottom = src.indexOf("pageNav('bas')");
  assert.ok(top > 0 && top < rows && rows < bottom, 'haut < résultats < bas');
  for (const call of ['pagerRef.current.first()', 'pagerRef.current.prev()', 'pagerRef.current.next()'])
    assert.equal(src.split(call).length - 1, 1, `${call} is wired once`);
});

test('Fiches titles the active view with its declared title, above the results', async () => {
  const src = await app('src/components/fiches.mjs');
  assert.match(src, /<h2 class="view-title">\$\{result\.title \|\| currentView\.title \|\| currentView\.name\}<\/h2>/);
  assert.ok(src.indexOf('view-title') < src.indexOf("pageNav('haut')"), 'the title comes first');
  assert.doesNotMatch(src, /Catalogue des articles/, 'no hard-coded label');
});

test('PageNav keeps its three actions, names each control and each group for assistive technology', async () => {
  const src = await app('src/components/pagenav.mjs');
  for (const label of ['« Début', '‹ Précédent', 'Suivant ›']) assert.match(src, new RegExp(label));
  for (const name of ['Première page', 'Page précédente', 'Page suivante']) assert.match(src, new RegExp(`aria-label="${name}"`));
  assert.match(src, /<nav class="pagenav" aria-label=\$\{`Pagination \(\$\{where\}\)`\}/);
  assert.match(src, /aria-busy=\$\{loading \? 'true' : 'false'\}/);
  assert.match(src, /disabled=\$\{loading \|\| !canFirst\}/);
  assert.match(src, /disabled=\$\{loading \|\| !canPrev\}/);
  assert.match(src, /disabled=\$\{loading \|\| !canNext\}/);
});

test('the controls are themed buttons, right-aligned, wrapping, with every state visible', async () => {
  const css = await app('styles.css');
  // exactly the page-navigation section: from its marker to the next top-level comment
  const start = css.indexOf('/* ---- page navigation');
  const end = css.indexOf('\n/*', start + 10);
  assert.ok(start >= 0 && end > start, 'the section is delimited');
  const block = css.slice(start, end);
  assert.match(block, /\.pagenav-row \{[^}]*justify-content: flex-end/);
  assert.match(block, /\.pagenav-row \{[^}]*flex-wrap: wrap/);
  assert.match(block, /\.pagenav-row button:hover/);
  assert.match(block, /\.pagenav-row button:active/);
  assert.match(block, /\.pagenav-row button:focus-visible/);
  assert.match(block, /\.pagenav-row button:disabled/);
  assert.match(block, /\.pagenav\[aria-busy="true"\]/);
  assert.match(block, /@media \(pointer: coarse\)/);
  assert.match(block, /var\(--edge\)/);
  assert.match(css, /\.view-title \{/);
});
