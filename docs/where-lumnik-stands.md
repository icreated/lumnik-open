# Where lumnik stands

**beta.** Nobody outside the lumnik team has run lumnik in production yet.

The latest release (`v1.8.0`) added:

- [entity resolution](entities-resolution.md), one identity across systems that never shared a key;
- [managed exports](apps/exports.md), lumnik's first outbound path;
- [queued runs](operations/upgrade.md#turning-on-the-run-queue);
- views read page by page beyond 1 000 rows ([Views](apps/views.md#rendering)).

## What is deliberately not there

Each line is a decision, declared on the page of its surface and gathered here.

| Surface | What is not there | Declared in |
|---|---|---|
| **GraphQL** | mutations, subscriptions, schema-introspection discovery, persisted queries, query batching, per-endpoint header overrides, `on_error: warn` | [GraphQL — Not yet supported](connectors/graphql.md#not-yet-supported) |
| **REST** | `mode: api_cursor` — declaring it behaves like `full`; per-endpoint headers, which belong at `spec.headers` | [REST](connectors/rest.md) |
| **SOAP** | SOAP **1.1** envelopes only — a service that requires SOAP 1.2 (`application/soap+xml`) is refused | [SOAP](connectors/soap.md) |
| **Kafka** (Pro) | JSON only — no Avro, Protobuf or Schema Registry; one topic per connector, no filtering by key or header; no mapping block, so no hooks at ingestion; no pre-flight test; SASL/SCRAM, mTLS and OAuth | [Kafka — Limitations](connectors/kafka.md#limitations) |
| **Transformers** | `validate-row` after `split-row`, and `aggregator` on a `csv-file` run — both refused at `lm apply`, with the arithmetic reason | [Transformers](connectors/transformers.md) |
| **Events** | REST-, GraphQL- and SOAP-ingested tables are event-silent — the respiration rides jdbc, CSV and kafka ingestion | [How lumnik breathes](respiration.md) |
| **Chat** | declared status aliases feed the dormancy watch and the chat's background knowledge, **not** the chat's SQL or a view's filters — the chat matches the literal values in the column | [How lumnik breathes](respiration.md) |
| **Workflows** | the transition journal's retention is unbounded, and the dormancy sweep costs the database a full pass over the source table every tick | [Workflows](apps/workflows.md) |
| **Exports** | CSV only, one entity per export, `mode: snapshot` only (no delta, no resume); SFTP and S3 only; four formatters, a row filter with `equals`/`in` only; no back-fill of missed slots, no automatic retry | [Exports — Not in this version](apps/exports.md#not-in-this-version) |
| **Search** | a row deleted straight from an ingested table — or by `lm endpoint purge` on a `connector`-schema hybrid target — stays searchable until the index is rebuilt | [SearchIndex — Rows deleted directly in the database](search/search-indexes.md#rows-deleted-directly-in-the-database) |
| **SCIM** (Pro) | no `/Groups`; no bulk, sorting, etags or `changePassword`; no pagination beyond the two `eq` filters; not the full PATCH path grammar; no `/Schemas` or `/ResourceTypes` | [SCIM — Not supported](security/scim.md#not-supported) |
| **Identity** | SAML-only IdPs are not supported by direct trust — front them with a broker and point lumnik at the broker | [Bring your own IdP](security/byo-idp.md) |
| **Encryption** | rotating the **master** key. Rotating one secret's value is supported, and stamps `rotated_at` | [RLS and encryption](security/rls.md) |

Kafka and SCIM are in the Pro build only: see [The two editions](editions.md).

## What is not finished

Known, and not yet closed — **you may meet them**:

- **A few `409` refusals do not name what holds the name** — e.g. which integration took it.
- **The live event tail can miss an event** when transactions run long. Webhooks and the inbox do not.
- **`lm secret rm` frees the name, but the stored row stays**, encrypted.

## When `beta` goes away

When **What is not finished** is empty and someone outside the lumnik team has run lumnik in
production. To be that first outside run, start with [the guided path](parcours.md).

## See also

- [The two editions](editions.md) — what each build contains
- [Parcours — the guided path](parcours.md) — the three half-days
- [How lumnik works](how-it-works.md) — the mechanism behind all of it
