# RAG over HTTP — the chat, search and scope API

> The JSON contract behind `lm ask`, the TUI and the PWA — chat, search, scopes, corpora and the
> admin schema endpoints, with every guard refusal and every HTTP error shape a caller can hit.

Everything `lm ask`, the TUI `:ask` and the métier PWA do rides a small set of JSON-over-HTTP
endpoints on the hub. This page is the contract for calling them yourself — from a script, an
integration, or an app you build on the substrate. The PWA at `/app/` is the living example: its
chat panel is one `POST /api/rag/chat` with `mode: "analytic"`
(`lumnik-hub/src/main/resources/META-INF/resources/app/src/components/chat.mjs`), nothing more.

All requests and responses are `application/json` unless noted. All example payloads below are
**illustrative** — field names and shapes are exact, values are made up.

## The two doors

The same chat lives behind two paths with different audiences:

| Door | Path | Who | Guarded by |
|---|---|---|---|
| **User** | `POST /api/rag/chat` | end users (`lm_user` role) — the PWA's door | `@RequiresPermission("lm_user")` |
| **Platform** | `POST /api/platform/rag/chat` + everything else under `/api/platform/rag/*` | integrators (`lm_integrator` role) — `lm`, scripts, tooling | `PlatformApiAuthFilter` on the whole `/api/platform/*` prefix |

Behavior is identical — the user door delegates to the platform one, so tenant validation, the
[métier-scope binding](scopes.md#binding-users-to-scopes-opt-in) and the
[honesty guards](../transparency/ask-honesty.md) ride through unchanged. Only the required role
and the rate-limit bucket differ. If you are building an app for end users, use `/api/rag/chat`;
`/api/platform/*` stays the integrator surface.

Two admin-only endpoints (`status`, `reindex`, `schema`, `rediscover`) additionally require
`lm_admin` — and, living under `/api/platform/*`, they pass through the integrator filter too,
so the token must carry both roles.

## Authentication

Every call carries a Bearer JWT:

```
Authorization: Bearer <access-token>
```

The token comes from your OIDC issuer — the same one `lm login` uses (see
[Point `lm` at the hub](../deploy.md#point-lm-at-the-hub)). After `lm login`, the short-lived
access token sits in `~/.lm/config.yaml` under `access:` (auto-refreshed by `lm`; if a curl call
starts answering 401, run any `lm` command or `lm login` again). Scripts driving `lm` itself can
instead set a static token via the `LUMNIK_TOKEN` environment variable (see the
[`lm` reference](../cli/lm-reference.md)).

The **tenant is resolved from the token**, never from a client-supplied header — an
authenticated caller cannot point a request at another tenant's data. (In dev with
`lumnik.security.dev-bypass=true`, auth is bypassed and tenant/user come from the
`X-Tenant-ID` / `X-User-ID` headers instead.)

## Chat

```
POST /api/rag/chat              (lm_user)
POST /api/platform/rag/chat     (lm_integrator)
```

One endpoint, two modes, selected by the `mode` field:

| Field | Type | Meaning | Default |
|---|---|---|---|
| `question` | string | the natural-language question — **required** (blank → 400) | — |
| `mode` | string | `"analytic"` (case-insensitive) = text-to-SQL over the ingested hub; anything else or absent = semantic retrieval + chat | semantic |
| `scope` | string | the métier tag. `"all"` = no specific scope; blank or absent = **your default** (`lm scope set`) if set, else none. Semantic: filters retrieval to chunks carrying the tag. Analytic: names the schema card to ask over — a scope-bound user must name, or default to, a granted scope ([scopes](scopes.md#binding-users-to-scopes-opt-in)). | your default / `system` |
| `corpus` | string | **semantic only**: restrict retrieval to one corpus (usually a connector source, see [corpora](#corpora)) | all corpora |
| `topK` | integer | **semantic only**: how many chunks to retrieve as context | 10 |

`corpus` and `topK` are silently ignored in analytic mode.

### Semantic response

The question is embedded, the top-K most similar chunks are retrieved (tenant-isolated, tag- and
corpus-filtered), and the LLM answers **from that context only**, citing chunks inline with
`[row:hash]` tags:

```json
{
  "answer": "Three customers are based in Lyon: Acme SARL [row:a3f9c2], Bistrot Lumière [row:7d01be] ...",
  "citations": ["a3f9c2...", "7d01be...", "..."]
}
```

`citations` lists the row hashes of **every retrieved chunk that is a row** — the context the
model saw. Not every chunk is one: a `kind:Workflow` lifecycle is indexed with no row hash, so it
grounds the answer and cites nothing (an answer drawn only from lifecycles comes back with
`"citations": []`). The `[row:hash]` tags inside `answer` are the ones the model says it used. The model is instructed to say so
explicitly when the context cannot answer the question.

### Analytic response (`mode: "analytic"`)

The question is translated to a single read-only `SELECT` over the scope's declared schema,
guarded, executed, and phrased back:

```json
{
  "answer": "There are 42 open orders, for a total of 18 750,00 €.",
  "sql": "SELECT count(*), sum(grand_total) FROM connector.c_order WHERE status = 'open'",
  "rowCount": 1,
  "dataAsOf": "2026-08-20T06:05:00Z",
  "rows": [ { "count": 42, "sum": 18750.00 } ],
  "otherDoors": []
}
```

- `sql` is **always** the query that ran (or was refused) — verification is one glance away.
- `rows` is the raw result, one `{column: value}` map per row, capped at 500 rows
  (a larger `LIMIT` in the generated SQL is lowered).
- Execution is read-only, tenant-scoped by Row-Level Security, with a statement timeout.

### How refusals surface in the payload

The [five deterministic guards](../transparency/ask-honesty.md) apply to every analytic call —
this page won't re-explain them, but you need to know **how a refusal arrives**: as a normal
`200` analytic response, with the guard's refusal message as the `answer`, `rowCount: 0` and
`rows: []`. The `sql` field carries the refused query (so you can see *what* was refused), or
`""` when the model abstained before any SQL existed. A **guard refusal** looks like this:

```json
{
  "answer": "I won't run this: 'segment_gold' is not a declared value of segment (known: ...). If it should exist, add it to the entity manifest.",
  "sql": "SELECT count(*) FROM connector.c_bpartner WHERE segment = 'segment_gold'",
  "rowCount": 0,
  "rows": []
}
```

`dataAsOf` is the end of the scope's **last completed ingestion run** — how fresh the
answer's ground is. It is absent when the scope has no completed run (fresh install, a
one-shot demo): absence is stated, never a fabricated timestamp. Refusals carry no
`dataAsOf`.

`otherDoors` names, as tokens, the doors that *can* answer when this one abstains and the scope
holds the answer somewhere text-to-SQL structurally cannot read. It is `[]` on every other
response, and the two tokens today are `"lifecycle"` (the scope declares a
[kind:Workflow](../apps/workflows.md), retrieved by the semantic door, not by SQL) and
`"conflicts"` (the scope fuses an entity, so the row-by-row disagreements live in a ledger the
schema card does not advertise):

```json
{
  "answer": "I can't answer that from the data available for this scope — … This scope does declare a lifecycle for its documents, …",
  "sql": "",
  "rowCount": 0,
  "rows": [],
  "otherDoors": ["lifecycle"]
}
```

**The `answer` already states the fact, and names no command on purpose** — the same string is
served to `lm ask`, to the PWA and to your client, and a CLI command in it would send a user on a
phone to a terminal they do not have. `otherDoors` is that same fact in a form you can branch on:
map a token to whatever gesture *your* surface has, and ignore the ones you have none for. `lm ask`
prints `lm chat` and `lm conflicts list`; the PWA offers its fiches for `conflicts` and stays
silent on `lifecycle`, having no lifecycle surface. A token you do not recognise — a newer hub —
must be passed over, never rendered raw.

An app consuming this door should treat a `rowCount: 0` answer as *"read the answer text"*, not
as *"there are none"* — that asymmetry is the whole point. In the PWA, the **abstentions**
(the two refusals that return `sql: ""`) get a distinct amber "refusal" bubble; a guard
refusal that carries the refused SQL — like the example above — currently renders as an
ordinary answer bubble whose summary line shows SQL that was never executed. Read the
answer text either way; the distinct bubble for guard refusals is a tracked follow-up.

Refusals that happen **before** the ask — a scope-bound user naming a scope they don't hold —
are HTTP errors instead: `403 {"error":"scope not granted"}` (see
[scopes](scopes.md#binding-users-to-scopes-opt-in)).

## Search

```
POST /api/platform/rag/search     (lm_integrator)
```

The raw retrieval layer under the semantic chat — embed a query, get the nearest chunks, no LLM
answer:

| Field | Type | Meaning | Default |
|---|---|---|---|
| `query` | string | the search text — **required** (blank → 400) | — |
| `topK` | integer | number of hits | 10 |
| `corpus` | string | restrict to one corpus (source) | all |
| `scope` | string | restrict to chunks carrying this métier tag; `"all"`/blank = no tag filter (for a scope-bound user, rewritten onto their granted set — never unfiltered) | none |
| `filters` | object | accepted in the body but **not applied yet** — reserved; don't rely on it | — |

```json
{
  "results": [
    {
      "rowHash": "a3f9c2...",
      "content": "Acme SARL, Lyon, segment: premium, since 2019 ...",
      "similarity": 0.87,
      "metadata": { "row_hash": "a3f9c2...", "table": "c_bpartner", "...": "..." }
    }
  ]
}
```

`similarity` is cosine similarity (1 = identical direction). The same scope binding applies: a
scope outside a bound user's grants → `403 {"error":"scope not granted"}`.

## Scopes

```
GET /api/platform/rag/scopes      (lm_integrator)
```

Lists the scopes that exist in this tenant — remember, [a scope is declared on the
connector](scopes.md#a-scope-is-declared-on-the-connector), so this is derived from the
ingested chunks:

```json
[
  { "scope": "clients", "connectors": 2, "chunks": 1240, "lastIndexed": "2026-07-20T09:14:03Z" },
  { "scope": "stock",   "connectors": 1, "chunks": 310,  "lastIndexed": "2026-07-19T17:02:41Z" }
]
```

`lastIndexed` is an ISO instant, or `""` if unknown. A scope-bound user sees **only their
granted métiers** in this list.

### Your default scope

`GET` / `PUT /api/platform/users/me/scope` read and set the default the chat doors use when a
request names no scope (`{"defaultScope": "compta"}`; empty string clears it). Both answer
`404 {"error":"no user record on this hub for user_id N"}` when your token names a hub user id
that has no row on this hub ([BYO IdP](../security/byo-idp.md#troubleshooting)).

## Corpora

```
GET /api/platform/rag/corpora     (lm_integrator)
```

Lists the corpora with indexed chunks. A corpus is usually a connector source, but not
always: applying a [`kind: Workflow`](../apps/workflows.md) indexes its declared lifecycle
under `workflow:<name>`, which is backed by a manifest rather than an ingest run.

```json
[
  { "name": "siebel-accounts", "chunkCount": 1240 },
  { "name": "erp-orders",      "chunkCount": 310 }
]
```

This listing is scope-bound too: a scope-bound user sees only corpora carrying at least one
granted métier tag — and an **untagged** corpus belongs to no métier, so it is invisible to a
bound user.

## Schema (admin)

```
GET  /api/platform/rag/schema?scope=<tag>        (lm_admin)
POST /api/platform/rag/rediscover?scope=<tag>    (lm_admin)
```

`schema` returns the scope's **schema card** — the exact tables/columns (with business labels,
descriptions, examples and declared value-domains) the analytic mode is allowed to query.
`404` if no card exists for the scope; run discovery first. `rediscover` rebuilds the card from
the ingested tables and returns a summary:

!!! note "These two are served by the hub, not by RAG"

    The `rag` in the path is history. The schema card is built, stored and served by the hub
    itself, so `schema` and `rediscover` — and the `lm source schema` / `lm source rediscover`
    the app-ladder pages send you to — are in the **open edition**. The paths did not change; only
    the module behind them did.

```json
{ "scope": "clients", "tableCount": 3, "columnCount": 41, "enriched": true, "sourceType": "connector" }
```

## Status and reindex (admin)

```
GET  /api/platform/rag/status     (lm_admin)
POST /api/platform/rag/reindex    (lm_admin)
```

```json
{ "indexed": true, "chunks": 1550, "indexedAt": "2026-07-20T09:14:03Z", "provider": "ollama-nomic", "embeddingModel": "nomic-embed-text" }
```

`provider` and `embeddingModel` are read from the **live embedding port** at each call —
the port's SPI `name()` and the model id its client is actually built from, never
constants. An edition shipping no embedding port answers `"none"` for both.

`reindex` rebuilds the **platform corpus** — the chunks derived from manifests, the
OpenAPI surface and hooks (`source: system`, tenant `0`) — and returns immediately:
`202 {"status": "reindex_started"}` — poll `status` to watch it land. It does **not**
touch chunks derived from ingested rows: those are written during ingestion itself and
erased by deleting their connector ([GDPR → erasure](../security/gdpr.md#the-rights-in-practice)).

## Errors

| Status | Body | When |
|---|---|---|
| `400` | `{"error": "question is required"}` (chat), `{"error": "query is required"}` (search), `{"error": "scope is required"}` (schema) | missing/blank required field |
| `401` | plain text `authentication required` + `WWW-Authenticate: Bearer` header | no/invalid token on `/api/platform/*` — note: **not** JSON |
| `401` | `{"error": "no tenant in security context"}` | analytic ask/schema call whose token resolves no usable tenant |
| `403` | plain text `missing role lm_integrator` | authenticated but not an integrator, on `/api/platform/*` |
| `403` | `{"error": "scope not granted"}` | a scope-bound user asked outside their granted set — the [uniform refusal](scopes.md#binding-users-to-scopes-opt-in) across the whole read surface |
| `404` | `{"error": "No schema card found for scope: ..."}` | `GET .../schema` on a scope with no discovered schema card yet |
| `409` | `{"error": "no schema discovered for scope '...' — apply an entity or run discovery first", "hint": "lm entity apply registers a schema for the scope; ask again after it lands"}` | analytic ask on a scope with no schema card at all — a fresh install, or a scope with no `kind: Entity` applied yet |
| `429` | `{"error": "rate limit exceeded"}` + `Retry-After: <seconds>` header | bucket exhausted — honor `Retry-After` before retrying |
| `503` | `{"error": "...", "hint": "..."}` | the needed port (chat/embedding/analytic) is not on the classpath, or the LLM/embedding backend is unreachable — a deployment problem, not a caller problem |

Rate limits are per **(bucket, tenant)** in a fixed 60-second window. The chat and search
endpoints ride three buckets — `user-chat`, `platform-chat`, `platform-search` — each
configurable via `lumnik.ratelimit.<bucket>.rpm` (fallback `lumnik.ratelimit.default.rpm`,
code default 60; `0` disables). See [rate limits](../operations/rate-limits.md).

## Quickstart — one chat call with curl

```bash
# 1. Log in once (device flow against your OIDC issuer — see the deploy guide)
lm login

# 2. Grab the access token lm stored (single-context config; re-run after it expires)
TOKEN=$(awk '/^ *access:/ {print $2; exit}' ~/.lm/config.yaml)

# 3. Ask your data — the same call the PWA makes
curl -s https://your-hub.example.com/api/rag/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question": "how many customers in Lyon?", "mode": "analytic", "scope": "clients"}'
```

The answer comes back with the SQL that ran — [read it](../transparency/ask-honesty.md) before
you trust a zero.

## See also

- [Scopes](scopes.md) — what a scope is, and the opt-in per-user binding
- [Ask honesty — the five guards](../transparency/ask-honesty.md) — the refusal contract behind analytic mode
- [The métier PWA](../apps/pwa.md) — the first consumer of this API
- [Deploy](../deploy.md) — issuer, `lm login`, pointing `lm` at the hub
