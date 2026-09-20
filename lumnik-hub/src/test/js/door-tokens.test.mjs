import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DOOR_LIFECYCLE, DOOR_CONFLICTS } from '../../main/resources/META-INF/resources/app/src/door-tokens.mjs';

// The oracle at contracts/ask-door-tokens.txt is the cross-language pin for AskResult.DOORS
// (#522): a rename on the Java side without updating it here breaks THIS test, instead of
// silently dropping the gesture format.mjs renders.
test('the PWA door tokens match the cross-language oracle', () => {
  const oraclePath = fileURLToPath(new URL('../../../../contracts/ask-door-tokens.txt', import.meta.url));
  const oracle = readFileSync(oraclePath, 'utf8')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .sort();
  const known = [DOOR_LIFECYCLE, DOOR_CONFLICTS].sort();

  assert.deepStrictEqual(known, oracle);
});
