import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// #2004: the vendored libraries carry no version banner; LICENSES.md holds the SHA-256 of each shipped file.
const VENDOR = fileURLToPath(new URL('../../main/resources/META-INF/resources/app/vendor/', import.meta.url));

test('every vendored file is the one LICENSES.md records, and every one is recorded', () => {
  const recorded = Object.fromEntries(
    [...readFileSync(VENDOR + 'LICENSES.md', 'utf8').matchAll(/^\| `([^`]+)` \| `([0-9a-f]{64})` \|$/gm)].map(m => [m[1], m[2]]),
  );
  const shipped = readdirSync(VENDOR).filter(f => f !== 'LICENSES.md').sort();
  assert.deepEqual(shipped, Object.keys(recorded).sort());
  for (const f of shipped) {
    assert.equal(createHash('sha256').update(readFileSync(VENDOR + f)).digest('hex'), recorded[f], f);
  }
});
