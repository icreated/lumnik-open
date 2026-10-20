# Troubleshooting, by symptom

Find your symptom; follow the link to the page that owns the fix.

## The stack won't cooperate

Which restart is safe, and what each one costs: the [on-call runbook](../operations/oncall-runbook.md).

| Symptom | First move |
|---|---|
| The hub won't start, or 500s everywhere | `curl -s http://localhost:8080/q/health` (no `lm` command reads it), then [the hub logs](logs.md) |
| `lumnik.secret.master-key is still the placeholder` at boot | `LUMNIK_SECRET_MASTER_KEY` is not set — generate one with `openssl rand -base64 32` ([quickstart table](../quickstart/installer.md#troubleshooting)) |
| `lumnik.secret.master-key is too short (< 16 chars)` at boot | the key is shorter than 16 characters — same fix |
| First boot seems stuck | migrations run at first boot — give it a minute, watch `./deploy/selfhost/logs.sh hub` |
| `up.sh` refuses to start, naming missing tools | its preflight — the message shows the exact install line; run it, re-run `up.sh` ([Windows walkthrough, step 3](../deploy-windows.md#step-3-get-lumnik-in-ubuntu-5-min)) |
| `!! could not obtain a Keycloak admin token` at the end of `up.sh` — then logins fail | Keycloak was still finishing its first start (up.sh retries ~25 s, then warns) — re-run `up.sh`; if it persists, `./deploy/selfhost/logs.sh keycloak` |
| Anything on a Windows PC | the dedicated page: [Deploy on Windows → Troubleshooting](../deploy-windows.md#troubleshooting-windows-edition) |
| https / certificate trouble | the TLS façade — caddy logs, [Deploy](../deploy.md#one-https-origin-the-tls-facade) |

## Login and identity

| Symptom | First move |
|---|---|
| `401` with `tenant_id`, `subject/issuer` or claim errors | the claim wiring — [BYO IdP troubleshooting](../security/byo-idp.md#troubleshooting) has the full table |
| `403` on every endpoint despite a valid login | the `groups` claim doesn't carry `lm_*` role names — [BYO IdP](../security/byo-idp.md#troubleshooting) |
| `403 {"error":"user is deactivated"}` | the identity was archived (SCIM departure) — [SCIM](../security/scim.md#troubleshooting) |
| Login broke after moving to TLS or a new hostname | the issuer changed and orphaned the JIT identity — [BYO IdP](../security/byo-idp.md#troubleshooting) |
| SCIM calls misbehave | [SCIM troubleshooting](../security/scim.md#troubleshooting) — the issuer-mismatch `404` is the classic |
| `lm` prints `API error 403 (forbidden): no details from the server — your account may lack the required role` (raw HTTP: `403` with an empty body) | a required role is missing — [Roles & permissions](../security/role-management.md#troubleshooting) |
| `API error 403: missing role lm_integrator` | the `/api/platform/*` gate: the account lacks `lm_integrator` — same page |

## Refusals that are features

| Symptom | What it is |
|---|---|
| `access refused (403): scope not granted` (raw HTTP: `403 {"error":"scope not granted"}`) | the métier boundary: your user holds scope roles and this resource is outside them. It covers every read surface (PWA lists, chat, views), so several going quiet at once points here. [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) |
| The chat says "I can't answer that…" or "I won't run this…" | a [guard](../transparency/ask-honesty.md) refused rather than guess; the message says what to do next |
| "The query returned no rows. That can mean…" | an empty result — check the SQL shown before concluding there are none |
| `409 {"error":"no schema discovered for scope '…'"}` on an ask | nothing was applied to the scope yet, or its last connector was deleted or moved — apply an entity (the message says so), then ask again |
| The ask answers *"I have no data declared for this scope yet…"* although you ingested into that scope | the scope's schema card declares no tables — see below |
| `429` on chat, search, or view-generate — not a connector run | the hub's own inbound limit — [Rate limits](../operations/rate-limits.md#troubleshooting). A vendor throttling a connector is the `429` under [Ingestion](#ingestion) |

Look at the scope's schema card (needs `lm_admin` on top of `lm_integrator`):

| `lm source schema --scope <scope>` prints | Meaning | Fix |
|---|---|---|
| `(no tables — run 'lm source rediscover --scope <scope>')` | the card exists but declares no tables | run that `lm source rediscover`; it removes the empty card |
| `Error: API error 404: No schema card found for scope: <scope>` | no card at all — the `409` state above | apply an entity to the scope |

The ask is Pro only; `lm source schema` and `rediscover` exist on both editions.

## The ask and the model

| Symptom | First move |
|---|---|
| The ask/chat errors after ~20 s, with an opaque error id | the model failed to load on the machine running Ollama — on Windows, **update the NVIDIA drivers first**, then the RAM note: [Deploy on Windows, step 5](../deploy-windows.md#step-5-the-ai-on-the-windows-side-20-min-8-gb-of-downloads) |
| The very first ask after a restart is slow or times out | the model loads on first use (tens of seconds on CPU) — ask again; on a server set `OLLAMA_KEEP_ALIVE=-1` in Ollama's own environment ([Deploy](../deploy.md#self-host-one-command)) |
| Chat/search return nothing, ask has no model | Ollama isn't installed or reachable — install it ([Windows: step 5](../deploy-windows.md#step-5-the-ai-on-the-windows-side-20-min-8-gb-of-downloads)) and check `OLLAMA_BASE_URL` ([Models](../rag/models.md#the-knobs)) |
| Same, on a Linux host | Ollama listens on `127.0.0.1`, which the hub container cannot reach — bind it on the Docker bridge: [Deploy → Linux host](../deploy.md#self-host-one-command) |

## Ingestion

| Symptom | First move |
|---|---|
| A run failed, or rows are missing | the dedicated recipe: [Diagnosing a failed run](diagnose-a-run.md) |
| `run scheduled` looked fine, but `lm run list` says **Failed** | "scheduled" only means *queued*. Failed at 0/0 usually means the source did not answer — `lm run get <id>` prints why ([Diagnosing a failed run](diagnose-a-run.md)) |
| A run says **Completed** but `IN=0 OUT=0` | the connector found nothing to read — for file connectors, the path or mount does not exist where the hub looks (the [WOW walkthrough's ✔ checks](../demo/wow-customer/README.md#replay-it-by-hand-on-the-self-host-stack) catch it) |
| `lm entity apply` errors naming a relation that `does not exist` | a source connector never landed rows — check `lm run list` |
| An applied entity never shows up in `lm ask`/chat | the manifest declares no `scopes:` — [Entities troubleshooting](../entities.md#troubleshooting) |
| `429 Too Many Requests` from a REST vendor | lower `default_rate_limit` — [REST troubleshooting](../connectors/rest.md#troubleshooting) |
| SFTP auth, host-key, or S3 `403` errors | [CSV & files troubleshooting](../connectors/csv.md#troubleshooting) |
| `Bearer token unavailable (env=…)` — REST connectors only | the secret was never set — `lm secret set`, [quickstart table](../quickstart/installer.md#troubleshooting) |
| jdbc run fails: `password_env '<NAME>' is not set in the server environment` | jdbc does not read `lm secret` — the variable must exist in the **hub's** environment — [JDBC troubleshooting](../connectors/jdbc.md#troubleshooting) |
| A cursor seems stuck in the past | `lm endpoint reset-cursor <connector>/<endpoint>` — next run is a full refresh |

## Outputs and apps

| Symptom | First move |
|---|---|
| A webhook never arrived | `lm outputs list` (failures first), then `lm outputs replay <ID>` — or `^R` in the TUI `:outputs` ([Outputs](../admin-console-guide.md#watch-deliveries-and-replay-a-failure)) |
| The Fiches list on a phone looks empty | see the table below |
| The PWA shows an old version | the service worker re-installs only when `sw.js` changes — [the shell-cache note](../apps/pwa.md) |
| `lm view -f v.yaml` (the list), `lm view KEY -f v.yaml` (one record) or `lm view apply` refused | a manifest field/column mismatch, or a missing scope role — [Views troubleshooting](../apps/views.md#troubleshooting) |
| `lm process apply` refused, or a declared process never fires | a manifest/schema mismatch, or its trigger table broke at runtime — [Processes troubleshooting](../apps/processes.md#troubleshooting) |
| `lm workflow apply` refused | an unknown field, a lifecycle collision, or dormancy declared without `on.key` — [Workflows troubleshooting](../apps/workflows.md#troubleshooting) |
| A workflow alert didn't fire | dormancy is observation-relative and needs `on.key` + `dormancy.after` — [Workflows](../apps/workflows.md#dormancy-the-memory-of-the-breathing) |

An empty Fiches list on the phone:

| You see | Meaning | Where to look |
|---|---|---|
| an amber box with a message | a refusal, with its reason: no view applied for the scope, or `403 scope not granted` | [Views troubleshooting](../apps/views.md#troubleshooting), [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) |
| grey "Aucune ligne" text | the view rendered and found zero rows | the source data |

Still stuck? [Reading the logs](logs.md) says where every component writes, and how to raise the
volume for the minutes you need it.
