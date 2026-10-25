# RAG over HTTP — the chat, search and scope API

> The JSON contract behind `lm ask`, the TUI and the PWA — chat, search, scopes, corpora and the
> admin schema endpoints, with every guard refusal and every HTTP error shape a caller can hit.

`lm ask`, the TUI's `:ask` and the PWA call a few JSON endpoints on the hub. This page is the
contract for calling them yourself, from a script or an app. The PWA's chat is one
`POST /api/rag/chat` with `mode: "analytic"`.

Requests and responses are `application/json` unless noted. The payloads below are real answers
from the demo desk. Where an `lm` command does the same, it is named.

## Quickstart — one chat call with curl

Mint a token as in [Test the API](../recipes/test-the-api.md#mint-a-token), then ask, the way the
PWA does:

```bash
curl -s https://your-hub.example.com/api/rag/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question": "Combien de clients GOLD sont à Lyon ?", "mode": "analytic", "scope": "desk", "lang": "fr"}'
```

The answer comes with its SQL and its rows: [the analytic response](#analytic-response-mode-analytic).
Read the SQL before you trust a zero.

## The two doors

The same chat lives behind two paths with different audiences:

| Door | Path | Role |
|---|---|---|
| **User** | `POST /api/rag/chat` | `lm_user` — the PWA's door |
| **Platform** | `POST /api/platform/rag/chat` + everything else under `/api/platform/rag/*` | `lm_integrator` — `lm`, scripts, tooling |

Both answer the same way, with the same [scope binding](scopes.md#binding-users-to-scopes-opt-in)
and [guards](../transparency/ask-honesty.md); only the role and the rate-limit bucket differ. An app
for end users uses `/api/rag/chat`.

Four admin endpoints (`status`, `reindex`, `schema`, `rediscover`) also require `lm_admin`: the
token must carry both `lm_integrator` and `lm_admin`.

## Authentication

Every call carries a Bearer JWT:

```
Authorization: Bearer <access-token>
```

Get one from your OIDC issuer: [Test the API — mint a token](../recipes/test-the-api.md#mint-a-token).
A token lasts one hour on the bundled realm: mint another when a call answers `401`.

The **tenant comes from the token**, never from a header.

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
| `scope` | string | the data-scope tag. `"all"` = no specific scope; blank or absent = **your default** (`lm scope set`) if set, else none. Semantic: filters retrieval to chunks carrying the tag. Analytic: names the schema card to ask over — a scope-bound user must name, or default to, a granted scope ([scopes](scopes.md#binding-users-to-scopes-opt-in)). | your default / `system` |
| `corpus` | string | **semantic only**: restrict retrieval to one corpus (usually a connector source, see [corpora](#corpora)) | all corpora |
| `topK` | integer | **semantic only**: how many chunks to retrieve as context, 1 to 50. Outside → `400 {"error":"topK must be between 1 and 50"}` | 10 |
| `lang` | string | the answer language, a two-letter code (`"fr"`). The answer is written in it whatever the question's language — a terse question such as `SO-000004 statut ?` carries none. Absent = the question's own language. Any other value → `400 {"error":"lang must be a two-letter language code"}`, in either mode. The PWA sends `fr`; `lm ask` sends `--lang`, else the language of `LC_ALL`, `LC_MESSAGES` or `LANG`, in that order. | the question's |
| `previous` | string | **analytic only**: the question the last answer was given for, so that this one may continue it (*« Et HAWARD ? »*). See [follow-up questions](#follow-up-questions). Blank or absent = a standalone question. Over 1000 characters → `400 {"error":"previous must be at most 1000 characters"}`. Sent in semantic mode → `400 {"error":"previous is only read in analytic mode"}`. | — |

`corpus` and `topK` are silently ignored in analytic mode. Fixed sentences
(abstentions, guard refusals) follow `lang` too, in French or English — see
[how refusals surface](#how-refusals-surface-in-the-payload).

### Semantic response

The question is embedded, the top-K most similar chunks are retrieved (tenant-isolated, tag- and
corpus-filtered), and the LLM answers **from that context only**, citing chunks inline with
`[row:hash]` tags:

```json
{
  "answer": "Les transitions autorisées pour une livraison correspondent à la transition d'état EN_ROUTE vers LIVREE.\n\nSelon les déclarations de workflow, seule cette transition est autorisée entre ces deux états.\n\n[No specific source is cited for the given context, but it can be inferred that it should follow a standard naming convention.",
  "citations": []
}
```

The semantic answer is the model's text, checked by no guard: here it ends with a stray sentence.
Read it with care; for figures, use the analytic mode.

- `citations` lists the row hashes of every retrieved chunk that is a row — the context the model
  saw. `lm data row HASH` shows the row behind one.
- A `kind: Workflow` lifecycle chunk has no row hash: an answer drawn only from lifecycles has
  `"citations": []`.
- The `[row:hash]` tags inside `answer` are the ones the model says it used.

### Analytic response (`mode: "analytic"`)

The question is translated to a single read-only `SELECT` over the scope's declared schema,
guarded, executed, and phrased back:

```json
{
  "answer": "Il y a 2 clients GOLD à Lyon.",
  "sql": "SELECT COUNT(*) \nFROM \"connector\".\"v_acmecustomer\" \nWHERE \"segment\" = 'GOLD' AND \"city\" = 'Lyon'\nLIMIT 500",
  "rowCount": 1,
  "rows": [
    {
      "count": 2
    }
  ],
  "dataAsOf": "2026-10-24T21:35:11.811114Z",
  "otherDoors": [
    "conflicts"
  ],
  "disputed": [
    {
      "key": "P-1008",
      "column": "city",
      "label": "Ville",
      "matched": "Lyon",
      "retained": "Lyon",
      "counted": true,
      "sources": [
        {
          "src": "t_acme_desk_erp",
          "value": "Lyon"
        },
        {
          "src": "t_acme_desk_crm",
          "value": "Villeurbanne"
        }
      ]
    }
  ],
  "disputedTruncated": false,
  "abstained": false,
  "understoodAs": null
}
```

Every field is always present.

- `sql` is the query that ran (or was refused).
- `disputed` (`[]` when empty) names the fused records the answer filtered on while
  their sources disagree; `disputedTruncated` says more exist than the ten listed. When it is
  non-empty, `otherDoors` carries `conflicts`. When it appears:
  [Disputed rows](../transparency/ask-rules.md#disputed-rows-a-disclosure-not-a-veto).

| `disputed[]` field | Meaning |
|---|---|
| `key` | the record's match key |
| `column`, `label` | the filtered column and its label |
| `matched` | the filter value at stake |
| `retained` | the value the fused record kept |
| `counted` | `true`: counted on a disputed value; `false`: left out while a source holds `matched` |
| `sources` | the `_conflicts` entries (`src`, `value`), in ledger order |

`lm ask -o json` prints the same fields, plus `columns`.

- `rows` is the raw result, one `{column: value}` map per row, at most 500 rows.
- Execution is read-only, limited to your tenant, with a statement timeout.

### How refusals surface in the payload

A [guard](../transparency/ask-honesty.md) refusal arrives as a normal `200` analytic response: the
refusal message as `answer`, `rowCount: 0`, `rows: []`, and `sql` holding the refused query (`""`
when the model abstained before writing any):

```json
{
  "answer": "Je ne peux pas répondre à partir des données disponibles pour ce périmètre — la question ne correspond pas au schéma déclaré.",
  "sql": "",
  "rowCount": 0,
  "rows": [],
  "dataAsOf": null,
  "otherDoors": [],
  "disputed": [],
  "disputedTruncated": false,
  "abstained": true,
  "understoodAs": null
}
```

`dataAsOf` is the end of the scope's last completed run; `null` when there is none, and on refusals.

`otherDoors` names the doors that *can* answer when this one abstains, as tokens; `[]` otherwise:

| Token | The scope… | Where to go next |
|---|---|---|
| `lifecycle` | declares a [`kind: Workflow`](../apps/workflows.md), read by the semantic chat | `lm chat` |
| `conflicts` | fuses an entity whose disagreements are in its ledger | `lm conflicts list` |

`lm ask` prints the command as a `try:` line — see
[Abstention exit](../transparency/ask-rules.md#2-abstention-exit-the-models-honest-i-cant).



- The `answer` names no command: map each token to the gesture your surface has, and skip a token
  you do not recognise.
- A token is offered only when the question is about what that door holds (sources that disagree; a
  lifecycle, its stages, a blocked document); an unrelated refusal carries none.

**Branch on `abstained`, never on the words.** The fixed sentences (abstentions, refusals, the
zero-row caveat) follow `lang`, in English or French; anything else gets English. `abstained` is
`true` when the door declined to answer:

- the model's own "cannot answer", or a scope with no schema;
- rejected SQL, or a table, column or value the scope does not declare;
- a filter that matches several rows, or a query the database refused;
- a query that returned no rows;
- a phrased answer withheld for a figure the rows do not back;
- a model that could not write the answer (the query ran: `rows` and `sql` are the result).

Treat `rowCount: 0` as *"read the answer text"*, never as *"there are none"*.

Refusals that happen **before** the ask — a scope-bound user naming a scope they don't hold —
are HTTP errors instead: `403 {"error":"scope not granted"}` (see
[scopes](scopes.md#binding-users-to-scopes-opt-in)).

### Follow-up questions

Send the previous question in `previous`; `lm ask` has no equivalent. The hub keeps nothing between
calls: your client holds the thread, one sentence long. Send the response's `understoodAs` back as the
next `previous`; when it is absent (a first question), send the question as asked.

Played on a hub whose scope `catalogue` holds eight articles (`lm csv ingest ./articles.csv --scope catalogue`). Only
four fields of each response are shown:

```console
$ curl -s https://your-hub.example.com/api/rag/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"question": "Et HAWARD ?", "previous": "Donne-moi les références dont la description contient Badu.",
         "mode": "analytic", "scope": "catalogue", "lang": "fr"}' | jq '{answer, sql, understoodAs, abstained}'
{
  "answer": "Il y a 2 références dont la description contient HAWARD :\n\n1. 4100.000.001\n2. 4100.000.002\n\nTotal de références : 2",
  "sql": "SELECT reference FROM \"ext\".\"articles\" WHERE description ILIKE '%HAWARD%'\nLIMIT 500",
  "understoodAs": "Donne-moi les références dont la description contient HAWARD.",
  "abstained": false
}
$ curl -s https://your-hub.example.com/api/rag/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"question": "Et seulement celles de la catégorie Pompes ?", "previous": "Donne-moi les références dont la description contient HAWARD.",
         "mode": "analytic", "scope": "catalogue", "lang": "fr"}' | jq '{answer, sql, understoodAs, abstained}'
{
  "answer": "Les références de la catégorie Pompes dont la description contient HAWARD sont : 4100.000.001.",
  "sql": "SELECT reference \nFROM \"ext\".\"articles\" \nWHERE categorie = 'Pompes' AND description ILIKE '%HAWARD%'\nLIMIT 500",
  "understoodAs": "Donne-moi les références de la catégorie Pompes dont la description contient HAWARD.",
  "abstained": false
}
$ curl -s https://your-hub.example.com/api/rag/chat -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"question": "Et le deuxième, il coûte combien ?", "previous": "Donne-moi les références dont la description contient HAWARD.",
         "mode": "analytic", "scope": "catalogue", "lang": "fr"}' | jq '{answer, sql, understoodAs, abstained}'
{
  "answer": "Je n'ai pas pu rattacher cette question à la précédente. Posez-la en entier, avec la valeur ou le nom auquel elle renvoie.",
  "sql": "",
  "understoodAs": null,
  "abstained": true
}
```

- `understoodAs` is present only when the question was resolved against `previous`. It is `null` for a standalone
  question and on that refusal. After a refusal, keep the `previous` you had: the user usually asks again in other
  words.
- The answer is phrased for `understoodAs`, not for the follow-up alone. Every guard reads `understoodAs`.
- `understoodAs` is never longer than 1000 characters, so it always fits the next `previous`.
- A follow-up costs one more model call, except a bare *« Et X ? »* over a question holding one value, which the hub
  resolves itself. What a resolution may and may not do, and when it is refused:
  [ask honesty](../transparency/ask-rules.md#a-question-that-follows-the-previous-one).
- A question may open by addressing the chat by name, *« lumnik, combien d'articles… ? »*. The address is removed
  before anything reads the question. A question that is nothing but the address (*« lumnik, »*) is kept as written.

## Search

```
POST /api/platform/rag/search     (lm_integrator)        lm rag search QUERY --top-k N
```

The retrieval layer under the semantic chat: the nearest chunks, no model answer.

| Field | Type | Meaning | Default |
|---|---|---|---|
| `query` | string | the search text — **required** (blank → 400) | — |
| `topK` | integer | number of hits, 1 to 50 (`lm rag search` asks for 5). Outside → `400 {"error":"topK must be between 1 and 50"}` | 10 |
| `corpus` | string | restrict to one corpus (source) | all |
| `scope` | string | restrict to chunks carrying this scope tag; `"all"`/blank = no tag filter (for a scope-bound user, rewritten onto their granted set — never unfiltered) | none |

```json
{
  "results": [
    {
      "rowHash": null,
      "ref": "workflow/acme-order-lifecycle",
      "content": "Workflow acme-order-lifecycle — cycle de vie de la colonne status de la table connector.t_acme_desk_orders (declared by the integrator). États: BROUILLON (initial, « Brouillon »), EN_COURS (« En préparation », alias: en cours, encours), TERMINE (terminal, « Terminée », alias: fini), ANNULE (terminal, « Annulée »). Transitions autorisées: BROUILLON->EN_COURS, BROUILLON->ANNULE, EN_COURS->ANNULE, EN_COURS->TERMINE.",
      "similarity": 0.5389897637416403,
      "metadata": {
        "scope": "desk",
        "table": "connector.t_acme_desk_orders",
        "provenance": "declared"
      }
    }
  ]
}
```

One hit shown. `rowHash` is `null` on a workflow's chunk; on a row's chunk, `lm data row HASH` shows the row. `similarity` runs from 0 to 1. The same scope binding applies: a
scope outside a bound user's grants → `403 {"error":"scope not granted"}`.

## Scopes

```
GET /api/platform/rag/scopes      (lm_integrator)        lm scopes
```

The scopes with indexed chunks in this tenant ([a scope is declared on the
connector](scopes.md#a-scope-is-declared-on-the-connector)):

```json
[
  {"scope":"demo","connectors":0,"chunks":1,"lastIndexed":"2026-10-24T19:59:25.582921Z"},
  {"scope":"desk","connectors":0,"chunks":2,"lastIndexed":"2026-10-24T21:35:19.934093Z"},
  {"scope":"retail","connectors":0,"chunks":1,"lastIndexed":"2026-10-24T19:55:42.964543Z"}
]
```

`lastIndexed` is an ISO instant, or `""` if unknown. A scope-bound user sees **only their
granted scopes** in this list.

### Your default scope

`GET` / `PUT /api/platform/users/me/scope` (`lm scope` / `lm scope set TAG`) read and set the default
the chat doors use when a request names no scope (`{"defaultScope": "compta"}`; empty string clears it). Both answer
`404 {"error":"no user record on this hub for user_id N"}` when your token names a hub user id
that has no row on this hub ([BYO IdP](../security/byo-idp.md#troubleshooting)).

## Corpora

```
GET /api/platform/rag/corpora     (lm_integrator)
```

Lists the corpora with indexed chunks (no `lm` command; `:corpus` in `lm chat` picks one). A corpus is usually a connector source, but not
always: applying a [`kind: Workflow`](../apps/workflows.md) indexes its declared lifecycle
under `workflow:<name>`, which is backed by a manifest rather than an ingest run.

```json
[
  {
    "name": "oneshot:ext.stock_count",
    "chunkCount": 1
  },
  {
    "name": "workflow:acme-delivery-lifecycle",
    "chunkCount": 1
  },
  {
    "name": "workflow:acme-order-lifecycle",
    "chunkCount": 1
  },
  {
    "name": "workflow:commande-lifecycle",
    "chunkCount": 1
  }
]
```

This listing is scope-bound too: a scope-bound user sees only corpora carrying at least one
granted scope tag — and an **untagged** corpus belongs to no scope, so it is invisible to a
bound user.

## Schema (admin)

```
GET  /api/platform/rag/schema?scope=<tag>        (lm_admin)    lm source schema --scope TAG
POST /api/platform/rag/rediscover?scope=<tag>    (lm_admin)    lm source rediscover --scope TAG
```

`schema` returns the scope's **schema card** — the exact tables/columns (with business labels,
descriptions, examples and declared value-domains) the analytic mode is allowed to query.
`404` if no card exists for the scope; run discovery first. `rediscover` rebuilds the card from
the ingested tables and returns a summary:

```json
{ "scope": "clients", "tableCount": 3, "columnCount": 41, "enriched": true, "sourceType": "connector" }
```

Both are served by the hub itself, so they exist in the Open edition and with the AI switched off.

## Status and reindex (admin)

```
GET  /api/platform/rag/status     (lm_admin)    lm rag status
POST /api/platform/rag/reindex    (lm_admin)    lm rag reindex
```

```json
{"indexed": true, "chunks": 8, "indexedAt": "2026-10-24T21:32:45.842950Z", "provider": "ollama-nomic", "embeddingModel": "nomic-embed-text"}
```

- `provider` and `embeddingModel` are the live embedding model's; `"none"` when there is none.
- `reindex` rebuilds the **platform corpus** (manifests, API, hooks) and returns
  `202 {"status": "reindex_started"}` at once; poll `status`. Chunks of ingested rows are not
  touched: they are written at ingestion and erased with their connector
  ([GDPR → erasure](../security/gdpr.md#the-rights-in-practice)).

## Errors

| Status | Body | When |
|---|---|---|
| `400` | `{"error": "question is required"}` (chat), `{"error": "query is required"}` (search), `{"error": "scope is required"}` (schema) | missing/blank required field |
| `400` | `{"error": "previous must be at most 1000 characters"}`, `{"error": "previous is only read in analytic mode"}` | a [follow-up](#follow-up-questions) `previous` over the bound, or sent without `mode: analytic` |
| `404` | no body | chat, search, scopes or corpora on lumnik Open, or with the AI switched off (`lm` prints `Error: API error 404 (no details from the server)`) |
| `401` | plain text `authentication required` + `WWW-Authenticate: Bearer` header | no/invalid token on `/api/platform/*` — note: **not** JSON |
| `401` | `{"error": "no tenant in security context"}` | analytic ask/schema call whose token resolves no usable tenant |
| `403` | plain text `missing role lm_integrator` | authenticated but not an integrator, on `/api/platform/*` |
| `403` | `{"error": "scope not granted"}` | a scope-bound user asked outside their granted set — the [uniform refusal](scopes.md#binding-users-to-scopes-opt-in) across the whole read surface |
| `404` | `{"error": "No schema card found for scope: ..."}` | `GET .../schema` on a scope with no discovered schema card yet |
| `409` | `{"error": "no schema discovered for scope '...' — apply an entity or run discovery first", "hint": "lm entity apply registers a schema for the scope; ask again after it lands"}` | analytic ask on a scope with no schema card at all — a fresh install, or a scope with no `kind: Entity` applied yet |
| `429` | `{"error": "rate limit exceeded"}` + `Retry-After: <seconds>` header | bucket exhausted — honor `Retry-After` before retrying |
| `503` | `{"error": "...", "hint": "..."}` | the model or embedding service is missing or unreachable — a deployment problem, not a caller problem. (An analytic ask whose SQL already ran is not a `503` when only the answer could not be written: it is a `200` with `abstained: true`, the rows and the SQL.) |

Rate limits are per **(bucket, tenant)** in a fixed 60-second window. The chat and search
endpoints ride three buckets — `user-chat`, `platform-chat`, `platform-search` — each
configurable via `lumnik.ratelimit.<bucket>.rpm` (fallback `lumnik.ratelimit.default.rpm`,
code default 60; `0` disables). See [rate limits](../operations/rate-limits.md).

## See also

- [Scopes](scopes.md) — what a scope is, and the opt-in per-user binding
- [When Ask refuses](../transparency/ask-honesty.md) — the refusal contract behind analytic mode
- [The PWA](../apps/pwa.md) — the first consumer of this API
- [Deploy](../deploy.md) — issuer, `lm login`, pointing `lm` at the hub
