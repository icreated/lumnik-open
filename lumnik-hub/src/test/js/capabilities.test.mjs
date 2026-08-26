import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hasChat } from '../../main/resources/META-INF/resources/app/src/capabilities.mjs';

// GET /api/bff/v1/me is lm_user-reachable and already returns `modules`, built from the same
// manifest registry /api/platform/health uses. The chat doors live in lumnik-rag, which the open
// edition does not carry — so the presence of the `rag` module is the signal.

// Chat needs BOTH halves of the boundary: the doors live in lumnik-rag, the model provider in
// lumnik-llm-langchain. A build carrying one without the other shows the input and fails at the
// first question — which is what #268 left open, because langchain declared no manifest.
test('both halves present means chat is reachable', () => {
  assert.equal(hasChat({ modules: [{ id: 'core' }, { id: 'identity' }, { id: 'rag' }, { id: 'llm' }] }), true);
});

test('no rag module means chat is not installed on this build', () => {
  assert.equal(hasChat({ modules: [{ id: 'core' }, { id: 'identity' }] }), false);
});

test('the doors without a model provider is not a working chat', () => {
  assert.equal(hasChat({ modules: [{ id: 'core' }, { id: 'rag' }] }), false,
    'rag alone shows the input and fails at the first question');
});

test('a model provider without the doors is not a working chat either', () => {
  assert.equal(hasChat({ modules: [{ id: 'core' }, { id: 'llm' }] }), false);
});

// Absence has to be PROVEN, never assumed: telling a métier "chat is not installed" when the
// probe merely failed is a worse lie than the missing feature. Anything we cannot read means
// "carry on as before".
test('an unreadable answer never claims absence', () => {
  for (const unreadable of [null, undefined, {}, { modules: null }, { modules: 'rag' }]) {
    assert.equal(hasChat(unreadable), true, `should assume present for ${JSON.stringify(unreadable)}`);
  }
});
