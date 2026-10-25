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
| **Status** | `lm search status` — `lm_integrator` |
| **Provider needed?** | to query, yes: [choosing a provider](#choosing-a-provider). Without one, the declaration is kept |

## Quickstart

```yaml
--8<-- "docs/search/search-indexes/10-customers.yaml"
```

Apply it after the [entity](../entities.md#quickstart). The hub builds the index within a minute:

```
$ lm search apply -f docs/search/search-indexes/10-customers.yaml
Applied search index "customers" → entity AcmeCustomer (valid)
$ lm search status
NAME       STATUS    LAG(s)  DOCUMENTS  LAST REBUILD
customers  building  0
$ lm search status
NAME       STATUS  LAG(s)  DOCUMENTS  LAST REBUILD
customers  ready   20      14         ready
$ lm search query hydraulique
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.55   city=Lyon | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD
AcmeCustomer  code=P-1010  0.50   city=Milano | code=P-1010 | name=Hydraulique Milano 1010 | segment=SILVER

facets:
city      Lyon (1)  Milano (1)
segment   GOLD (1)  SILVER (1)

2 hits shown · as of 2026-10-25 00:14
```

Fourteen customers indexed; two match *hydraulique*. `city` and `segment` are facets: counted
values, to narrow with `--facet segment=GOLD`. Every way to query: [Query it](querying.md).

**On a hub without a [Search provider](#choosing-a-provider)**, nothing builds the index. Its
status reads `not_installed` in `lm search status` and on the TUI's `:search` page. A query says
why:

```
$ lm search status
NAME       STATUS         LAG(s)  DOCUMENTS  LAST REBUILD
customers  not_installed  0
$ lm search query hydraulique
Error: search is not installed on this hub — no Search provider is built into it, so there is nothing to query (this is not an empty result). The published hub image never carries one: build it from source with deploy/selfhost/up.sh --search (PostgreSQL) or --search-cluster (OpenSearch), both closed edition
```

## Fields: searchable, returned, faceted

Every declared field is **returned** with a hit. `match` also makes it **searchable**; `facet: true`
also makes it a facet. So:

- A searched or faceted field is always shown too.
- A field **without `match` is shown only**: it rides along with the hit (a status, a date), and is
  never searched.
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

`spec.language` is one of `simple` (the default), `english`, `french`, `german`, `italian`,
`spanish`, `dutch`, `portuguese`.

## Choosing a title

A hit shows the name of its entity as its heading, which tells a product from a customer but not one product
from another. Name the field people recognise an item by, and the business app promotes it to the heading:

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
shows — and Search uses it, with nothing to declare in the index:

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

A mistake is refused when you apply, naming the key — here an address that mentions `{ghost}`, which is not a field of the index:

```
$ lm search apply -f linked.yaml
Error: API error 400: spec.links[0].url: {ghost} is not one of spec.fields
```

A new or changed link shows at the very next search, without waiting for the index to catch up.

## Columns joined from a lookup table

A [joined column](../entities-joins.md) can be a field like any other, on a `match:` entity or on one
that declares `resolution:`. The index follows the lookup table as it follows the entity's own sources:

- the first lookup row for a key makes the rows holding that key appear with the joined value;
- one more row of **different content** for the same key makes the key ambiguous: the joined value goes
  empty on those rows, it is never one of the two;
- ingesting the same lookup row again changes nothing.

On an entity with `resolution:`, one result is one resolved customer. A row still undecided is not
searchable yet.

## What is refused, and why

| Refused | Do instead |
|---|---|
| `spec.scope` / `spec.scopes` | nothing: an index takes its entity's scopes |
| `spec.table` / `spec.source` | index an [entity](../entities.md), never a raw table |
| a field that is not a column of the entity | use the entity's column name, not the source's |
| `tenant_id`, `_conflicts`, `_entity_id`… | index business columns only |
| a `metadata.name` that is not a lower-case slug, or `apply` | rename it |
| an entity whose scopes you do not **all** hold | ask for the missing `scope:` role |
| a second index over the same entity | **one index per entity**: re-apply the first one, mixing `match` modes field by field |

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

- Its results can show for a moment after the delete, never to someone who may not read them.
- To change an index, re-apply it under the same name. Delete-then-recreate shows both for a while.

## Rows deleted directly in the database

A row deleted outside ingestion (a `DELETE` in the database, `lm endpoint purge`) stays searchable
until the index is rebuilt. Rebuild, and wait for the end:

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

## How fast an index catches up

After a bulk load the index trails the data. `lm search status` shows how far behind, in `LAG(s)`.
The hub catches up about once a minute, on its own. To have it now: `lm search rebuild NAME --wait`.

??? note "Tuning the catch-up"
    | Setting | Default | Effect |
    |---|---|---|
    | `LUMNIK_SEARCH_PROJECTION_CATCHUP_BUDGET` | `30s` | time per minute for extra batches on indexes still behind |
    | `LUMNIK_SEARCH_PROJECTION_REBUILD_BACKLOG_THRESHOLD` | `5000` | pending changes above which the index is rebuilt instead |

    The lag is a metric too: [Observability](../operations/observability.md).

A document the provider refuses leaves the index `degraded`, with the provider's message in
`lm search status NAME`; the other documents carry on. Then:

- the **source row** is the cause: correct it and ingest it again;
- the **declaration** is the cause: correct it, `lm search apply -f <manifest>`, then
  `lm search rebuild NAME --wait`.

## Choosing a provider

Search needs a provider, chosen when the hub is **built** (lumnik Pro). The published hub image
carries neither.

| Provider | Self-host | Where the index lives |
|---|---|---|
| PostgreSQL | `deploy/selfhost/up.sh --search` | the database you already run |
| OpenSearch / Elasticsearch | `deploy/selfhost/up.sh --search-cluster` | an OpenSearch 2.x/3.x cluster, or your Elasticsearch 8.x/9.x |

- The two are alternatives: `up.sh` refuses `--search` and `--search-cluster` together.
- `--search-cluster` starts an OpenSearch container with security on and writes two passwords into
  `.env`: the cluster admin's, and that of a `lumnik` user confined to the `lumnik-*` indexes.
- The hub reads the cluster from `LUMNIK_SEARCH_OPENSEARCH_URL`, `…_USERNAME`, `…_PASSWORD` and, for
  a private CA, `…_TLS_CA_FILE`.

**When to move to the cluster provider:** when searches slow down while connectors run. Measured
numbers: [Sizing](../operations/sizing.md).

Once an index is built, query it — the [full guide](querying.md):

```bash
lm search query "hydraulique lyon" --entity AcmeCustomer --facet segment=GOLD
```

**Is there a provider?** `lm search query` and the TUI's `:search` page say *not installed*, and
every index's status reads `not_installed`. No `lm` command prints the raw state; over HTTP:

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
