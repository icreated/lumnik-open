# TLS façade — one https origin for the self-host stack

Caddy in front of everything: the hub (API + the `/app/` PWA) at `/`, Keycloak
path-proxied under `/realms/*` + `/resources/*`. One origin means no mixed content,
no CORS, and **one token issuer for every device**.

> **Exposure:** unlike the base compose (loopback-only), this overlay binds 443/80 on
> **all interfaces** by default, so phones on the LAN can reach it. Restrict it with
> `LUMNIK_TLS_BIND` (e.g. `LUMNIK_TLS_BIND=127.0.0.1` behind your own reverse proxy, or a
> specific LAN address).

```bash
LUMNIK_TLS_HOST=erp.example.com \
  docker compose -f docker-compose.selfhost.yml -f docker-compose.tls.yml up -d
# → https://erp.example.com        (hub + PWA)
# → https://erp.example.com/realms/lumnik   (Keycloak)
```

Layer it LAST when combining overlays — its `QUARKUS_OIDC_TOKEN_ISSUER` must win.

## Certificates, automatically

| `LUMNIK_TLS_HOST` | Certificate |
|---|---|
| a public domain | Let's Encrypt, obtained and renewed by Caddy (ports 80+443 reachable) |
| a LAN IP / localhost | Caddy's internal CA — for local testing |

## Trusting the internal CA (LAN testing)

Browsers warn on the internal CA until you trust its root. Extract it from the
running container and install it on the device (iOS: AirDrop the file, then
*Settings → General → VPN & Device Management*, and enable full trust under
*Certificate Trust Settings*):

```bash
docker compose -f docker-compose.selfhost.yml -f docker-compose.tls.yml \
  cp caddy:/data/caddy/pki/authorities/local/root.crt ./lumnik-local-ca.crt
```

With the root trusted, the PWA gets its full powers on the phone: the real install
prompt, `crypto.subtle`, service workers. (Without it you can still browse past the
warning, but the browser will not install the PWA.)

## What the overlay changes

- **Keycloak** honors `X-Forwarded-*` (`KC_PROXY_HEADERS`) and announces the façade
  origin in every URL it mints (`KC_HOSTNAME`); the hub keeps its internal
  back-channel (`keycloak:8180`, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`).
- **The hub** validates tokens against the façade issuer and honors forwarded headers.
- Nothing else moves: the plain-http workflow (`http://localhost:8080`) keeps working
  for a same-machine operator without this overlay.

## Things to know

- **Bare-IP clients send no SNI** (RFC 6066 forbids it), and without a `default_sni`
  global option Caddy matches no certificate and aborts every handshake with TLS
  alert 80. The shipped Caddyfile sets it.
- **Re-running `up.sh` keeps the façade.** It reads the files the running hub was started
  with and applies them again, with the same `LUMNIK_TLS_HOST` and `LUMNIK_TLS_BIND`. A bare
  `docker compose up -d` on the base file alone still drops it: the hub then refuses every
  token minted through the https origin.
- **Changing the public origin changes the token issuer — and orphans JIT-provisioned
  identities.** They are keyed `(idp_issuer, idp_subject)`; after a move to https the
  same human is a NEW identity, and that first login fails on the existing login name. Migrating an install to TLS means
  migrating identities too: update `idp_issuer` on existing rows, or accept
  re-provisioning by clearing the old-issuer users. Pick the origin once, early.
