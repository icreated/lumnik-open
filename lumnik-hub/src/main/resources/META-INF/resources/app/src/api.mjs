// API module for the real PWA at /app/ — ported from the spike's call() (m/index.html, ~L348)
// and discoverView()'s raw-yaml fetch (~L388). Uniform, honest error mapping (B8, D4):
// every non-2xx response — including 401 — becomes a plain thrown object with a `kind`,
// never a bare Error, so callers can branch on it without string-matching a message.
// token/onAuthLost/fetchFn are all injected so plain `node --test` can drive every branch
// with fakes — nothing here touches sessionStorage or fetch directly.

export function createApi({ token, onAuthLost, fetchFn }) {
  async function handleResponse(r) {
    if (r.status === 401) {
      onAuthLost();
      throw { kind: 'auth' };
    }
    if (!r.ok) {
      let body = {};
      try { body = await r.json(); } catch { body = {}; }
      throw {
        kind: body.error === 'scope not granted' ? 'scope' : 'error',
        message: body.error || `erreur ${r.status}`,
      };
    }
  }

  // a raw fetch failure (network down, DNS, CORS) throws a browser-English TypeError —
  // wrap it into the house error shape so every surface stays French and honest
  async function doFetch(path, init) {
    try {
      return await fetchFn(path, init);
    } catch {
      throw { kind: 'error', message: 'réseau indisponible — vérifie ta connexion' };
    }
  }

  async function json(path, { method = 'POST', json: jsonBody, yaml } = {}) {
    const headers = { 'Accept': 'application/json', 'Authorization': 'Bearer ' + token() };
    let body;
    if (yaml !== undefined) { headers['Content-Type'] = 'application/yaml'; body = yaml; }
    if (jsonBody !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(jsonBody); }
    const r = await doFetch(path, { method, headers, body });
    await handleResponse(r);
    return r.json();
  }

  async function text(path, accept) {
    const headers = { 'Accept': accept, 'Authorization': 'Bearer ' + token() };
    const r = await doFetch(path, { headers });
    await handleResponse(r);
    return r.text();
  }

  return { json, text };
}
