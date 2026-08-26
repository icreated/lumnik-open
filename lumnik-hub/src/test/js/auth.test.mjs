import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createAuth } from '../../main/resources/META-INF/resources/app/src/auth.mjs';

const AUTH_MJS_PATH = fileURLToPath(
  new URL('../../main/resources/META-INF/resources/app/src/auth.mjs', import.meta.url),
);

function b64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return Buffer.from(bin, 'binary').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function jwt(payload) {
  return `header.${b64url(new TextEncoder().encode(JSON.stringify(payload)))}.sig`;
}

function fakeStorage(initial = {}) {
  const m = new Map(Object.entries(initial));
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, v),
    removeItem: (k) => m.delete(k),
    _map: m,
  };
}

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
  return { ok: true, status: 200, json: async () => body, text: async () => '' };
}

function refusedFetch(status = 400) {
  return fakeFetch({ ok: false, status, json: async () => ({}), text: async () => 'nope' });
}

const BASE = {
  kc: 'https://kc.example/realms/lumnik',
  clientId: 'lumnik-spa',
  redirect: 'https://app.example/app/',
  now: () => 1_000_000,
  onExpired: () => { throw new Error('onExpired should not fire here'); },
};

test('auth.mjs source does not contain the pure-JS sha256 (K256 table) — A2 retirement', () => {
  const src = readFileSync(AUTH_MJS_PATH, 'utf8');
  assert.ok(!src.includes('K256'), 'auth.mjs must rely on crypto.subtle only, not a ported pure-JS sha256');
});

test('loginUrl: stores the verifier and returns a KC auth URL with client_id/redirect/S256 challenge', async () => {
  const storage = fakeStorage();
  const verifier = 'fixed-test-verifier-0123456789';
  const expectedDigest = new Uint8Array(
    await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)),
  );
  const expectedChallenge = b64url(expectedDigest);

  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  const url = await auth.loginUrl(verifier);

  assert.equal(storage.getItem('pkce_verifier'), verifier);
  const q = new URL(url).searchParams;
  assert.equal(new URL(url).origin + new URL(url).pathname, `${BASE.kc}/protocol/openid-connect/auth`);
  assert.equal(q.get('client_id'), BASE.clientId);
  assert.equal(q.get('redirect_uri'), BASE.redirect);
  assert.equal(q.get('response_type'), 'code');
  assert.equal(q.get('scope'), 'openid');
  assert.equal(q.get('code_challenge_method'), 'S256');
  assert.equal(q.get('code_challenge'), expectedChallenge);
});

test('loginUrl: verifier is optional — defaults to a random one via crypto.getRandomValues', async () => {
  const storage = fakeStorage();
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  const url = await auth.loginUrl();
  assert.ok(storage.getItem('pkce_verifier'));
  assert.ok(new URL(url).searchParams.get('code_challenge'));
});

test('exchange: stores the 4 token keys and returns claims — navigation stays the caller\'s job', async () => {
  const storage = fakeStorage({ pkce_verifier: 'v1' });
  const tok = { access_token: jwt({ sub: 'u1' }), refresh_token: 'r1', id_token: 'idtok1', expires_in: 300 };
  const fetchFn = fakeFetch(okJson(tok));
  const auth = createAuth({ ...BASE, storage, fetchFn });

  const claims = await auth.exchange('the-code');

  assert.equal(storage.getItem('access_token'), tok.access_token);
  assert.equal(storage.getItem('refresh_token'), tok.refresh_token);
  assert.equal(storage.getItem('token_exp'), String(BASE.now() + tok.expires_in * 1000));
  assert.equal(storage.getItem('id_token'), tok.id_token);
  assert.deepEqual(claims, { sub: 'u1' });

  const [call] = fetchFn.calls;
  assert.equal(call.url, `${BASE.kc}/protocol/openid-connect/token`);
  assert.equal(call.opts.method, 'POST');
  const sentBody = new URLSearchParams(call.opts.body);
  assert.equal(sentBody.get('grant_type'), 'authorization_code');
  assert.equal(sentBody.get('code'), 'the-code');
  assert.equal(sentBody.get('code_verifier'), 'v1');
  assert.equal(sentBody.get('redirect_uri'), BASE.redirect);
});

test('exchange: a refused token POST throws and stores nothing', async () => {
  const storage = fakeStorage({ pkce_verifier: 'v1' });
  const auth = createAuth({ ...BASE, storage, fetchFn: refusedFetch(400) });
  await assert.rejects(() => auth.exchange('bad-code'));
  assert.equal(storage.getItem('access_token'), null);
});

test('renewalDelay: exp - now - 60s, no token → null', () => {
  const storage = fakeStorage();
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.renewalDelay(), null);
});

test('renewalDelay: normal window subtracts the 60s safety margin', () => {
  const storage = fakeStorage({ token_exp: String(BASE.now() + 120_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.renewalDelay(), 60_000);
});

test('renewalDelay: floors at 5s when the window is already tight', () => {
  const storage = fakeStorage({ token_exp: String(BASE.now() + 61_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.renewalDelay(), 5_000);
});

test('renew: success rotates access/refresh/exp/id tokens', async () => {
  const storage = fakeStorage({ refresh_token: 'r0' });
  const tok = { access_token: jwt({ sub: 'u2' }), refresh_token: 'r1', id_token: 'idtok2', expires_in: 600 };
  const fetchFn = fakeFetch(okJson(tok));
  const auth = createAuth({ ...BASE, storage, fetchFn, onExpired: () => { throw new Error('must not fire'); } });

  await auth.renew();

  assert.equal(storage.getItem('access_token'), tok.access_token);
  assert.equal(storage.getItem('refresh_token'), 'r1');
  assert.equal(storage.getItem('token_exp'), String(BASE.now() + 600_000));
  assert.equal(storage.getItem('id_token'), 'idtok2');
  const sentBody = new URLSearchParams(fetchFn.calls[0].opts.body);
  assert.equal(sentBody.get('grant_type'), 'refresh_token');
  assert.equal(sentBody.get('refresh_token'), 'r0');
});

test('renew: failure clears the session and calls onExpired — never throws outward', async () => {
  const storage = fakeStorage({
    access_token: 'a', refresh_token: 'r0', token_exp: '123', id_token: 'idt', pkce_verifier: 'v',
  });
  let expiredCalled = false;
  const auth = createAuth({
    ...BASE, storage, fetchFn: refusedFetch(401), onExpired: () => { expiredCalled = true; },
  });

  await assert.doesNotReject(() => auth.renew());

  assert.equal(expiredCalled, true);
  assert.equal(storage.getItem('access_token'), null);
  assert.equal(storage.getItem('refresh_token'), null);
  assert.equal(storage.getItem('token_exp'), null);
  assert.equal(storage.getItem('id_token'), null);
  assert.equal(storage.getItem('pkce_verifier'), null);
});

test('bootState: no stored session → none', () => {
  const storage = fakeStorage();
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.bootState(), 'none');
});

test('bootState: token_exp in the past → clears session (no API call) and returns expired', () => {
  const storage = fakeStorage({ access_token: 'a', token_exp: String(BASE.now() - 1) });
  const fetchFn = fakeFetch(okJson({}));
  const auth = createAuth({ ...BASE, storage, fetchFn });
  assert.equal(auth.bootState(), 'expired');
  assert.equal(fetchFn.calls.length, 0);
  assert.equal(storage.getItem('access_token'), null);
  assert.equal(storage.getItem('token_exp'), null);
});

test('bootState: token_exp in the future → live', () => {
  const storage = fakeStorage({ access_token: 'a', token_exp: String(BASE.now() + 60_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.bootState(), 'live');
});

test('logoutUrl: clears the session and includes id_token_hint when an id_token was stored', () => {
  const storage = fakeStorage({ access_token: 'a', refresh_token: 'r', token_exp: '1', id_token: 'idt', pkce_verifier: 'v' });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });

  const url = auth.logoutUrl();

  const q = new URL(url).searchParams;
  assert.equal(new URL(url).origin + new URL(url).pathname, `${BASE.kc}/protocol/openid-connect/logout`);
  assert.equal(q.get('client_id'), BASE.clientId);
  assert.equal(q.get('post_logout_redirect_uri'), BASE.redirect);
  assert.equal(q.get('id_token_hint'), 'idt');
  assert.equal(storage.getItem('access_token'), null);
  assert.equal(storage.getItem('id_token'), null);
});

test('logoutUrl: omits id_token_hint when no id_token was stored', () => {
  const storage = fakeStorage({ access_token: 'a', token_exp: '1' });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  const url = auth.logoutUrl();
  assert.equal(new URL(url).searchParams.has('id_token_hint'), false);
});

test('token(): returns the stored access token or null', () => {
  const storage = fakeStorage({ access_token: 'a1' });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.token(), 'a1');
  auth.clear();
  assert.equal(auth.token(), null);
});

test('claims(): parses the base64url JWT payload; null without a token or on malformed input', () => {
  const storage = fakeStorage({ access_token: jwt({ sub: 'u3', scopes: ['stock'] }) });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.deepEqual(auth.claims(), { sub: 'u3', scopes: ['stock'] });

  const empty = createAuth({ ...BASE, storage: fakeStorage(), fetchFn: fakeFetch(okJson({})) });
  assert.equal(empty.claims(), null);

  const malformed = createAuth({ ...BASE, storage: fakeStorage({ access_token: 'not-a-jwt' }), fetchFn: fakeFetch(okJson({})) });
  assert.equal(malformed.claims(), null);
});
