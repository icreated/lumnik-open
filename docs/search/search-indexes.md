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

Apply it after the [entity](../entities.md#quickstart):

```
$ lm search apply -f docs/search/search-indexes/10-customers.yaml
Applied search index "customers" → entity AcmeCustomer (valid)
$ lm search status
NAME       STATUS    LAG(s)  DOCUMENTS  LAST REBUILD
customers  building  0
```

On a hub without a [Search provider](#choosing-a-provider) the declaration is stored, nothing is
built, and `building` stays as it is. A query says whether a provider is there:

```
$ lm search query hydraulique
Error: search is not installed on this hub — no Search provider is built into it, so there is nothing to query (this is not an empty result). The published hub image never carries one: build it from source with deploy/selfhost/up.sh --search (PostgreSQL) or --search-cluster (OpenSearch), both closed edition
```

## Fields: searchable, returned, faceted

Every declared field is **returned** with a hit. `match` also makes it **searchable**; `facet: true`
also makes it a facet. So:

- **`searchable ⊆ returnable`** and **`facet ⊆ returnable`** always hold: a field cannot be searched
  without being shown.
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

`phonetic` is refused at apply: `spec.fields[0].match: 'phonetic' is not supported yet`.

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
- **It is never guessed** from the field order.
- **The entity stays visible** under the heading, because one search may return several entities.
- **An item with no value in that field** shows the entity name for that item only.

Declaring or changing a `titleField` is a re-apply. The heading changes at the next search when the
field is already in the index; a title field added in the same apply shows once the index has
[caught up](#how-fast-an-index-catches-up).

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

To open the same item in the system that owns it, declare the address once, on the field people see:

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
$ lm search apply -f linked.yaml
Error: API error 400: spec.links[0].url: {ghost} is not one of spec.fields
```

A new or changed link shows at the very next search, without waiting for the index to catch up.

## Columns joined from a lookup table

A [joined column](../entities.md#fuse-or-join) can be a field like any other, on a `match:` entity or on one
that declares `resolution:`. The index follows the lookup table as it follows the entity's own sources:

- the first lookup row for a key makes the rows holding that key appear with the joined value;
- one more row of **different content** for the same key makes the key ambiguous: the joined value goes
  empty on those rows, it is never one of the two;
- ingesting the same lookup row again changes nothing.

A big lookup loaded for the first time re-projects every row that joins it — see
[how fast an index catches up](#how-fast-an-index-catches-up).

On an entity with `resolution:`, one document is one resolved identity. A source row whose identity
is undecided is not a document until its decision. A document's freshness (`asOf`) also waits for the
lookup to have been read.

## What is refused, and why

| Refused | Because |
|---|---|
| `spec.scope` / `spec.scopes` | scopes are inherited from the entity; a SearchIndex can never widen who sees its data |
| `spec.table` / `spec.source` | only a canonical entity has a stable identity and scopes; a raw table has neither |
| a field that is not a canonical column of the entity | the allowlist names canonical columns only (a source column name is not one) |
| `tenant_id` and the entity's [reserved columns](../entities.md#the-manifest) (`_conflicts`, `_entity_id`…) | hub bookkeeping, never business data |
| a `metadata.name` that is not a lower-case slug, or `apply` | the name is used in the delete address |
| an apply over an entity whose scopes you do not **all** hold | `403 {"error":"scope not granted"}` — holding one of two is escalation |
| a second index over an entity another SearchIndex already indexes | `409` naming that index — **one SearchIndex per Entity**: two would return every hit twice, with scores that cannot be compared |

```
$ lm search apply -f scoped.yaml
Error: API error 400: spec.scopes: scopes are inherited from the entity and cannot be declared on a SearchIndex
$ lm search apply -f tenant.yaml
Error: API error 400: spec.fields[0].name: 'tenant_id' is an internal column and cannot be indexed
$ lm search apply -f apply.yaml
Error: API error 400: metadata.name must be a URL-safe slug ([a-z0-9_-], starting with a letter or digit) and not one of [apply]
$ lm search apply -f second.yaml
Error: API error 409: entity 'AcmeCustomer' is already indexed by SearchIndex 'customers' — one SearchIndex per Entity: re-apply 'customers' to change it (match modes can be mixed field by field), or delete it first
```

- A re-apply of an existing name needs every scope of the entity that name **currently** indexes.
- To search one column two ways, give each field its own `match` mode inside the one index.

## Deleting — and the purge it leaves behind

`lm search rm` names the index and its entity and asks you to type `delete` (`--force` skips the
question in a script):

```
$ lm search rm customers
Type 'delete' to delete search index customers over entity AcmeCustomer — its projected documents are purged: delete
search index customers deleted
```

The delete also queues the purge of the documents already in the Search backend. The purge happens
even if the provider was down or installed later, and re-applying the same name never cancels it.

**The purge is asynchronous**, and waits while the provider is down:

- A query right after `lm search rm` can still return that index's hits (never hits the caller may
  not read: scopes are checked at query time).
- Deleting an index and declaring a new one over the same entity can show both for a while. Re-apply
  the same name instead: a re-apply rebuilds in place. A deleted name declared again before its purge
  ran stays [withheld](#removing-a-field-from-an-index) until rebuilt.
- A script that must be sure a deleted index no longer answers polls for the empty answer.

## Rows deleted directly in the database

Search is not told about rows removed outside ingestion; they stay searchable until the index is
rebuilt. Two gestures do this:

- a direct `DELETE` on `connector.t_*` in the database;
- `lm endpoint purge` on an endpoint whose hybrid target is in the `connector` schema (for example
  the demo's `connector.cust_siebel`).

Rebuild, and wait for the end:

```bash
lm search rebuild customers --wait
```

Without `--wait` the command returns once the run is claimed, and a search right away can still return
the deleted row. Only the `ready` outcome means it is gone (`failed` and `cancelled` are the others).

## When the entity changes

When an entity is re-applied without a column a SearchIndex uses, every tenant's SearchIndex over it
turns **`invalid`**. The entity apply is never refused because of Search. An invalid index serves no
query until it is **re-applied** successfully; restoring the column alone does not revalidate it.

`lm search status` says so in its `STATUS` column; what was built before stays under `LAST REBUILD`:

```
$ lm search status
NAME       STATUS   LAG(s)  DOCUMENTS  LAST REBUILD
customers  invalid  0       14         ready
$ lm search status customers
Name              customers
Status            invalid
Live generation   1
Documents         14
Lag               0s
Last rebuild      97641fed-1dd2-4f35-bd29-353763e0f249 (ready)
Invalid           spec.fields[2].name: 'city' is not a canonical column of entity 'AcmeCustomer'
```

A query no longer reaches the index's documents — neither their hits nor their facets — and the empty
answer says why:

```
$ lm search query hydraulique
0 hits
SearchIndex "customers" over AcmeCustomer is invalid: spec.fields[2].name: 'city' is not a canonical column of entity 'AcmeCustomer' — fix the manifest and re-apply it: lm search apply -f <manifest>
```

To serve again, remove the column from the index and re-apply it. The index then reads
[`withheld`](#removing-a-field-from-an-index) until a rebuild without the column has replaced its
build; the section below shows that step.

### Removing a field from an index

Removing a field is how you stop exposing it. What a re-apply does to the index:

| The re-apply… | The index |
|---|---|
| removes a field, or stops searching or faceting on one | **withheld**: answers no query until rebuilt |
| changes a field's `match` mode, the `language` or the `entity` | **withheld** until rebuilt |
| only adds (a field, a field newly searched or faceted) | keeps answering from its current build; the addition appears after the rebuild |

The hub starts the rebuild on its own at its next pass; `lm search rebuild NAME --wait` starts it now
and returns when it is done. A withheld index stays withheld until that rebuild, even if you put the
field back.

```
$ lm search apply -f customers.yaml
Applied search index "customers" → entity AcmeCustomer (valid)
$ lm search query hydraulique
0 hits
SearchIndex "customers" over AcmeCustomer is withheld: a re-apply removed or changed what its build still serves — it answers again once rebuilt: lm search rebuild customers --wait
$ lm search status
NAME       STATUS    LAG(s)  DOCUMENTS  LAST REBUILD
customers  withheld  0       14         ready
$ lm search status customers
Name              customers
Status            withheld
Live generation   3
Documents         14
Lag               0s
Last rebuild      fe1e7ee9-2d01-472b-a397-60f0b7e4ba84 (ready)
Withheld          a re-apply removed or changed what its build serves; it answers nothing until rebuilt: lm search rebuild customers --wait
$ lm search rebuild customers --wait
Name              customers
Status            ready
Live generation   4
Documents         14
Lag               0s
Last rebuild      fe72138f-9a9c-4ae7-8a3c-7c274fe5e559 (ready)
$ lm search query hydraulique
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.55   code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD
AcmeCustomer  code=P-1010  0.50   code=P-1010 | name=Hydraulique Milano 1010 | segment=SILVER

facets:
segment   GOLD (1)  SILVER (1)

2 hits shown · as of 2026-10-18 22:13
```

Here `customers.yaml` is the quickstart manifest without its `city` field.

### An entity applied before Search

| Symptom | Gesture |
|---|---|
| `lm search status NAME` reads `degraded`, `Last error … its view has no "_canonical_key" column …` | `lm entity apply -f <manifest>` (the same manifest), then `lm search rebuild NAME --wait` |

Do it once for each indexed entity that was last applied by an older hub.

## How fast an index catches up

After a bulk load the index trails the data. `lm search status` shows the lag; the hub catches up
about once a minute, in batches of at most 500 documents. A backlog over the threshold below is
rebuilt instead, while the index keeps answering from its current content.

| Property (env var) | Default | Effect |
|---|---|---|
| `lumnik.search.projection.catchup-budget` (`LUMNIK_SEARCH_PROJECTION_CATCHUP_BUDGET`) | `30s` | time per minute for extra batches on indexes still behind; `0s` = one batch per minute |
| `lumnik.search.projection.rebuild-backlog-threshold` (`LUMNIK_SEARCH_PROJECTION_REBUILD_BACKLOG_THRESHOLD`) | `5000` | pending changes above which the index is rebuilt; `0` = never |
| `lumnik.search.projection.catchup-consecutive-failure-limit` (`LUMNIK_SEARCH_PROJECTION_CATCHUP_CONSECUTIVE_FAILURE_LIMIT`) | `3` | refused documents in a row before the index waits for the next minute; at least `1` |
| `lumnik.search.projection.rebuild-scan-timeout-seconds` | `1800` | time a batch or a rebuild has to read the entity |

Two metrics watch it: `lumnik_search_projection_lag_seconds` (oldest pending change) and
`lumnik_search_projection_lagging_indexes` (indexes still behind).

A document the provider refuses leaves the index `degraded`, with the provider's message in
`lm search status NAME`; the other documents carry on. Then:

- the **source row** is the cause: correct it and ingest it again;
- the **declaration** is the cause: correct it, `lm search apply -f <manifest>`, then
  `lm search rebuild NAME --wait`.

## Database cost on observed tables

- With a provider installed, the hub adds a database index to every source or lookup table a valid
  SearchIndex reads. Nothing to prepare.
- It costs disk space and ingestion work, and stays when the SearchIndex or the provider goes. A DBA
  may drop it once the table no longer serves Search; the hub recreates it if needed.

## Choosing a provider

Search needs a provider, chosen when the hub is **built**. The published hub image carries neither.

| Provider | Build | Where the index lives | Try it on one host (closed-edition sources) |
|---|---|---|---|
| PostgreSQL | `-Psearch` | the database you already run; shares its resources with ingestion | `deploy/selfhost/up.sh --search` |
| Cluster | `-Psearch-cluster` (closed edition) | an OpenSearch 2.x/3.x cluster, or your Elasticsearch 8.x/9.x | `deploy/selfhost/up.sh --search-cluster` |

- The two are alternatives: `up.sh` refuses `--search` and `--search-cluster` together.
- `--search-cluster` starts an OpenSearch container with security on and writes two passwords into
  `.env`: the cluster admin's, and that of a `lumnik` user confined to the `lumnik-*` indexes.
- The hub reads the cluster from `LUMNIK_SEARCH_OPENSEARCH_URL`, `…_USERNAME`, `…_PASSWORD` and, for
  a private CA, `…_TLS_CA_FILE`.

**When to move to the cluster provider.** Replay your expected volume on production hardware. Move
when, **under ingestion**, query p95 or projection lag exceeds your budget, or ingestion slows — while
the same search load **alone** stays within budget. If search alone is already too slow, re-read the
index declaration first.

Once an index is built, query it — the [full guide](querying.md):

```bash
lm search query "hydraulique lyon" --entity AcmeCustomer --facet segment=GOLD
```

**Is there a provider?** `lm search query` and the TUI's `:search` page say *not installed*. No `lm`
command prints the raw state; over HTTP:

```
$ curl -H "Authorization: Bearer $TOKEN" "$LUMNIK_URL/api/search/capability"
{"state":"unavailable","provider":null,"reason":"not_installed","version":null}
```

| `state` | Meaning |
|---|---|
| `available` | a provider answers |
| `degraded` | a provider is installed but impaired; `reason` is `backend_unreachable`, `backend_auth_failed`, `backend_error`, `unsupported_distribution` or `cluster_red` |
| `unavailable` | no provider in this hub (`reason`: `not_installed`) |

A cluster outage never stops ingestion and never turns into an empty answer.
