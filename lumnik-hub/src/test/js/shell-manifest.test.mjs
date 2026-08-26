import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

// sw.js enumerates the shell by hand, and a module missing from that list simply fails to load
// for an offline visitor — the app breaks where nothing was tested. Adding a source file and
// forgetting the list is a one-line omission with no other symptom, so it gets a guard.
const APP = 'lumnik-hub/src/main/resources/META-INF/resources/app';

function sources(dir, prefix) {
  return readdirSync(join(APP, dir), { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? sources(join(dir, e.name), `${prefix}${e.name}/`)
                    : e.name.endsWith('.mjs') ? [`${prefix}${e.name}`] : []);
}

test('every app source module is listed in the service worker shell', () => {
  const sw = readFileSync(join(APP, 'sw.js'), 'utf8');
  const missing = sources('src', './src/').filter((p) => !sw.includes(`'${p}'`));
  assert.deepEqual(missing, [], `not cached — an offline visitor loses these: ${missing.join(', ')}`);
});
