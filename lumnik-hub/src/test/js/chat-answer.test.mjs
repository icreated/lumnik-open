import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const app = (p) => readFile(new URL(`../../main/resources/META-INF/resources/app/${p}`, import.meta.url), 'utf8');

// #1046: the API's line breaks in an answer must survive the bubble.
test('the bot answer is a text child of .answer — escaped by Preact, never injected as HTML', async () => {
  const src = await app('src/components/chat.mjs');
  assert.match(src, /<div class="answer">\$\{m\.text\}<\/div>/);
  assert.doesNotMatch(src, /innerHTML|dangerouslySetInnerHTML/);
});

test('.answer keeps newlines and the bubble still wraps long lines', async () => {
  const css = await app('styles.css');
  assert.match(css, /\.bubble \.answer\s*\{[^}]*white-space:\s*pre-wrap/);
  assert.match(css, /\.bubble\s*\{[^}]*overflow-wrap:\s*anywhere/);
});

test('only the answer preserves whitespace — the bubble itself does not', async () => {
  const css = await app('styles.css');
  const bubble = css.match(/\n\.bubble\s*\{[^}]*\}/)[0];
  const bot = css.match(/\n\.bubble\.bot\s*\{[^}]*\}/)[0];
  assert.doesNotMatch(bubble, /white-space/);
  assert.doesNotMatch(bot, /white-space/);
});

// pre-wrap would now show a model's leading/trailing blank lines that the browser used to collapse
test('the answer is trimmed before it is shown', async () => {
  const src = await app('src/components/chat.mjs');
  assert.match(src, /text: \(a\.answer \|\| ''\)\.trim\(\) \|\| '\(réponse vide\)'/);
});
