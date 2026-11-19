# Test the API — URLs, auth, Postman

> Take a token from `lm token` and paste it into `curl`, Postman, or Swagger UI —
> every door the hub serves, listed with the role it requires.

Day-to-day work goes through `lm`; this page is for testing the HTTP API itself.
URLs below are the self-host defaults. Behind the
[TLS proxy](../deploy.md#one-https-origin-the-tls-proxy) everything lives on one
https origin, with Keycloak's `lumnik` realm under `/realms/lumnik`.

## The doors

| URL | Auth | What it is |
|---|---|---|
| `localhost:9000/q/health`, inside the hub's container | none | UP/DOWN with named checks — [observability](../operations/observability.md) |
| `localhost:9000/q/metrics`, inside the hub's container | none | Prometheus metrics |
| `localhost:9000/q/openapi`, inside the hub's container | none | **the OpenAPI spec** — what Postman imports |
| `http://localhost:8080/app/` | browser login | the [PWA](../apps/pwa.md) |
| `http://localhost:8080/api/rag/chat` | Bearer, `lm_user` | the chat door — semantic or analytic, [guards](../transparency/ask-honesty.md) apply |
| `http://localhost:8080/api/entities/…` | Bearer: reads `lm_user`; apply and writes `lm_admin` | fused entities, reads scope-bound — [entities](../entities.md) |
| `http://localhost:8080/api/views` | Bearer, `lm_user` | saved-view discovery, scope-bound — [views](../apps/views.md) |
| `http://localhost:8080/api/platform/…` | Bearer, `lm_integrator` (some doors also `lm_admin`) | the integrator surface: connectors, validator, runs, apply |
| `http://localhost:8080/scim/v2/…` | SCIM bearer token | deprovisioning, Pro only — [SCIM](../security/scim.md) |
| `http://localhost:8180/realms/lumnik` | — | Keycloak: the issuer, and the token endpoint below |

Roles ride in the JWT's `groups` claim; the catalog is on
[Roles & permissions](../security/role-management.md). A scope-bound user gets the
uniform `403 {"error":"scope not granted"}` outside their granted scopes —
[a feature, not a failure](troubleshooting.md#refusals-that-are-features).

## Mint a token

`lm` holds your session: sign in once with `lm login` (it opens the device flow), then
`lm token` prints an access token, renewed when it is about to expire. The bundled realm's
clients take no password, so this is the way in for `curl`. It needs `lm` 1.10.0 or later, and a
context pointing at your hub (once, as on the [deploy page](../deploy.md)):

```bash
lm config set-context local --server http://localhost:8080 --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context local
lm login
TOKEN=$(lm token)

# First authenticated smoke test:
curl -s http://localhost:8080/api/platform/health -H "Authorization: Bearer $TOKEN"
```

The reply lists every installed module (here a Pro hub with the AI switched off):

```json
{"platform":"ok","modules":{"core":{"status":"ok","version":"0.1.0"},"identity":{"status":"ok","version":"0.1.0"},"rag":{"status":"inactive","version":"0.1.0","reason":"disabled:lumnik.ai.enabled"},"llm":{"status":"inactive","version":"0.1.0","reason":"disabled:lumnik.ai.enabled"}},"search":{"status":"not_installed","affectsReadiness":false,"error":null}}
```

Tokens from the bundled realm live **5 minutes**: run `lm token` again when a call answers
`401`, or put it on each call, `-H "Authorization: Bearer $(lm token)"`. Your own IdP may be
stricter.

## The same thing in Postman

On the collection's **Authorization** tab, choose *Bearer Token* and set it to `{{token}}`. Put
the output of `lm token` in a `token` variable of the collection: every request inherits it.
When a request answers `401`, the token has expired: paste a new `lm token`.

## Import the whole API

The spec answers on the hub's management port, which nothing publishes. Save it from inside the
container, then **Postman → Import → File**:

```bash
docker compose -f docker-compose.selfhost.yml exec -T hub curl -s localhost:9000/q/openapi > lumnik-openapi.yaml
```

Postman builds the collection, every endpoint pre-filled. Set the collection auth as above.
The try-it console (Swagger UI) runs only on a hub started in dev mode, at `/q/swagger-ui/`.

!!! note "Testing as a business user, not an integrator"
    Everything above uses your own session. To see what a scope-bound user sees (the
    `403 scope not granted` boundaries, the user chat door), give `lm` a second context and
    sign in with that user's account:

    ```bash
    lm config set-context ventes --server http://localhost:8080 \
      --oidc-issuer http://localhost:8180/realms/lumnik
    lm login --context ventes
    lm token --context ventes
    ```

    [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) explains the binding.
