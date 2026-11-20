import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createAuth, nextRenewalDelay, unavailableScreen, IDP_DOWN } from '../../main/resources/META-INF/resources/app/src/auth.mjs';

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
  const storage = fakeStorage({ pkce_verifier: 'v1', oidc_state: 's1', oidc_nonce: 'n1' });
  const tok = { access_token: jwt({ sub: 'u1' }), refresh_token: 'r1', id_token: jwt({ nonce: 'n1' }), expires_in: 300 };
  const fetchFn = fakeFetch(okJson(tok));
  const auth = createAuth({ ...BASE, storage, fetchFn });

  const claims = await auth.exchange('the-code', 's1');

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
  const storage = fakeStorage({ pkce_verifier: 'v1', oidc_state: 's1', oidc_nonce: 'n1' });
  const auth = createAuth({ ...BASE, storage, fetchFn: refusedFetch(400) });
  await assert.rejects(() => auth.exchange('bad-code', 's1'));
  assert.equal(storage.getItem('access_token'), null);
});

test('loginUrl: sends a fresh state and nonce and keeps them for the return (#2004)', async () => {
  const storage = fakeStorage();
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  const q = new URL(await auth.loginUrl()).searchParams;
  assert.ok(q.get('state') && q.get('nonce'));
  assert.notEqual(q.get('state'), q.get('nonce'));
  assert.equal(storage.getItem('oidc_state'), q.get('state'));
  assert.equal(storage.getItem('oidc_nonce'), q.get('nonce'));
});

test('exchange: a code this tab did not ask for is refused before any call to Keycloak (#2004)', async () => {
  for (const [stored, returned] of [[{}, 's1'], [{ oidc_state: 's1' }, 'other'], [{ oidc_state: 's1' }, null]]) {
    const storage = fakeStorage({ pkce_verifier: 'v1', oidc_nonce: 'n1', ...stored });
    const fetchFn = fakeFetch(okJson({}));
    const auth = createAuth({ ...BASE, storage, fetchFn });
    await assert.rejects(() => auth.exchange('the-code', returned));
    assert.equal(fetchFn.calls.length, 0);
    assert.equal(storage.getItem('oidc_state'), null, 'a login is answered once');
  }
});

test('exchange: an id_token minted for another login is refused and nothing is stored (#2004)', async () => {
  for (const idToken of [jwt({ nonce: 'other' }), jwt({}), undefined]) {
    const storage = fakeStorage({ pkce_verifier: 'v1', oidc_state: 's1', oidc_nonce: 'n1' });
    const tok = { access_token: jwt({ sub: 'u1' }), refresh_token: 'r1', id_token: idToken, expires_in: 300 };
    const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson(tok)) });
    await assert.rejects(() => auth.exchange('the-code', 's1'));
    assert.equal(storage.getItem('access_token'), null);
  }
});

test('exchange: a login is answered once — state, nonce and verifier are gone after it (#2004)', async () => {
  const storage = fakeStorage({ pkce_verifier: 'v1', oidc_state: 's1', oidc_nonce: 'n1' });
  const tok = { access_token: jwt({ sub: 'u1' }), refresh_token: 'r1', id_token: jwt({ nonce: 'n1' }), expires_in: 300 };
  await createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson(tok)) }).exchange('the-code', 's1');
  for (const k of ['pkce_verifier', 'oidc_state', 'oidc_nonce']) assert.equal(storage.getItem(k), null, k);
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

test('renew: Keycloak refusing the session clears it and calls onExpired — never throws outward', async () => {
  const storage = fakeStorage({
    access_token: 'a', refresh_token: 'r0', token_exp: '123', id_token: 'idt', pkce_verifier: 'v',
  });
  let expiredCalled = false;
  const auth = createAuth({
    ...BASE, storage, fetchFn: fakeFetch({ ok: false, status: 400, json: async () => ({ error: 'invalid_grant' }), text: async () => '' }),
    onExpired: () => { expiredCalled = true; },
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

test('bootState: token_exp in the past with no refresh token → clears session (no API call) and returns expired', () => {
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

// ── #2036: renew before a call, one renewal at a time, a lost session told from a network problem ──────────────

function sequenceFetch(...responses) {
  const calls = [];
  const fn = async (url, opts) => {
    calls.push({ url, opts });
    const r = responses[Math.min(calls.length - 1, responses.length - 1)];
    if (r instanceof Error) throw r;
    return r;
  };
  fn.calls = calls;
  return fn;
}

const TOK = { access_token: jwt({ sub: 'u9' }), refresh_token: 'r9', expires_in: 300 };
const LIVE = () => fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() + 600_000) });
const notJson = { ok: true, status: 200, json: async () => { throw new SyntaxError('<html>'); }, text: async () => '<html>' };
const status = (s, body = {}) => ({ ok: s < 300, status: s, json: async () => body, text: async () => JSON.stringify(body) });

test('renew: answers renewed, and stores the new tokens', async () => {
  const storage = LIVE();
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.renew(), 'renewed');
  assert.equal(storage.getItem('refresh_token'), 'r9');
});

test('renew: Keycloak refusing the refresh token (400 invalid_grant) ends the session', async () => {
  const storage = LIVE();
  let expired = 0;
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(status(400, { error: 'invalid_grant' })),
    onExpired: () => { expired += 1; } });
  assert.equal(await auth.renew(), 'lost');
  assert.equal(expired, 1);
  assert.equal(storage.getItem('refresh_token'), null);
});

for (const [label, response] of [
  ['a network failure', new TypeError('Failed to fetch')],
  ['a 503', status(503)],
  ['a 502', status(502)],
  ['a 408', status(408)],
  ['a 429', status(429)],
  ['a 200 whose body is not JSON (a captive portal)', notJson],
]) {
  test(`renew: ${label} keeps the session for a later try`, async () => {
    const storage = LIVE();
    const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(response) });
    assert.equal(await auth.renew(), 'unavailable');
    assert.equal(storage.getItem('refresh_token'), 'r0');
    assert.equal(storage.getItem('access_token'), 'a0');
  });
}

test('renew: the timer and every call share one renewal at a time', async () => {
  let release;
  const gate = new Promise(r => { release = r; });
  const calls = [];
  const fetchFn = async (url, opts) => { calls.push(opts); await gate; return okJson(TOK); };
  const auth = createAuth({ ...BASE, storage: LIVE(), fetchFn });
  const both = Promise.all([auth.renew(), auth.renew()]);
  release();
  assert.deepEqual(await both, ['renewed', 'renewed']);
  assert.equal(calls.length, 1);
  assert.equal(await auth.renew(), 'renewed', 'a later renewal is a new request');
  assert.equal(calls.length, 2);
});

test('renew: a request that never answers gives up after its timeout, and keeps the session', async () => {
  const storage = LIVE();
  const fetchFn = (url, opts) => new Promise((_, reject) => {
    opts.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  });
  const auth = createAuth({ ...BASE, storage, fetchFn, renewTimeoutMs: 20 });
  assert.equal(await auth.renew(), 'unavailable');
  assert.equal(storage.getItem('refresh_token'), 'r0');
});

test('renew: a session declared lost while a renewal is in flight stays lost', async () => {
  const storage = LIVE();
  let release;
  const gate = new Promise(r => { release = r; });
  const fetchFn = async () => { await gate; return okJson(TOK); };
  const auth = createAuth({ ...BASE, storage, fetchFn });
  const pending = auth.renew();
  auth.clear(); // onAuthLost, a logout
  release();
  assert.equal(await pending, 'lost');
  assert.equal(storage.getItem('access_token'), null, 'the late answer writes nothing back');
});

test('fresh: a token valid for more than 30 s is handed out without a request', async () => {
  const fetchFn = sequenceFetch(okJson(TOK));
  const auth = createAuth({ ...BASE, storage: LIVE(), fetchFn });
  assert.equal(await auth.fresh(), 'a0');
  assert.equal(fetchFn.calls.length, 0);
});

test('fresh: a token that expires within 30 s is renewed, the next call sends the new one', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() + 20_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.fresh(), 'a0', 'still valid: it goes out at once');
  await auth.renew(); // the renewal it started
  assert.equal(await auth.fresh(), TOK.access_token);
});

test('fresh: an expired token with Keycloak unreachable is an honest error, the session kept', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  const online = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(status(503)), online: () => true });
  await assert.rejects(online.fresh(), e => e.kind === 'error' && /connexion/.test(e.message) && !/réseau/.test(e.message));
  const offline = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(new TypeError('x')), online: () => false });
  await assert.rejects(offline.fresh(), e => e.kind === 'error' && /réseau/.test(e.message));
  assert.equal(storage.getItem('refresh_token'), 'r0');
});

test('fresh: a still-valid token is handed out when the early renewal cannot reach Keycloak', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() + 20_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(status(503)) });
  assert.equal(await auth.fresh(), 'a0');
});

test('fresh: a lost session is an auth error', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(status(400, { error: 'invalid_grant' })),
    onExpired: () => {} });
  await assert.rejects(auth.fresh(), e => e.kind === 'auth');
});

test('bootState: an expired token with a refresh token is renewable, nothing cleared', () => {
  const storage = fakeStorage({ access_token: 'a', refresh_token: 'r', token_exp: String(BASE.now() - 1) });
  const auth = createAuth({ ...BASE, storage, fetchFn: fakeFetch(okJson({})) });
  assert.equal(auth.bootState(), 'renewable');
  assert.equal(storage.getItem('refresh_token'), 'r');
});

test('nextRenewalDelay: renewed → expiry minus 60 s (5 s floor); unavailable → a minute; lost → none', () => {
  const now = 1_000_000;
  assert.equal(nextRenewalDelay('renewed', { exp: now + 300_000, now }), 240_000);
  assert.equal(nextRenewalDelay('renewed', { exp: now + 30_000, now }), 5_000);
  assert.equal(nextRenewalDelay('unavailable', { exp: now + 300_000, now }), 60_000);
  assert.equal(nextRenewalDelay('lost', { exp: now + 300_000, now }), null);
});

// ── #2036, review round: what the first version let through ─────────────────────────────────────────────────────

for (const [label, response] of [
  ['a 403 from a proxy', status(403)],
  ['a 404', status(404)],
  ['a 405', status(405)],
  ['a 401', status(401)],
  ['a 400 that is not invalid_grant', status(400, { error: 'invalid_request' })],
  ['a 200 JSON body without tokens', status(200, { error: 'portal' })],
]) {
  test(`renew: ${label} is not Keycloak refusing the session: it keeps it`, async () => {
    const storage = LIVE();
    const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(response) });
    assert.equal(await auth.renew(), 'unavailable');
    assert.equal(storage.getItem('refresh_token'), 'r0');
  });
}

test('renew: a storage that cannot write keeps the outcome unavailable, never a rejection', async () => {
  const storage = LIVE();
  storage.setItem = () => { throw new DOMException('quota', 'QuotaExceededError'); };
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.renew(), 'unavailable');
});

test('renew and fresh: a session declared lost during a renewal that then fails stays lost; nothing is handed out', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  let release;
  const gate = new Promise(r => { release = r; });
  const fetchFn = async () => { await gate; return status(503); };
  const auth = createAuth({ ...BASE, storage, fetchFn, now: () => BASE.now() });
  const call = auth.fresh();
  auth.clear(); // a neighbour call met a 401: onAuthLost
  release();
  await assert.rejects(call, e => e.kind === 'auth');
});

test('fresh: a token still valid goes out at once while the renewal runs behind it', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() + 20_000) });
  const calls = [];
  const fetchFn = (url, opts) => { calls.push(opts); return new Promise(() => {}); }; // Keycloak hangs
  const auth = createAuth({ ...BASE, storage, fetchFn, renewTimeoutMs: 20 });
  assert.equal(await auth.fresh(), 'a0');
  assert.equal(calls.length, 1, 'the renewal started');
});

test('fresh: after Keycloak was unavailable, the next minute starts no request: a valid token goes out, an expired one fails at once', async () => {
  let t = BASE.now();
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(t + 20_000) });
  const fetchFn = sequenceFetch(status(503));
  const auth = createAuth({ ...BASE, storage, fetchFn, now: () => t, online: () => true });
  assert.equal(await auth.renew(), 'unavailable');
  assert.equal(await auth.fresh(), 'a0');
  t += 25_000; // expired, still inside the minute
  await assert.rejects(auth.fresh(), e => e.kind === 'error' && e.reason === 'idp');
  assert.equal(fetchFn.calls.length, 1);
  t += 60_000; // the minute is over: try again
  await assert.rejects(auth.fresh(), e => e.reason === 'idp');
  assert.equal(fetchFn.calls.length, 2);
});

test('fresh: the error says whether the device is offline or Keycloak is', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  const off = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(new TypeError('x')), online: () => false });
  await assert.rejects(off.fresh(), e => e.reason === 'network');
});

test('renewIfDue: a token renewed meanwhile (by a call) is not renewed again', async () => {
  const fetchFn = sequenceFetch(okJson(TOK));
  const auth = createAuth({ ...BASE, storage: LIVE(), fetchFn }); // expires in 10 min
  assert.equal(await auth.renewIfDue(), 'renewed');
  assert.equal(fetchFn.calls.length, 0);
  const due = createAuth({ ...BASE, storage: fakeStorage({ access_token: 'a', refresh_token: 'r', token_exp: String(BASE.now() + 50_000) }), fetchFn });
  assert.equal(await due.renewIfDue(), 'renewed');
  assert.equal(fetchFn.calls.length, 1);
});

test('nextRenewalDelay: no session, no timer', () => {
  assert.equal(nextRenewalDelay('renewed', { exp: null, now: 1 }), null);
  assert.equal(nextRenewalDelay('unavailable', { exp: null, now: 1 }), null);
});

// ── #2036, review round 2 ──────────────────────────────────────────────────────────────────────────────────────

test('fresh: a renewal that failed while the device was offline arms no backoff: once online, the next call tries', async () => {
  let t = BASE.now(), up = false;
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(t - 1) }); // woke past expiry
  const fetchFn = sequenceFetch(new TypeError('offline'), okJson(TOK));
  const auth = createAuth({ ...BASE, storage, fetchFn, now: () => t, online: () => up });
  assert.equal(await auth.renew(), 'unavailable'); // the overdue timer, before the network is back
  up = true; t += 2_000;
  assert.equal(await auth.fresh(), TOK.access_token);
  assert.equal(fetchFn.calls.length, 2);
});

test('fresh: a token with 5 s or less left is not sent: the call waits for its renewal', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() + 4_000) });
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.fresh(), TOK.access_token);
});

test('renew: a storage that fails halfway through leaves the session as it was', async () => {
  const storage = LIVE();
  const real = storage.setItem;
  storage.setItem = (k, v) => { if (k === 'token_exp') throw new DOMException('quota', 'QuotaExceededError'); real(k, v); };
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.renew(), 'unavailable');
  assert.equal(storage.getItem('access_token'), 'a0');
  assert.equal(storage.getItem('refresh_token'), 'r0');
});

test('renew: never rejects, even when reading the session throws', async () => {
  const storage = LIVE();
  storage.getItem = () => { throw new DOMException('blocked', 'SecurityError'); };
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)) });
  assert.equal(await auth.renew(), 'unavailable');
});

test('renewIfDue: no session sends nothing', async () => {
  const fetchFn = sequenceFetch(okJson(TOK));
  const auth = createAuth({ ...BASE, storage: fakeStorage(), fetchFn });
  assert.equal(await auth.renewIfDue(), 'lost');
  assert.equal(fetchFn.calls.length, 0);
});

test('renewIfDue and fresh share one renewal', async () => {
  let release;
  const gate = new Promise(r => { release = r; });
  const calls = [];
  const fetchFn = async (url, opts) => { calls.push(opts); await gate; return okJson(TOK); };
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  const auth = createAuth({ ...BASE, storage, fetchFn });
  const both = Promise.all([auth.renewIfDue(), auth.fresh()]);
  release();
  assert.deepEqual(await both, ['renewed', TOK.access_token]);
  assert.equal(calls.length, 1);
});

test('fresh: Keycloak unreachable says it in the words of the screen', async () => {
  const storage = fakeStorage({ access_token: 'a0', refresh_token: 'r0', token_exp: String(BASE.now() - 1) });
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(status(503)), online: () => true });
  await assert.rejects(auth.fresh(), e => e.message === IDP_DOWN);
});

test('unavailableScreen: the screen an unusable session leads to', () => {
  assert.equal(unavailableScreen({ kind: 'error', reason: 'idp' }), 'unreachable');
  assert.equal(unavailableScreen({ kind: 'error', reason: 'network' }), 'offline');
  assert.equal(unavailableScreen({ kind: 'error', message: 'erreur 500' }), 'offline'); // the hub, not the session
  assert.equal(unavailableScreen({ kind: 'auth' }), null); // onAuthLost or onExpired has shown the login
});

// ── #2036, PR review: fresh() never hands out nothing, never leaks a raw storage error ──────────────────────────

test('fresh: an expiry stored without its token is no session: an auth error, never "Bearer null"', async () => {
  const storage = fakeStorage({ refresh_token: 'r0', token_exp: String(BASE.now() + 600_000) });
  let expired = 0;
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)), onExpired: () => { expired += 1; } });
  await assert.rejects(auth.fresh(), e => e.kind === 'auth');
  assert.equal(expired, 1, 'the login is on screen');
});

test('fresh: a storage that cannot be read gives the api error shape, not a DOMException', async () => {
  const storage = LIVE();
  storage.getItem = () => { throw new DOMException('blocked', 'SecurityError'); };
  let expired = 0;
  const auth = createAuth({ ...BASE, storage, fetchFn: sequenceFetch(okJson(TOK)), onExpired: () => { expired += 1; } });
  await assert.rejects(auth.fresh(), e => e.kind === 'auth' && typeof e.message === 'string');
  assert.equal(expired, 1, 'the login is on screen');
});
