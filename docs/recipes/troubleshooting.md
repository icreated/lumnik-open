# Troubleshooting, by symptom

You have a symptom; this page names its family and hands you to the page that owns
the cure. Detailed cause→fix tables live with their subjects — this is the front door,
so you never have to know *which* page to search.

## The stack won't cooperate

Deciding whether a **restart** is safe — and what each one costs, with the exact
commands — is the [on-call runbook](../operations/oncall-runbook.md).

| Symptom | First move |
|---|---|
| The hub won't start, or 500s everywhere | `curl -s http://localhost:8080/q/health`, then [the hub logs](logs.md) |
| `lumnik.secret.master-key is too short` at boot | the env var is missing or < 16 chars — [quickstart table](../quickstart/installer.md#troubleshooting) |
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
| `403` with an **empty body** | a misconfiguration, not a feature — a required *role* is missing, not a scope. [Roles & permissions](../security/role-management.md#troubleshooting) tells all three 403 shapes apart |
| `403` plain text `missing role lm_integrator` | the third shape — the `/api/platform/*` gate, ahead of any endpoint-level check. Same page: [Roles & permissions](../security/role-management.md#troubleshooting) |

## Refusals that are features

If **several read surfaces go quiet at once** — the PWA list and chat, say, for the same
user — suspect the scope boundary before anything else: it covers the whole read surface,
not just the chat, so one missing `scope:<tag>` grant explains both. [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in)

| Symptom | What it is |
|---|---|
| `403 {"error":"scope not granted"}` | the métier boundary, working — your user holds scope roles and this resource is outside them. [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) |
| The chat says "I can't answer that…" or "I won't run this…" | a [guard](../transparency/ask-honesty.md) preferred refusal over a guess; the message itself says what to do next |
| "The query returned no rows. That can mean…" | zero-row honesty — verify the shown SQL before concluding there are none |
| `409 {"error":"no schema discovered for scope '…'"}` on an ask | expected on a fresh scope: nothing was applied to it yet — apply an entity (the message says so), then ask again |
| The ask answers *"I have no data declared for this scope yet…"* although you have ingested into that scope | the sibling state of the `409` above: a schema card exists for the scope but declares no tables, so guard 1 abstains. Look at the card itself — `lm source schema --scope <scope>` (needs `lm_admin` on top of `lm_integrator`). `(no tables — run 'lm source rediscover --scope …')` is this state; `API error 404: No schema card found for scope: …` is the `409` one. (Both the ask and this command live in the chat extensions — an open-edition build has neither) |
| `429` on chat, search, or view-generate — not a connector run | the hub's own inbound limit; the *other* `429` (a vendor throttling a connector, see Ingestion below) is a different gate — [Rate limits](../operations/rate-limits.md#troubleshooting) |

## The ask and the model

| Symptom | First move |
|---|---|
| The ask/chat errors after ~20 s, with an opaque error id | the model failed to load on the machine running Ollama — on Windows, **update the NVIDIA drivers first** (the classic), then the RAM note: [Deploy on Windows, step 5](../deploy-windows.md#step-5-the-ai-on-the-windows-side-20-min-8-gb-of-downloads) |
| The very first ask after a restart is slow or times out | the model loads into memory on first use (tens of seconds on CPU) — ask again; on a server, keep it loaded by setting `OLLAMA_KEEP_ALIVE=-1` in Ollama's own environment (`deploy/selfhost/README.md` has the full prerequisite section) |
| Chat/search return nothing, ask has no model | Ollama isn't installed or reachable — install it ([Windows: step 5](../deploy-windows.md#step-5-the-ai-on-the-windows-side-20-min-8-gb-of-downloads)) and check `OLLAMA_BASE_URL` ([Models](../rag/models.md#the-knobs)). **On a Linux host this is usually the bridge trap**: the hub reaches Ollama at `host.docker.internal`, which on Linux resolves to the *docker bridge IP* (`172.17.0.1` by default) and not to your loopback — so Ollama must be bound on the bridge, not on `127.0.0.1`. Docker Desktop masks this, which is why it works on your laptop and not on the server. Full prerequisite list: `deploy/selfhost/README.md` in the repo |

## Ingestion

| Symptom | First move |
|---|---|
| A run failed, or rows are missing | the dedicated recipe: [Diagnosing a failed run](diagnose-a-run.md) |
| `run scheduled` looked fine, but `lm run list` says **Failed** | "scheduled" only means *queued* — the ledger holds the verdict. Failed at 0/0 usually means the source didn't answer (is the source system up and reachable?) — [Diagnosing a failed run](diagnose-a-run.md) |
| A run says **Completed** but `IN=0 OUT=0` | the connector found *nothing to read* — for file connectors, the path/mount doesn't exist where the hub looks (a missing Docker mount is the classic; the [WOW walkthrough's ✔ checks](../demo/wow-customer/README.md#replay-it-by-hand-on-the-self-host-stack) catch it) |
| `lm entity apply` errors naming a relation that `does not exist` | the entity fuses tables that its source connectors create — a source run above it never landed rows; check `lm run list` for the two symptoms just before this one |
| An applied entity never shows up in `lm ask`/chat | no `scopes:` were declared on the manifest — undiscoverable by design, not a bug — [Entities troubleshooting](../entities.md#troubleshooting) |
| `429 Too Many Requests` from a REST vendor | lower `default_rate_limit` — [REST troubleshooting](../connectors/rest.md#troubleshooting) |
| SFTP auth, host-key, or S3 `403` errors | [CSV & files troubleshooting](../connectors/csv.md#troubleshooting) |
| `Bearer token unavailable (env=…)` — REST connectors only | the secret was never set — `lm secret set`, [quickstart table](../quickstart/installer.md#troubleshooting) |
| jdbc run fails: `password_env … unset` | jdbc does not consult `lm secret` — the variable must exist in the **hub server's** environment, not the laptop's — [JDBC troubleshooting](../connectors/jdbc.md#troubleshooting) |
| A cursor seems stuck in the past | `lm endpoint reset-cursor <connector>/<endpoint>` — next run is a full refresh |

## Outputs and apps

| Symptom | First move |
|---|---|
| A webhook never arrived | `lm outputs list` — deliveries, failures first; `^R` in `:outputs` replays one |
| The Fiches list on a phone looks empty | it is never a blank screen — look at *how* it's empty: an amber box with a message is a refusal with its reason (no view applied for the scope, or `403 scope not granted` — same family as above); plain grey "Aucune ligne" text means the view rendered and genuinely found zero rows — check the source data, not the scope. [Views troubleshooting](../apps/views.md#troubleshooting) |
| The PWA shows an old version | the service worker re-installs only when `sw.js` changes — [the shell-cache note](../apps/pwa.md) |
| `lm view -f v.yaml` (the list), `lm view KEY -f v.yaml` (one record) or `lm view apply` refused | a manifest field/column mismatch, or a missing scope role — [Views troubleshooting](../apps/views.md#troubleshooting) |
| `lm process apply` refused, or a declared process never fires | a manifest/schema mismatch, or its trigger table broke at runtime — [Processes troubleshooting](../apps/processes.md#troubleshooting) |
| `lm workflow apply` refused | an unknown field, a lifecycle collision, or dormancy declared without `on.key` — [Workflows troubleshooting](../apps/workflows.md#troubleshooting) |
| A workflow alert didn't fire | dormancy is observation-relative and needs `on.key` + `dormancy.after` — [Workflows](../apps/workflows.md#dormancy-the-memory-of-the-breathing) |

Still stuck? [Reading the logs](logs.md) tells you where every component writes, and
how to raise the volume for the minutes you need it.
