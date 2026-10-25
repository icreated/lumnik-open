// Auth-redirect URL wiring for the real PWA at /app/: the KC realm base and the PKCE
// REDIRECT that carries ?m=. App IDENTITY (title, icon, theme) is no longer compiled here —
// see appRegistry.mjs, which reads it from the runtime kind:App registry (#571). This file
// keeps only what the auth round-trip needs before any identity is resolved.

// The raw ?m= key from the URL, or 'clients' when absent. This is ALL the redirect_uri needs
// — it must round-trip whatever tag the visitor actually typed, even one the registry does
// not (yet, or ever) recognise, so a login redirect never silently rewrites it.
export function urlKey(search) {
  return new URLSearchParams(search).get('m') || 'clients';
}

// {protocol,origin,hostname} → https façade: same-origin path-proxied Keycloak;
// else plain LAN spike mode: hostname on the fixed Keycloak port.
export function kcBase(loc) {
  return loc.protocol === 'https:'
    ? `${loc.origin}/realms/lumnik`
    : `http://${loc.hostname}:8180/realms/lumnik`;
}

// the OAuth round-trip must come back to THIS app — ?m rides the redirect_uri (mirrors spike REDIRECT)
export function redirectUri(loc, metierKey) {
  return loc.origin + '/app/' + (metierKey === 'clients' ? '' : `?m=${encodeURIComponent(metierKey)}`);
}
