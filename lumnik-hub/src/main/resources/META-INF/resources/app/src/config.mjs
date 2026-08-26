// Per-métier config for the real PWA at /app/ — ported from the spike (m/index.html):
// METIERS registry, M_KEY resolution, KC realm base, and the PKCE REDIRECT that carries ?m=.

export const METIERS = {
  clients: { scope: 'clients', title: 'Clients', manifest: 'manifest-clients.json', icon: 'icon-192.png' },
  stock:   { scope: 'stock',  title: 'Stock',   manifest: 'manifest-stock.json',  icon: 'icon-stock-192.png' },
};

// '?m=stock' → {key:'stock', ...METIERS.stock}; unknown/absent métier param → clients (safe default)
export function resolveMetier(search) {
  const param = new URLSearchParams(search).get('m');
  const key = METIERS[param] ? param : 'clients';
  return { key, ...METIERS[key] };
}

// {protocol,origin,hostname} → https façade: same-origin path-proxied Keycloak;
// else plain LAN spike mode: hostname on the fixed Keycloak port.
export function kcBase(loc) {
  return loc.protocol === 'https:'
    ? `${loc.origin}/realms/lumnik`
    : `http://${loc.hostname}:8180/realms/lumnik`;
}

// the OAuth round-trip must come back to THIS métier — ?m rides the redirect_uri (mirrors spike REDIRECT)
export function redirectUri(loc, metierKey) {
  return loc.origin + '/app/' + (metierKey === 'clients' ? '' : `?m=${metierKey}`);
}
