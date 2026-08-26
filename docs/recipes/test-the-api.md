# Test the API — URLs, auth, Postman

> Mint a token against the bundled realm and paste it into `curl`, Postman, or Swagger UI —
> every door the hub serves, listed with the role it requires.

Every door the hub serves, which token opens it, and how to point Postman (or plain
`curl`) at it. URLs below are the self-host defaults; behind the
[TLS façade](../deploy.md#one-https-origin-the-tls-facade) everything lives on one
https origin, with Keycloak path-proxied under `/realms/*`.

## The doors

| URL | Auth | What it is |
|---|---|---|
| `http://localhost:8080/q/health` | none | UP/DOWN with named checks — [observability](../operations/observability.md) |
| `http://localhost:8080/q/metrics` | none | Prometheus metrics (block `/q/*` at the proxy on a public origin) |
| `http://localhost:8080/q/openapi` | none | **the OpenAPI spec** — what Postman imports |
| `http://localhost:8080/q/swagger-ui/` | none | the try-it console, shipped in the packaged hub |
| `http://localhost:8080/app/` | browser login | the [métier PWA](../apps/pwa.md) |
| `http://localhost:8080/api/rag/chat` | Bearer, `lm_user` | the chat door — semantic or analytic, [guards](../transparency/ask-honesty.md) apply |
| `http://localhost:8080/api/entities/…` | Bearer, `lm_user` | fused-entity reads, scope-bound — [entities](../entities.md) |
| `http://localhost:8080/api/views` | Bearer, `lm_user` | saved-view discovery, scope-bound — [views](../apps/views.md) |
| `http://localhost:8080/api/platform/…` | Bearer, `lm_integrator` | the whole integrator surface: connectors, validator, runs, apply |
| `http://localhost:8080/scim/v2/…` | SCIM bearer token | deprovisioning — [SCIM](../security/scim.md) |
| `http://localhost:8180/realms/lumnik` | — | Keycloak: the issuer, and the token endpoint below |

Roles ride in the JWT's `groups` claim; the catalog is on
[Roles & permissions](../security/role-management.md). A scope-bound user gets the
uniform `403 {"error":"scope not granted"}` outside their métiers —
[a feature, not a failure](troubleshooting.md#refusals-that-are-features).

## Mint a token

The bundled realm ships an `integrator` user (password: `INTEGRATOR_PASSWORD` in the
`.env` that `up.sh` generated) and a public CLI client, `lm-cli`, that allows the
password grant. In `curl`:

```bash
TOKEN=$(curl -s "http://localhost:8180/realms/lumnik/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=lm-cli \
  -d username=integrator --data-urlencode password="$INTEGRATOR_PASSWORD" \
  | jq -r .access_token)

# First authenticated smoke test:
curl -s http://localhost:8080/api/platform/health -H "Authorization: Bearer $TOKEN"
```

Tokens from the bundled realm live **one hour** (`accessTokenLifespan: 3600`) — mint
again when a call starts answering `401`. Your own IdP may be stricter.

## The same thing in Postman

On the collection's **Authorization** tab, choose *OAuth 2.0* and fill:

| Field | Value |
|---|---|
| Grant type | Password Credentials |
| Access Token URL | `http://localhost:8180/realms/lumnik/protocol/openid-connect/token` |
| Client ID | `lm-cli` |
| Username / Password | `integrator` / the `INTEGRATOR_PASSWORD` from `.env` |

*Get New Access Token* → every request in the collection inherits the Bearer, and
Postman re-mints it when it expires.

## Import the whole API

**Postman → Import → URL** → `http://localhost:8080/q/openapi`. Postman reads the
OpenAPI spec straight from the running hub and builds the collection — every endpoint,
pre-filled. Set the collection auth as above and explore.

Prefer a browser? `http://localhost:8080/q/swagger-ui/` is the same spec with a
try-it button — paste the token from *Mint a token* into its **Authorize** dialog.

!!! note "Testing as a métier user, not an integrator"
    Everything above authenticates as `integrator`. To see the API the way a
    scope-bound user sees it — the `403 scope not granted` boundaries, the user chat
    door — mint the token with that user's credentials instead; the mechanics are
    identical. [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) explains the
    binding.
