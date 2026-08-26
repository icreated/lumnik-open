// Session module for the real PWA at /app/ — ported from the spike (m/index.html, lines ~263–345):
// authorization code + PKCE against lumnik-spa, silent refresh, true (RP-initiated) logout.
// Effects (KC base URL, clientId, redirect, storage, fetch, clock, onExpired) are all
// injected so plain `node --test` can drive every branch with fakes — nothing here touches
// sessionStorage/fetch/Date/crypto.getRandomValues directly except crypto.subtle for S256 (A2:
// the spike's pure-JS SHA-256 fallback and its round-constant table are retired — crypto.subtle
// is available everywhere this PWA runs).

const KEYS = ['access_token', 'refresh_token', 'token_exp', 'id_token', 'pkce_verifier'];

function b64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function createAuth({ kc, clientId, redirect, storage, fetchFn, now, onExpired }) {
  function clear() {
    for (const key of KEYS) storage.removeItem(key);
  }

  function storeTokens(tok) {
    storage.setItem('access_token', tok.access_token);
    storage.setItem('refresh_token', tok.refresh_token);
    storage.setItem('token_exp', String(now() + tok.expires_in * 1000));
    if (tok.id_token) storage.setItem('id_token', tok.id_token); // logout's id_token_hint
  }

  function token() {
    return storage.getItem('access_token');
  }

  function claims() {
    const t = token();
    if (!t) return null;
    try {
      let b64 = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      b64 += '='.repeat((4 - b64.length % 4) % 4);  // forgiving-base64 makes padding optional in browsers; belt for non-spec envs
      return JSON.parse(atob(b64));
    }
    catch { return null; }
  }

  async function loginUrl(verifier = b64url(crypto.getRandomValues(new Uint8Array(32)))) {
    storage.setItem('pkce_verifier', verifier);
    const challenge = b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
    const q = new URLSearchParams({
      client_id: clientId, response_type: 'code', scope: 'openid',
      redirect_uri: redirect, code_challenge: challenge, code_challenge_method: 'S256',
    });
    return `${kc}/protocol/openid-connect/auth?${q}`;
  }

  async function exchange(code) {
    const body = new URLSearchParams({
      grant_type: 'authorization_code', client_id: clientId, code,
      redirect_uri: redirect, code_verifier: storage.getItem('pkce_verifier') || '',
    });
    const r = await fetchFn(`${kc}/protocol/openid-connect/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
    });
    if (!r.ok) throw new Error(`échange du code refusé (${r.status}): ${await r.text()}`);
    storeTokens(await r.json());
    // no history.replaceState here — stripping ?code from the URL is the component's job
    return claims();
  }

  function renewalDelay() {
    const exp = Number(storage.getItem('token_exp'));
    if (!exp) return null;
    return Math.max(5_000, exp - now() - 60_000);
  }

  async function renew() {
    const body = new URLSearchParams({
      grant_type: 'refresh_token', client_id: clientId,
      refresh_token: storage.getItem('refresh_token') || '',
    });
    try {
      const r = await fetchFn(`${kc}/protocol/openid-connect/token`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
      });
      if (!r.ok) throw new Error(`renouvellement refusé (${r.status})`);
      storeTokens(await r.json());
    } catch {
      clear();
      onExpired();
    }
  }

  function bootState() {
    const exp = Number(storage.getItem('token_exp'));
    if (!exp) return 'none';
    if (exp <= now()) { clear(); return 'expired'; }
    return 'live';
  }

  function logoutUrl() {
    const idToken = storage.getItem('id_token');
    clear();
    const q = new URLSearchParams({ client_id: clientId, post_logout_redirect_uri: redirect });
    if (idToken) q.set('id_token_hint', idToken);
    return `${kc}/protocol/openid-connect/logout?${q}`;
  }

  return { loginUrl, exchange, renewalDelay, renew, bootState, logoutUrl, clear, token, claims };
}
