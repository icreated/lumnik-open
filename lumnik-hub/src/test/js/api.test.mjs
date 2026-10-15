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
    (err) => { assert.equal(err.kind, 'auth'); assert.equal(err.status, 401); return true; },
  );
  assert.equal(calls, 1);
});

test('json(): 403 with {"error":"scope not granted"} throws kind scope with the verbatim message', async () => {
  const fetchFn = fakeFetch(refused(403, { error: 'scope not granted' }));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await assert.rejects(
    () => api.json('/api/views/render', { json: {} }),
    (err) => { assert.equal(err.kind, 'scope'); assert.equal(err.message, 'scope not granted'); assert.equal(err.status, 403); return true; },
  );
});

test('json(): 500 with no/invalid body throws kind error with a fallback message', async () => {
  const fetchFn = fakeFetch(refused(500));
  const api = createApi({ token: () => 'tok1', onAuthLost: noAuthLost(), fetchFn });

  await assert.rejects(
    () => api.json('/api/views/render', { json: {} }),
    (err) => { assert.equal(err.kind, 'error'); assert.equal(err.message, 'erreur 500'); assert.equal(err.status, 500); return true; },
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
    (err) => { assert.equal(err.kind, 'scope'); assert.equal(err.message, 'scope not granted'); assert.equal(err.status, 403); return true; },
  );
});

test('text(): 401 calls onAuthLost once and throws kind auth', async () => {
  let calls = 0;
  const fetchFn = fakeFetch(refused(401));
  const api = createApi({ token: () => 'tok2', onAuthLost: () => { calls += 1; }, fetchFn });

  await assert.rejects(
    () => api.text('/api/views/stock.yaml', 'application/yaml'),
    (err) => { assert.equal(err.kind, 'auth'); assert.equal(err.status, 401); return true; },
  );
  assert.equal(calls, 1);
});

test('a network failure (fetch throws) maps to a French message, not browser English', async () => {
  const api = createApi({ token: () => 't', onAuthLost: () => {}, fetchFn: () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(() => api.json('/api/views', { method: 'GET' }),
    err => err.kind === 'error' && err.message === 'réseau indisponible — vérifie ta connexion');
});

// Search needs more than a kind: the status (to pick the sentence), the reason a 503 carries in its
// body, and the Retry-After the hub sent. They ride on every non-2xx, the 401 included.
function refusedWithHeaders(status, body, headers) {
  return { ...refused(status, body), headers: { get: (k) => (headers[k] ?? headers[k.toLowerCase()] ?? null) } };
}

test('a refusal also carries status, retryAfter (seconds) and the parsed body', async () => {
  const fetchFn = fakeFetch(refusedWithHeaders(503, { error: 'search is degraded', reason: 'backend_down' }, { 'Retry-After': '60' }));
  const api = createApi({ token: () => 't', onAuthLost: noAuthLost(), fetchFn });
  await assert.rejects(() => api.json('/api/search?q=x', { method: 'GET' }), (e) => {
    assert.equal(e.kind, 'error');
    assert.equal(e.message, 'search is degraded');
    assert.equal(e.status, 503);
    assert.equal(e.retryAfter, 60);
    assert.deepEqual(e.body, { error: 'search is degraded', reason: 'backend_down' });
    return true;
  });
});

test('without a Retry-After header, or a headers object, retryAfter is null', async () => {
  for (const response of [refusedWithHeaders(429, { error: 'slow down' }, {}), refused(429, { error: 'slow down' })]) {
    const api = createApi({ token: () => 't', onAuthLost: noAuthLost(), fetchFn: fakeFetch(response) });
    await assert.rejects(() => api.json('/x', { method: 'GET' }), (e) => e.status === 429 && e.retryAfter === null);
  }
});

test('a Retry-After that is not a number of seconds is null, never NaN', async () => {
  const api = createApi({ token: () => 't', onAuthLost: noAuthLost(),
    fetchFn: fakeFetch(refusedWithHeaders(503, {}, { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' })) });
  await assert.rejects(() => api.json('/x', { method: 'GET' }), (e) => e.retryAfter === null);
});

test('the 401 still signals auth loss and now carries its status too', async () => {
  let lost = 0;
  const api = createApi({ token: () => 't', onAuthLost: () => { lost += 1; }, fetchFn: fakeFetch(refused(401, undefined)) });
  await assert.rejects(() => api.json('/x', { method: 'GET' }), (e) => e.kind === 'auth' && e.status === 401);
  assert.equal(lost, 1);
});

test('a scope refusal keeps its kind and gains the status', async () => {
  const api = createApi({ token: () => 't', onAuthLost: noAuthLost(), fetchFn: fakeFetch(refused(403, { error: 'scope not granted' })) });
  await assert.rejects(() => api.json('/x', { method: 'GET' }), (e) => e.kind === 'scope' && e.status === 403);
});
