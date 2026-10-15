# kind: SearchIndex — declare what Search may see

> A `kind: SearchIndex` manifest is an **allowlist**: the columns of **one canonical entity** that
> may leave PostgreSQL for an optional Search provider. No declaration, no indexing. Nothing is
> inferred from the data, and a column added to a source later never reaches the index on its own.

| | |
|---|---|
| **Kind** | `SearchIndex` (`apiVersion: search.lumnik.io/v1`, strictly checked) |
| **Source** | one [entity](../entities.md), never a raw `connector.t_*` table |
| **Who may see a hit** | whoever may read the entity: its `scopes`, resolved at query time |
| **Apply / delete** | `lm_integrator` |
| **List** | `lm_user`, filtered by the entity's scopes |
| **Provider needed?** | no — without one, a declaration is stored configuration and nothing more |

## Quickstart

```yaml
--8<-- "docs/search/search-indexes/10-customers.yaml"
```

```bash
curl -X POST "$LUMNIK_URL/api/search-indexes/apply" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/yaml" \
  --data-binary @docs/search/search-indexes/10-customers.yaml
# → {"name":"customers","entity":"AcmeCustomer","status":"valid"}
```

The same door from the CLI, which is how a scenario script applies it after the entity:

```bash
lm search apply -f docs/search/search-indexes/10-customers.yaml
# → Applied search index "customers" → entity AcmeCustomer (valid)
```

## Fields: searchable, returned, faceted

Every declared field is **returned** with a hit. `match` also makes it **searchable**; `facet: true`
also makes it a facet. So:

- **`searchable ⊆ returnable`** and **`facet ⊆ returnable`** always hold. A field cannot be searched
  without being shown, so the index can never answer "does anyone earn 85 000?" about a column the
  caller cannot see.
- A field **without `match` is return-only**: it rides along with the hit (a status, a date) but is
  never matched against.
- At least one field must be searchable.

```yaml
--8<-- "docs/search/search-indexes/20-orders-return-only.yaml"
```

| `match` | Meant for |
|---|---|
| `exact` | codes, identifiers, closed values |
| `text` | free text, analysed in `spec.language` |
| `fuzzy` | names with typos, accents and spelling variants |
| `prefix` | autocomplete, order numbers typed partially |

`phonetic` is refused as *not supported yet*: it returns once a provider proves it on French and
German data.

`spec.language` is one of `simple` (the default), `english`, `french`, `german`, `italian`,
`spanish`, `dutch`, `portuguese`.

## Choosing a title

A hit shows the name of its entity as its heading, which tells a product from a customer but not one product
from another. Name the field people recognise an item by, and the métier app promotes it to the heading:

```yaml
--8<-- "docs/search/search-indexes/30-products-title.yaml"
```

```
$ lm search apply -f products.yaml
Applied search index "products" → entity Product (valid)
```

In the app, a result then reads:

```
2195.300.037
Product · product_id=1024944
description  Pompe Badu Resort 30 2cv Tri
family       Pompes
```

- **`titleField` is optional.** Without it every result keeps the entity name as its heading, with the key under it.
- **It names one of the declared fields**, searchable or return-only. Any other name is refused when you apply.
- **It is never guessed.** The first field of the list is not the title: a status or a date would become a
  heading by accident. Say which one, or say nothing.
- **The entity stays visible** under the heading, because one search may return several entities.
- **An item with no value in that field** shows the entity name for that item only.

Declaring or changing a `titleField` is a re-apply like any other, so the index is re-projected
([how fast an index catches up](#how-fast-an-index-catches-up)). The heading changes at the next search once
the field is already in the index; when you add the title column to `spec.fields` in the same apply, results
keep the entity name as heading until the projection has caught up.

## Human names for fields

A result names its fields as the columns are called: `credit_limit`, `category_description`. Give a column a `label`
in its [entity's `columns:` block](../entities.md#the-columns-grounding-block) — the same label a view already
shows — and Recherche uses it, with nothing to declare in the index:

```yaml
columns:
  name:
    label: "Raison sociale"
  credit_limit:
    label: "Encours autorisé"
```

In the app, a result then reads:

```
Raison sociale  Acme Industries
Encours autorisé  50000
city  Lyon
```

- **The labels are the entity's.** Change one by re-applying the entity; it shows at the next search once the hub
  has taken the new entity in.
- **A column without a label keeps its name** (`city` above), so you can label what matters first.
- **They show in three places**: on each result, in the record sheet it opens (every column of the record, not only
  those the index returns, and the columns reported as divergent), and on the filter chips offered with the results.
- **A filter chip is labelled only when the entities in the page agree.** If two entities label the same column
  differently, the chip keeps the column name rather than pick one.
- Labels are display names only: searching and filtering still use the column names.

## Linking a result to your ERP

A métier user who finds an article by its reference often needs the same item in the system that owns it. Declare
the address once, on the field people see:

```yaml
--8<-- "docs/search/search-indexes/40-products-erp-link.yaml"
```

```
$ lm search apply -f products.yaml
Applied search index "products" → entity Product (valid)
```

In the app, the reference of each result becomes a link: tapping it opens
`https://erp.example.com/articles/2195.300.037` in a new tab, and does not also open the record sheet. The rest of
the card behaves as before.

- **`{field}` is the value of that field on the result.** It must be one of this index's `spec.fields`, not
  necessarily the linked one. Literal text around it is kept. Nothing else is ever substituted.
- **One link per field**, at most 16, each on a field the index declares. Nothing but `field` and `url` is allowed.
- **The address follows the same rules as a [view's external link](../apps/views.md#linking-views)**: `https://`
  only, the host written out in full, the page opened in a new tab. Special characters in the value are made safe
  for the address. **The other system receives the value** — declare only systems you trust with it.
- **A result with no value for any `{field}` of the address has no link**, and the field shows as plain text.
- **A link on the title field** makes the heading itself the link.
- Linking to another view from a result is not available yet.

A mistake is refused when you apply, naming the key — here an address that mentions `{ghost}`, which is not a field of the index:

```
$ lm search apply -f products.yaml
Error: API error 400: spec.links[0].url: {ghost} is not one of spec.fields
```

Adding or changing a link is a re-apply like any other, but the link does not wait for the index to catch up: it
shows at the very next search.

## Columns joined from a lookup table

A [joined column](../entities.md#fuse-or-join) can be a field like any other, on a `match:` entity or on one
that declares `resolution:`. The index follows the lookup table as it follows the entity's own sources:

- the first lookup row for a key makes the rows holding that key appear with the joined value;
- one more row of **different content** for the same key makes the key ambiguous: the joined value goes
  empty on those rows, it is never one of the two;
- ingesting the same lookup row again changes nothing.

A lookup change re-projects every row holding the changed key, through each source that carries the
joined-on column, so a big lookup loaded for the first time re-projects every row that joins it — see
[how fast an index catches up](#how-fast-an-index-catches-up).

On an entity with `resolution:` the document is keyed by the resolved identity: a lookup change re-projects
every identity that has at least one decided source row holding the key. A source row whose identity is
undecided is not a document yet — its decision, later, projects it with the lookup as it then is. A
document's freshness (`asOf`) also waits for the lookup to have been read, not only for the resolution
pass to have covered the run that wrote it.

## What is refused, and why

| Refused | Because |
|---|---|
| `spec.scope` / `spec.scopes` | scopes are inherited from the entity; a SearchIndex can never widen who sees its data |
| `spec.table` / `spec.source` | only a canonical entity has a stable identity and scopes; a raw table has neither |
| a field that is not a canonical column of the entity | the allowlist names canonical columns only (a source column name is not one) |
| `tenant_id`, `_entity_id`, `_conflicts`, `_resolution`, `_handle` | hub bookkeeping, never business data |
| a `metadata.name` that is not a lower-case slug, or `apply` | the name is used as written in `DELETE /api/search-indexes/{name}` |
| an apply over an entity whose scopes you do not **all** hold | `403 {"error":"scope not granted"}` — holding one of two is escalation |
| a second index over an entity another SearchIndex already indexes | `409` naming that index — **one SearchIndex per Entity**: two would return every hit twice, with scores that cannot be compared |

A re-apply of an existing name also needs every scope of the entity the name **currently** indexes,
so nobody can take over a declaration they could not have written.

Searching one column two ways does not take two indexes: give each field its own `match` mode inside the
one index. To change what an entity's index declares, re-apply it under its own name — or delete it first.
An install that already held two indexes over one entity when it upgraded keeps the oldest **valid** one
(the oldest, if none is valid) and has the others deleted with the usual purge (see [Deleting](#deleting-and-the-purge-it-leaves-behind)).

## Deleting — and the purge it leaves behind

```bash
curl -X DELETE "$LUMNIK_URL/api/search-indexes/customers" -H "Authorization: Bearer $TOKEN"
# → 204
```

From the CLI: `lm search rm customers`.

A delete removes the declaration **and records a purge obligation in the same transaction**. The
obligation outlives the declaration: documents already projected into a Search backend are purged
even if the provider was down, absent, or installed only later. Re-applying the same name afterwards
is a new declaration; it never cancels the pending purge.

**The purge is asynchronous.** Until it has completed, the deleted index's documents can still come back from
`GET /api/search`: the provider answers from what is projected, and does not ask whether a declaration
still exists. It does not complete while the projection worker or the provider is down. Three consequences:

- A query made right after `lm search rm` can still return that index's hits. Scopes are unaffected: they
  belong to the entity and are checked at query time, so this is stale data, never data a caller may not read.
- Replacing an index by deleting it and declaring a new one over the same entity can show both for that
  window: duplicate hits, and scores shaped by the old definition. Wait for the empty answer, or re-apply
  the same name instead — a re-apply rebuilds in place.
- A client that must be sure a deleted index no longer answers should poll for the empty answer, not assume it.

## Rows deleted directly in the database

Search follows what ingestion writes. If someone removes rows straight from an ingested table — a `DELETE`
on `connector.t_*`, bypassing lumnik — Search is not told: those rows stay searchable until the index is
rebuilt. Ingestion never deletes rows, so this concerns two gestures: a direct `DELETE` in the database, and `lm endpoint purge` on an endpoint whose hybrid target lives in the `connector` schema (for example the demo's `connector.cust_siebel`). Either way, Search is not told.

```bash
lm search rebuild customers --wait
```

Use `--wait`. Without it the command returns as soon as the hub has *claimed* the run, and a search made
right away can still return the deleted row; with it, the command follows the rebuild to its end
(`ready`, `failed` or `cancelled`). Only `ready` means the deleted row is no longer searchable.

## When the entity changes

Entities are deployment-global. When one is re-applied without a column that a SearchIndex uses,
every tenant's SearchIndex over it turns **`invalid`**, with the reason in the list:

```json
{"name":"customers","entity":"AcmeCustomer","language":"french",
 "status":"invalid","invalidReason":"spec.fields[2].name: 'city' is not a canonical column of entity 'AcmeCustomer'"}
```

The entity apply itself is never refused because of Search. An invalid declaration takes no part in
Search until it is **re-applied** successfully — restoring the column alone does not revalidate it.

### An entity applied before Search

An index reads the entity's compiled view, and that view only gets the column the index needs
(`_canonical_key`) when the entity is **applied**. An entity applied by a hub older than the one that
introduced Search therefore cannot be indexed yet. Declaring the index still answers `valid`; the first
build fails, and `lm search status NAME` says why:

```
Status            degraded
Last error        IllegalStateException: entity 'AcmeCustomer' cannot be indexed: its view has no "_canonical_key" column, because the entity was applied by a hub older than the one that added it — apply it again (lm entity apply -f <manifest>), then rebuild
```

Apply the entity again — the same manifest, no other change — then rebuild:
`lm entity apply -f <manifest>` and `lm search rebuild NAME --wait`. Do it once for each entity you index
that was applied before the upgrade. `lm search query` over such an index says the build failed (and why),
not that the index "is not built yet".

## How fast an index catches up

After a bulk load the index trails the data until the hub has re-projected every changed row. The rule it follows:

- A batch is **at most 500 documents**.
- A document the provider **refuses** does not hold the index back. The hub first sends the batch in one call; if that call fails it retries mark by mark, leaves the refused document pending, and carries on with the ones behind it. It stops for the tick after **3 failures in a row** in that mark-by-mark pass (a success resets the count), so a provider that is down costs **four** calls per index per tick (the batch call, then three), not one per document. The index shows `degraded` with the provider's message for as long as a refused document is pending, and its freshness claim (`asOf`) does not move past it. Read the message with `lm search status NAME`, then: if the **source row** is what the provider rejects, correct it and ingest it again — the change re-marks the document and the next tick retries it; if the **index declaration** is the cause, correct it, re-apply it with `lm search apply -f <manifest>`, then `lm search rebuild NAME --wait`. Rebuilding without fixing the cause fails on the same document again. The limit is `lumnik.search.projection.catchup-consecutive-failure-limit` (environment variable `LUMNIK_SEARCH_PROJECTION_CATCHUP_CONSECUTIVE_FAILURE_LIMIT`), at least `1`; with `1`, the pass stops at the first failed mark.
- Every eligible index gets **one attempt on each scheduler tick**, nominally once per minute. A successful attempt processes at most 500 documents. An attempt can come to nothing: another replica may hold the index's lock, the batch may fail, or a full rebuild may be owed; and a tick that overruns the minute makes the next one be skipped.
- While the minute's time budget lasts, the indexes that are still behind get **further batches, taking turns**: one batch each per round, so a very large index never takes another's turn.
- Further batches stop when an index is up to date, at that index's first failure of the minute, or when the budget is spent. The budget stops a new batch from **starting**; it never cuts one short, so a minute can run a little over.
- The budget is `lumnik.search.projection.catchup-budget` (environment variable `LUMNIK_SEARCH_PROJECTION_CATCHUP_BUDGET`), a duration with a unit. The default is `30s`. With `0s`, no additional batches run during a tick.
- An index with more than **5,000 pending changes** is not caught up batch by batch: it is **rebuilt**, as after re-applying its declaration. Reading one batch costs about as much as reading the whole entity, whatever the batch holds, so past a few thousand changes one rebuild costs less than the batches it replaces. While it rebuilds, the index keeps answering from its current content; changes made after the rebuild started are caught up after it. An index whose last attempt failed is caught up batch by batch until one succeeds. The threshold is `lumnik.search.projection.rebuild-backlog-threshold` (environment variable `LUMNIK_SEARCH_PROJECTION_REBUILD_BACKLOG_THRESHOLD`), a number of pending changes. With `0`, a backlog never causes a rebuild.
- A batch has as long to read the entity as a rebuild has to scan it: `lumnik.search.projection.rebuild-scan-timeout-seconds`, 1,800 by default.

The budget trades freshness for load on the primary database: a longer budget empties a backlog sooner and keeps the database busier while it does. To watch it, `lumnik_search_projection_lag_seconds` and `lumnik_search_projection_lagging_indexes` report the oldest pending change and how many indexes still have one. Your own freshness budget, measured on your hardware, is the figure that decides the setting.

The rebuild threshold weighs reading against writing. Measured on one laptop with the PostgreSQL provider, a rebuild cost the same as catching up somewhere between 1,600 and 6,800 pending changes, for entities of 100,000 and of 1,000,000 rows. A provider whose writes cost more per document moves that point up; replay a backlog on your own hardware before changing the default.

## Database cost on observed tables

Once a Search provider is installed, the hub automatically adds an index to every source or lookup
table observed by a valid SearchIndex. Existing tables need no migration or manual preparation.

The index uses disk space and adds work to ingestion, especially when unchanged rows are ingested
again with `on-conflict: update`. The hub does not remove it when a SearchIndex is deleted or becomes
invalid, or when the provider is removed. Leave it in place unless the table is no longer used by
Search and reclaiming that space or write capacity matters; a database administrator can then identify
and drop it with the usual PostgreSQL tools. The hub recreates it if Search observes the table again.

## Choosing a provider

Search needs a provider, and there are two, chosen when the hub is **built**, never implied by
enabling Pro:

- **PostgreSQL** (`-Psearch`): the index lives in the database you already run. Nothing else to
  operate. Projection writes and index maintenance share the primary database's resources with
  ingestion, so it suits installations where that headroom exists. One measured envelope, on one
  laptop, is in [Search (PostgreSQL provider)](../operations/sizing.md#search-postgresql-provider).
- **Cluster** (`-Psearch-cluster`, closed edition): the index lives in an OpenSearch 2.x/3.x cluster,
  or in your own Elasticsearch 8.x/9.x. Graduate to it when projection and query load start to
  contend with ingestion — and decide that on your own measurements, not on a row count:

> On your production hardware, replay the scenario at the volume and rate you expect. Move to the cluster provider when, **under ingestion**, the p95 of a matching mode exceeds your latency budget, or projection lag exceeds your freshness budget, or ingestion throughput drops noticeably against the ingest-alone phase — while the same search load **alone** stays within budget. That combination identifies contention from keeping Search on the primary database; the cluster provider moves query execution, projection writes and search-index maintenance off it (the projection worker still reads the canonical data from PostgreSQL). If the search load alone already exceeds the budget, the cause is volume or matching, not sharing: re-read the index declaration before adding infrastructure.

Rows per tenant is a column of the observed table, not a trigger.

The published hub image carries **neither**. A provider is built in from source, so an installation
that never asked for Search never has a search client on its classpath.

To try the PostgreSQL provider on a single host, run `deploy/selfhost/up.sh --search` from the
closed-edition sources: it builds the hub from source with the provider and starts the usual stack — there is nothing else to run. It is an alternative to
`--search-cluster`; the script refuses both together.

To try the cluster provider on a single host, run `deploy/selfhost/up.sh --search-cluster` from the
closed-edition sources: it builds the hub with the provider, starts an OpenSearch container with
security on and per-install certificates, and generates two passwords into `.env`: the cluster's own admin, and the password of a dedicated `lumnik` user that the hub runs as. That user is confined to the cluster's `lumnik-*` indexes and cannot read or change the cluster's security settings. The hub reads the
cluster from `LUMNIK_SEARCH_OPENSEARCH_URL`, `..._USERNAME`, `..._PASSWORD` and, for a private CA,
`..._TLS_CA_FILE`.

Once an index is built, query it from the CLI, or over HTTP with `GET /api/search`:

```bash
lm search query "hydraulique lyon" --entity AcmeCustomer --facet segment=GOLD
```

An empty answer is explained rather than left bare. Anyone is told when no engine is installed, when no
index is declared over the entity, or when the index is invalid; an integrator is also told whether the
index is not built yet or still being built (the build state is theirs to read). See
[Querying Search](querying.md), which also covers the match modes seen from the query side, filters, pages and
the answers that are not "no results".

You can tell whether it works without guessing:

```bash
curl -H "Authorization: Bearer $TOKEN" "$LUMNIK_URL/api/search/capability"
```

The answer is one of three, and none of them looks like "zero hits": a provider that is `AVAILABLE`,
a provider that is `DEGRADED` with a short reason (`backend_unreachable`, `backend_auth_failed`,
`unsupported_distribution`, `cluster_red`), or no provider installed. A cluster outage never stops
ingestion and never turns into an empty answer.

Two limits to know. A search is **not a snapshot** across pages: a page fetched after the index was
rebuilt is refused rather than spliced into the previous one. And the answer's `asOf` is the freshness
it reports — trust that value, not the wall clock.
