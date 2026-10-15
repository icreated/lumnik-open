import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { TABS } from '../../main/resources/META-INF/resources/app/src/tabs.mjs';

// The shell is closed for modification (docs/superpowers/specs/2026-10-13-hub-facets-design.md,
// D11): a tab is ONE entry in tabs.mjs, so app.mjs and tabbar.mjs name no tab. This guard is what
// keeps that sentence true — it refuses a quoted tab id the day someone hand-wires one again.
const SRC = 'lumnik-hub/src/main/resources/META-INF/resources/app/src';
// From the registry itself, so a new tab is guarded the day it is added; `search` is #1028's.
const TAB_IDS = [...new Set([...TABS.map((t) => t.id), 'search'])];

for (const file of ['components/app.mjs', 'components/tabbar.mjs']) {
  test(`${file} names no tab id`, () => {
    const code = readFileSync(join(SRC, file), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    const hits = TAB_IDS.filter((id) => new RegExp(`['"\`]${id}['"\`]`).test(code));
    assert.deepEqual(hits, [], `${file} hand-wires a tab: add it to tabs.mjs instead (${hits.join(', ')})`);
  });
}
