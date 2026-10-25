# GraphQL

> Ingest a GraphQL API by putting the query in the manifest — a request shape on the REST
> engine (`connector_type` stays `rest-generic`), with Relay pagination required.

| | |
|---|---|
| **Type** | `rest-generic` (shape `graphql`) |
| **Protocol** | GraphQL over POST |
| **Target** | `ext.*` hybrid |
| **Schedulable** | cron |
| **Edition** | Open |

Auth, retry, rate limiting and the hybrid JSONB + typed storage are all shared with
[REST](rest.md). This page covers what is different: the query, Relay pagination, and what
happens when the server answers `200` and still says no.

## Quickstart

The screens below read [the demo API](rest/demo-api.md) that ships with the repo — the same one
the REST page uses, under its `POST /graphql` shape. Start it, and put its key in the registry:

```bash
python3 docs/connectors/rest/demo/acme-api.py
lm secret set ACME_API_KEY        # demo-key
```

```yaml
--8<-- "docs/connectors/rest/demo/70-graphql-relay.yaml"
```

```
$ lm apply -f docs/connectors/rest/demo/70-graphql-relay.yaml
Applied connector "acme-graphql" — 1 endpoint
$ lm source rest test acme-graphql
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  POST    200     500    ok
```

The pre-flight sends the request a run sends (`POST`): one page, 500 nodes, nothing written.

```
$ lm connector run acme-graphql
run scheduled: f26868f0-641e-471a-8719-e6c4c2957fee
$ lm run list --connector acme-graphql
ID        CONNECTOR     ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
f26868f0  acme-graphql  partners  Completed  2400  2400  0        0             2026-10-19 01:45  2026-10-19 01:45
```

## The manifest

| Field | Where | Required | Notes |
|---|---|---|---|
| `graphql_path` | `spec` | no | The single POST endpoint; requests go to `base_url + graphql_path` (default `/graphql`) |
| `kind: graphql` | endpoint | no | An endpoint carrying a `query` is treated as GraphQL either way — the examples declare it for readability |
| `query` | endpoint | yes | The query document; declare the cursor variable it expects (e.g. `$after`) |
| `variables` | endpoint | no | Static variables sent with every request; the relay cursor is injected on top |
| `pagination` | endpoint | yes | **Hard rule:** `kind: relay` with `cursor_binding: variable` — anything else is refused at apply |
| `cursor_var` | pagination | yes | The variable that receives `endCursor` (e.g. `after`) |
| `cursor_path`, `has_more_path`, `response_path` | pagination | yes | JSONPaths to `endCursor`, `hasNextPage` and the node list |
| `target` | endpoint | yes | The same hybrid target as REST: `table`, `unique_key`, `promote` |
| `incremental` | endpoint | no | `mode: watermark` sends the mark as the variable named by `param` — see [Incremental](#incremental-the-mark-rides-in-a-variable) |

`path` and `method` are implicit — the request is always a POST to `graphql_path`.

- In the query the variable carries its sigil (`$after`); `cursor_var: after` names it without.
- There is no page-size field: page size is in the query itself (`first: 500` above).

- The rows that land are the nodes, without the `edges` wrapper.
- A graphql endpoint must declare `kind: relay` with `cursor_binding: variable`. `lm apply`
  refuses anything else, and shows the block to write.

## The two ways a GraphQL server says no on HTTP 200

A GraphQL server may answer `200` with an `errors` array, or with `"data": null`. Both are
refused — by `lm source rest test` before any run:

```
$ lm source rest test acme-graphql-errors
ENDPOINT      METHOD  STATUS  ITEMS  VERDICT
errors-array  POST    200     0      GraphQL response carried 1 error(s); first: Cannot query field 'boom' on type 'Query'
null-data     POST    200     0      GraphQL response has no errors and null/absent data (malformed envelope): {"data": null}
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
```

and by a run, with the same message:

```yaml
--8<-- "docs/connectors/rest/demo/80-graphql-errors.yaml"
```

```
$ lm run list --connector acme-graphql-errors
ID        CONNECTOR            ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2ca12c44  acme-graphql-errors  null-data     Failed  0   0    0        0             2026-10-19 01:45  2026-10-19 01:45
84f519e9  acme-graphql-errors  errors-array  Failed  0   0    0        0             2026-10-19 01:45  2026-10-19 01:45

$ lm run get 84f519e9
ID        CONNECTOR            ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
84f519e9  acme-graphql-errors  errors-array  Failed  0   0    0        0             2026-10-19 01:45  2026-10-19 01:45
✖ run failed: GraphQL response carried 1 error(s); first: Cannot query field 'boom' on type 'Query'
```

Nothing is ingested and nothing is dead-lettered. Fix the query, or the permission it needs,
and run again.

## Headers

`spec.headers[]` applies to every request, as for [REST request headers](rest.md#request-headers).

## Incremental: the mark rides in a variable

On a graphql endpoint, `incremental.param` names a **GraphQL variable**. The stored mark is
sent in the request's `variables`, on every page of the run:

```yaml
endpoints:
  - id: partners
    kind: graphql
    query: |
      query($after: String, $updated_since: String) {
        partners(first: 500, after: $after, updatedSince: $updated_since) {
          edges { cursor node { id name updated_at } }
          pageInfo { hasNextPage endCursor }
        }
      }
    incremental:
      mode: watermark
      param: updated_since              # the variable, without the $
      watermark_path: "$.updated_at"
```

- The query must declare the variable. `lm apply` refuses a `param` the query does not declare.
- Declare it nullable (`String`, not `String!`). The first run has no mark yet and sends none.
- The server filters on it. The [demo API](rest/demo-api.md) reads `$updated_since` and
  returns the partners changed at or after it.

The demo's `acme-graphql` with this `incremental` block (and `updated_at` added to the node),
run twice:

```
$ lm run get 0e15e816
ID        CONNECTOR         ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED             EVALUATED
0e15e816  acme-graphql-inc  partners  Completed  2400  2400  0        0             2026-10-25 11:16  2026-10-25 11:16  yes
$ lm run get beb5aab8
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED             EVALUATED
beb5aab8  acme-graphql-inc  partners  Completed  2   2    0        0             2026-10-25 11:16  2026-10-25 11:16  yes
```

The second run asks only from the mark. It reads a little before it, by the length of the
previous run: [a change during a run](rest.md#a-change-during-a-run).

## Not yet supported

Mutations, subscriptions, schema-introspection discovery, persisted queries, query batching,
per-endpoint header overrides.

## How to…

### Send static variables with every request

```yaml
endpoints:
  - id: partners
    kind: graphql
    variables: { locale: fr-FR }   # merged into every request
```

**Result:** every request carries `locale: fr-FR` in its GraphQL `variables`, beside the relay
cursor.

### Read a second collection from the same API

Add a second endpoint with its own query, its own `pagination` paths (they name the collection:
`$.data.<collection>.pageInfo.endCursor`) and its own table. One connector, one API, one
credential; the ledger records one run per endpoint.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails though the HTTP status was 200 | an `errors[]` array, or `data: null` with no errors | read the message in `lm run get` — it carries the server's first error verbatim |
| Run completed with `IN` 0 | `response_path` does not match the envelope | `lm source rest test <connector>` prints the item count it read from one page |
| `cursor_binding: variable` refused at apply | that binding is only valid on `relay` on a graphql endpoint | use `query` or `header` binding on a plain REST endpoint |
| Pagination never ends | `cursor_path` resolves to a value that does not change | it must point at `pageInfo.endCursor`, the opaque cursor the server hands back |

## See also

- [REST](rest.md) — the engine underneath (auth, retry, rate limit, storage layout)
- [Pagination](rest/pagination.md) — `relay`, and the binding rule above
- [The demo API](rest/demo-api.md) — the source these screens read
- [Overview](index.md)
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
