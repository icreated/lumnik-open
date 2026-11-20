// Session module for the real PWA at /app/ — ported from the spike (m/index.html, lines ~263–345):
// authorization code + PKCE against lumnik-spa, silent refresh, true (RP-initiated) logout.
// Effects (KC base URL, clientId, redirect, storage, fetch, clock, onExpired) are all
// injected so plain `node --test` can drive every branch with fakes — nothing here touches
// sessionStorage/fetch/Date/crypto.getRandomValues directly except crypto.subtle for S256 (A2:
// the spike's pure-JS SHA-256 fallback and its round-constant table are retired — crypto.subtle
// is available everywhere this PWA runs).

import { NETWORK_DOWN } from './api.mjs';

// What a login keeps until Keycloak sends the user back: answered once, then forgotten (#2004).
const LOGIN_KEYS = ['pkce_verifier', 'oidc_state', 'oidc_nonce'];
const KEYS = ['access_token', 'refresh_token', 'token_exp', 'id_token', ...LOGIN_KEYS];

function random() {
  return b64url(crypto.getRandomValues(new Uint8Array(32)));
}

function b64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// #2036: when the renewal timer next fires, after a renewal's outcome. A renewed session renews a minute before
// it expires (never sooner than 5 s); Keycloak unreachable waits a minute rather than storming it from a phone
// that is offline; a lost session renews nothing.
export function nextRenewalDelay(outcome, { exp, now }) {
  if (exp == null) return null; // no session
  if (outcome === 'renewed') return Math.max(5_000, exp - now - RENEW_AHEAD_MS);
  if (outcome === 'unavailable') return BACKOFF_MS;
  return null;
}

const RENEW_AHEAD_MS = 60_000; // the timer renews this long before expiry
const EARLY_MS = 30_000;       // a call renews a token that expires within this
const MARGIN_MS = 5_000;       // a token with less left is not sent: it could die on its way to the hub
const BACKOFF_MS = 60_000;     // after Keycloak was unavailable, no new request for this long

export const IDP_DOWN = 'Le service de connexion ne répond pas — réessaie dans un instant.';

// The screen an unusable session leads to (#2036): Keycloak down while online is not "offline". null for an auth
// error: the login is already on screen.
export function unavailableScreen(e) {
  if (e && e.kind === 'auth') return null;
  return e && e.reason === 'idp' ? 'unreachable' : 'offline';
}

function payload(jwt) {
  if (!jwt) return null;
  try {
    let b64 = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    b64 += '='.repeat((4 - b64.length % 4) % 4);  // forgiving-base64 makes padding optional in browsers; belt for non-spec envs
    return JSON.parse(atob(b64));
  }
  catch { return null; }
}

export function createAuth({
  kc, clientId, redirect, storage, fetchFn, now, onExpired,
  online = () => true, renewTimeoutMs = 10_000,
}) {
  // A session's generation: clear() starts a new one, so a renewal answering after the session was declared lost
  // (onAuthLost, a logout) writes nothing back.
  let generation = 0;
  let inflight = null;
  let unavailableAt = -Infinity;

  function clear() {
    generation += 1;
    for (const key of KEYS) storage.removeItem(key);
  }

  function storeTokens(tok) {
    const before = KEYS.map(k => [k, storage.getItem(k)]);
    try {
      storage.setItem('access_token', tok.access_token);
      storage.setItem('refresh_token', tok.refresh_token);
      storage.setItem('token_exp', String(now() + tok.expires_in * 1000));
      if (tok.id_token) storage.setItem('id_token', tok.id_token); // logout's id_token_hint
    } catch (e) {
      // put the session back as it was: never a new access token under the old refresh token and expiry
      for (const [k, v] of before) { try { if (v == null) storage.removeItem(k); else storage.setItem(k, v); } catch { /* as far as it goes */ } }
      throw e;
    }
  }

  function token() {
    return storage.getItem('access_token');
  }

  function claims() {
    return payload(token());
  }

  // state ties Keycloak's answer to the login this tab started; nonce ties the id_token to it (#2004).
  async function loginUrl(verifier = random(), state = random(), nonce = random()) {
    storage.setItem('pkce_verifier', verifier);
    storage.setItem('oidc_state', state);
    storage.setItem('oidc_nonce', nonce);
    const challenge = b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
    const q = new URLSearchParams({
      client_id: clientId, response_type: 'code', scope: 'openid', state, nonce,
      redirect_uri: redirect, code_challenge: challenge, code_challenge_method: 'S256',
    });
    return `${kc}/protocol/openid-connect/auth?${q}`;
  }

  async function exchange(code, state) {
    const verifier = storage.getItem('pkce_verifier');
    const expected = storage.getItem('oidc_state');
    const nonce = storage.getItem('oidc_nonce');
    for (const key of LOGIN_KEYS) storage.removeItem(key);
    if (!expected || state !== expected) throw new Error('cette connexion n’a pas été demandée par cette page — reconnecte-toi');
    const body = new URLSearchParams({
      grant_type: 'authorization_code', client_id: clientId, code,
      redirect_uri: redirect, code_verifier: verifier || '',
    });
    const r = await fetchFn(`${kc}/protocol/openid-connect/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
    });
    if (!r.ok) throw new Error(`échange du code refusé (${r.status}): ${await r.text()}`);
    const tok = await r.json();
    if (payload(tok.id_token)?.nonce !== nonce) throw new Error('la réponse de connexion ne correspond pas à cette page — reconnecte-toi');
    storeTokens(tok);
    // no history.replaceState here — stripping ?code from the URL is the component's job
    return claims();
  }

  function expiry() {
    return Number(storage.getItem('token_exp')) || null;
  }

  // A token response as Keycloak writes it; anything else (a portal's JSON, a proxy's page) is not one.
  function isTokens(t) {
    return t && typeof t.access_token === 'string' && typeof t.refresh_token === 'string' && Number(t.expires_in) > 0;
  }

  // One refresh-token request. 'renewed'; 'lost' only when Keycloak refuses the session (400 invalid_grant), or
  // when the session was declared lost (clear()) while the request was out; 'unavailable' for anything else (no
  // answer in time, the network, a status of a proxy or of a Keycloak in trouble, a body that is not tokens, a
  // storage that cannot write): the session is kept for a later try.
  async function requestRenewal() {
    const mine = generation;
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), renewTimeoutMs);
    let outcome;
    try {
      const body = new URLSearchParams({
        grant_type: 'refresh_token', client_id: clientId,
        refresh_token: storage.getItem('refresh_token') || '',
      });
      const r = await fetchFn(`${kc}/protocol/openid-connect/token`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body, signal: abort.signal,
      });
      const answer = await r.json().catch(() => null);
      if (mine !== generation) return 'lost';
      if (r.status === 400 && answer && answer.error === 'invalid_grant') {
        clear();
        onExpired();
        return 'lost';
      }
      outcome = r.ok && isTokens(answer) ? (storeTokens(answer), 'renewed') : 'unavailable';
    } catch {
      outcome = 'unavailable';
    } finally {
      clearTimeout(timer);
    }
    if (mine !== generation) return 'lost';
    // Back off only from a Keycloak that did not answer while the device was online: a phone waking before its
    // network is back tries again with its next call.
    if (outcome === 'unavailable' && online()) unavailableAt = now();
    return outcome;
  }

  // The timer and every call share one renewal at a time.
  function renew() {
    if (!inflight) inflight = requestRenewal().finally(() => { inflight = null; });
    return inflight;
  }

  // The timer's renewal: only when it is due, so a token a call renewed meanwhile is not renewed twice.
  function renewIfDue() {
    const exp = expiry();
    if (exp == null) return Promise.resolve('lost'); // no session: nothing to send
    if (exp - now() > RENEW_AHEAD_MS) return Promise.resolve('renewed');
    return renew();
  }

  // The token a call sends (#2036). One that expires within 30 s is renewed: a still-valid one goes out at once
  // while the renewal runs behind it; an expired one waits for it, so a phone that slept past its token's life
  // does not meet a 401. Within a minute of Keycloak being unavailable, no new request. Throws the api's error
  // shape when no usable token is left: `reason` says whether the device is offline ('network') or Keycloak is
  // ('idp').
  async function usableToken() {
    const exp = expiry();
    if (exp == null) return null;
    if (exp - now() > EARLY_MS) return token();
    const backingOff = now() - unavailableAt < BACKOFF_MS;
    if (exp - now() > MARGIN_MS) {
      if (!backingOff) renew();
      return token();
    }
    const outcome = backingOff ? 'unavailable' : await renew();
    if (outcome === 'renewed') return token();
    if (outcome === 'lost') throw { kind: 'auth', message: 'session expirée' };
    throw online()
      ? { kind: 'error', reason: 'idp', message: IDP_DOWN }
      : { kind: 'error', reason: 'network', message: NETWORK_DOWN };
  }

  // Never hands out nothing ("Bearer null" from a session stored half), never a raw storage error: no usable session
  // ends like a refused one, the login on screen (onExpired), in the api's error shape.
  async function fresh() {
    let t;
    try {
      t = await usableToken();
    } catch (e) {
      if (e && e.kind) throw e; // its own outcome: a lost session has already shown the login
    }
    if (t) return t;
    try { clear(); } catch { /* a storage that cannot be written: the generation moved on all the same */ }
    onExpired();
    throw { kind: 'auth', message: 'session expirée' };
  }

  // 'none', 'live', 'renewable' (expired, with a refresh token to try), or 'expired' (cleared).
  function bootState() {
    const exp = expiry();
    if (exp == null) return 'none';
    if (exp > now()) return 'live';
    if (storage.getItem('refresh_token')) return 'renewable';
    clear();
    return 'expired';
  }

  function logoutUrl() {
    const idToken = storage.getItem('id_token');
    clear();
    const q = new URLSearchParams({ client_id: clientId, post_logout_redirect_uri: redirect });
    if (idToken) q.set('id_token_hint', idToken);
    return `${kc}/protocol/openid-connect/logout?${q}`;
  }

  return { loginUrl, exchange, renew, renewIfDue, fresh, expiry, bootState, logoutUrl, clear, token, claims };
}
