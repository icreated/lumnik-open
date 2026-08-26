import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApi } from '../../main/resources/META-INF/resources/app/src/api.mjs';

function fakeFetch(response) {
  const calls = [];
  const fn = async (url, opts) => {
    calls.push({ url, opts });
    return response;
  };
  fn.calls = calls;
  return fn;
}

function okJson(body) {
  return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
}

function okText(body) {
  return { ok: true, status: 200, json: async () => { throw new Error('not json'); }, text: async () => body };
}

function refused(status, body) {
  return {
    ok: false, status,
    json: async () => { if (body === undefined) throw new Error('no body'); return body; },
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
  };
}

function noAuthLost() {
  return () => { throw new Error('onAuthLost should not fire here'); };
}

test('json(): happy POST with a json body sends Bearer, Accept json, Content-Type json, stringified body', async () => {
  const fetchFn = fakeFetch(okJson({ ok: true }));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  const result = await api.json('/api/views/render', { json: { a: 1 } });

  assert.deepEqual(result, { ok: true });
  const [call] = fetchFn.calls;
  assert.equal(call.url, '/api/views/render');
  assert.equal(call.opts.method, 'POST');
  assert.equal(call.opts.headers['Authorization'], 'Bearer tok1');
  assert.equal(call.opts.headers['Accept'], 'application/json');
  assert.equal(call.opts.headers['Content-Type'], 'application/json');
  assert.equal(call.opts.body, JSON.stringify({ a: 1 }));
});

test('json(): a yaml body sends Content-Type application/yaml with the raw string, unstringified', async () => {
  const fetchFn = fakeFetch(okJson({ ok: true }));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await api.json('/api/views/render', { yaml: 'key: value\n' });

  const [call] = fetchFn.calls;
  assert.equal(call.opts.headers['Content-Type'], 'application/yaml');
  assert.equal(call.opts.body, 'key: value\n');
});

test('json(): method GET with no body sends no body and no Content-Type', async () => {
  const fetchFn = fakeFetch(okJson({ items: [] }));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await api.json('/api/views/list', { method: 'GET' });

  const [call] = fetchFn.calls;
  assert.equal(call.opts.method, 'GET');
  assert.equal(call.opts.body, undefined);
  assert.equal(call.opts.headers['Content-Type'], undefined);
});

test('json(): 401 calls onAuthLost once and throws a plain object with kind auth', async () => {
  let calls = 0;
  const fetchFn = fakeFetch(refused(401));
  const api = createApi({ token: () => 'tok1', onAuthLost: () => { calls += 1; }, fetchFn });

  await assert.rejects(
    () => api.json('/api/views/render', { json: {} }),
    (err) => { assert.deepEqual(err, { kind: 'auth' }); return true; },
  );
  assert.equal(calls, 1);
});

test('json(): 403 with {"error":"scope not granted"} throws kind scope with the verbatim message', async () => {
  const fetchFn = fakeFetch(refused(403, { error: 'scope not granted' }));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await assert.rejects(
    () => api.json('/api/views/render', { json: {} }),
    (err) => { assert.deepEqual(err, { kind: 'scope', message: 'scope not granted' }); return true; },
  );
});

test('json(): 500 with no/invalid body throws kind error with a fallback message', async () => {
  const fetchFn = fakeFetch(refused(500));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await assert.rejects(
    () => api.json('/api/views/render', { json: {} }),
    (err) => { assert.deepEqual(err, { kind: 'error', message: 'erreur 500' }); return true; },
  );
});

test('text(): sends the given Accept header and Bearer, returns the raw response text', async () => {
  const fetchFn = fakeFetch(okText('title: Stock\nfields: []\n'));
  const api = createApi({ token: () => 'tok2', onAuthLost: noAuthLost(), fetchFn });

  const raw = await api.text('/api/views/stock.yaml', 'application/yaml');

  assert.equal(raw, 'title: Stock\nfields: []\n');
  const [call] = fetchFn.calls;
  assert.equal(call.url, '/api/views/stock.yaml');
  assert.equal(call.opts.headers['Accept'], 'application/yaml');
  assert.equal(call.opts.headers['Authorization'], 'Bearer tok2');
});

test('text(): 403 with a scope-not-granted body maps to kind scope, same as json()', async () => {
  const fetchFn = fakeFetch(refused(403, { error: 'scope not granted' }));
  const api = createApi({ token: () => 'tok2', onAuthLost: noAuthLost(), fetchFn });

  await assert.rejects(
    () => api.text('/api/views/stock.yaml', 'application/yaml'),
    (err) => { assert.deepEqual(err, { kind: 'scope', message: 'scope not granted' }); return true; },
  );
});

test('text(): 401 calls onAuthLost once and throws kind auth', async () => {
  let calls = 0;
  const fetchFn = fakeFetch(refused(401));
  const api = createApi({ token: () => 'tok2', onAuthLost: () => { calls += 1; }, fetchFn });

  await assert.rejects(
    () => api.text('/api/views/stock.yaml', 'application/yaml'),
    (err) => { assert.deepEqual(err, { kind: 'auth' }); return true; },
  );
  assert.equal(calls, 1);
});

test('a network failure (fetch throws) maps to a French message, not browser English', async () => {
  const api = createApi({ token: () => 't', onAuthLost: () => {}, fetchFn: () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(() => api.json('/api/views', { method: 'GET' }),
    err => err.kind === 'error' && err.message === 'réseau indisponible — vérifie ta connexion');
});
