# kind: View — see

> The first rung of the per-métier app ladder: a **declarative, read-only list** over a
> scope's table, rendered by the hub and displayed by `lm`, the TUI or the PWA. One YAML says
> which columns; a filter it cannot apply is refused instead of showing an empty table.

| | |
|---|---|
| **Kind** | `View` — `apps.lumnik.io/v1` |
| **Over** | any table of a scope's card: a raw `connector.t_*`, or a fused `connector.v_*` |
| **Renders** | `lm view -f FILE` or `lm view NAME` (list, filters, one record's detail); the TUI's `:view`; the PWA's *Fiches* tab |
| **Store** | `lm view apply` / `lm view rm` — `lm_integrator`, scope-bound; reads `lm_user` |
| **Bounds** | 1 000 rows and a 5-second statement timeout per read, read-only, tenant-isolated; when more rows match, `lm view` ends with `first 1000 rows shown; more match — narrow with --filter` (`-o json`: `"truncated": true`) |
| **Edition** | Open |

A view is a title, a table and the columns worth looking at. Over an [entity](../entities.md) it
uses the entity's labels, and a record's detail ends with the sources' disagreements on it. The
file **is** the app — hand-written, or [drafted by the AI](#generate-a-view-with-the-ai).

## Features

- **[Over a raw table or a fused entity.](#a-view-over-a-raw-table)** A freshly landed export is
  viewable as it is; an entity's view carries its labels and its `_conflicts` ledger.
- **[A filter grammar that refuses.](#filter-grammar)** An unknown column, a column the view does
  not show, a malformed filter or a value outside a declared domain is refused.
- **[One record's detail, disagreements included.](#quickstart)** `lm view KEY` prints a
  record and, under *Ambiguïtés et divergences*, which source said what.
- **[A store the PWA discovers.](#the-saved-view-store)** `lm view apply` publishes a manifest;
  anyone holding the scope finds it on their phone.
- **[Drafted by the AI, gated like a render.](#generate-a-view-with-the-ai)** The model picks
  table, columns and filters; the hub validates them and writes the YAML.

## Quickstart

!!! note "Every screen on this page reads the demo desk that ships with the repo"
    Land the three sources as [The demo desk](demo-desk.md#landing-the-sources) shows, then
    apply the customer entity:
    `lm entity apply -f docs/apps/demo/entities/10-customer.yaml`.

```yaml
--8<-- "docs/apps/demo/views/10-customers.yaml"
```

```
$ lm view -f docs/apps/demo/views/10-customers.yaml
Clients ACME

Code client  Raison sociale             Ville      Segment  Encours autorisé
P-1000       Hydraulique Lyon 1000      Lyon       GOLD     500
P-1001       Roulements Bruxelles 1001  Bruxelles  SILVER   637
P-1002       Fixations Milano 1002      Milano     BRONZE   774
P-1003       Pneumatique Porto 1003     Porto      GOLD     911
P-1004       Étanchéité Kraków 1004     Kraków     SILVER   1048
P-1005       Transmission Aarhus 1005   Aarhus     GOLD     1185
P-1006       Outillage Valencia 1006    Valencia   BRONZE   1322
P-1007       Levage Bristol 1007        Bristol    SILVER   1459
P-1008       Filtration Lyon 1008       Lyon       GOLD     1596
P-1009       Soudure Bruxelles 1009     Bruxelles  BRONZE   1733
P-1010       Hydraulique Milano 1010    Milano     SILVER   1870
P-1011       Roulements Porto 1011      Porto               2007
P-1012       Levage Aarhus 1012         Aarhus              
P-1013       Filtration Valencia 1013   Valencia            
```

The headers are the entity's `label`s. Fourteen rows, ordered by the first declared column.
Narrow it — filters are repeatable and ANDed, and the title lists them:

```
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter segment=GOLD --filter 'credit_limit>1000'
Clients ACME · segment=GOLD · credit_limit>1000

Code client  Raison sociale            Ville   Segment  Encours autorisé
P-1005       Transmission Aarhus 1005  Aarhus  GOLD     1185
P-1008       Filtration Lyon 1008      Lyon    GOLD     1596
```

One record — the first declared column is the key:

```
$ lm view P-1008 -f docs/apps/demo/views/10-customers.yaml
Clients ACME / P-1008

  Code client        P-1008
  Raison sociale     Filtration Lyon 1008
  Ville              Lyon
  Segment            GOLD
  Encours autorisé   1596

  (!) Ambiguïtés et divergences
  Ville   t_acme_desk_erp «Lyon» · t_acme_desk_crm «Villeurbanne»
```

*Ambiguïtés et divergences* is the entity's `_conflicts` ledger for that record: the ERP (listed
first) won with Lyon, and the CRM's Villeurbanne is kept. A record both sources agree on:

```
$ lm view P-1005 -f docs/apps/demo/views/10-customers.yaml
Clients ACME / P-1005

  Code client        P-1005
  Raison sociale     Transmission Aarhus 1005
  Ville              Aarhus
  Segment            GOLD
  Encours autorisé   1185

  ✓ aucune ambiguïté ni divergence
```

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | the view's name — in the store, lower-cased, a URL-safe slug (`[a-z0-9_-]`, starting with a letter or digit) |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) backs the view |
| `spec.table` | no | defaults to the scope's single table; required when the scope has several. Raw `connector.t_*` tables and fused `connector.v_*` views both qualify |
| `spec.title` | no | the display title — falls back to the entity's `label:`, then the table's name |
| `spec.columns[]` | yes (≥ 1) | the columns to show, in order. **Every one must exist in the scope's card.** The first is the sort order and the detail key |
| `spec.links[]` | no | cells that lead somewhere: another view, or an external page — see [Linking views](#linking-views) |

`apply` refuses a key it does not know:

```
$ lm view apply -f docs/apps/demo/views/61-refused-unknown-key.yaml
Error: API error 400: unknown key(s) in the manifest: spec.titel — kind:View honors root {apiVersion, kind, metadata, spec}, metadata {name}, spec {columns, links, scope, table, title}, spec.links[] {column, filters, targetView, url}
```

`lm view -f` still renders such a file: the unknown key is ignored, so this one shows under the
table's name instead of a title.

### Linking views

A cell can lead somewhere. Declare it on the view the user starts from:

```yaml
--8<-- "docs/apps/demo/views/30-categories-links.yaml"
```

- **To another view.** Tapping *Pompes* opens the `articles` view with `category_id=1000021` already applied,
  on its first page. The filter shows in the filter bar like one the user typed, and removes the same way.
- **To an external page.** Tapping a category id opens `https://erp.example.com/categories/1000021` in a new tab.
- **`{column}` is the value of that column on the row tapped.** It must be one of this view's `spec.columns`.
  Literal text around it is kept (`CAT-{category_id}`). Nothing else is substituted.
- **One destination per link**: `targetView` (with at least one filter) or `url`, never both. One link per
  column, at most 16 links, at most 8 filters per link.
- A row whose value is empty has no link on that cell.

The **target** view judges the link's filters, like any filter: an unknown column, a value outside
a declared domain or a malformed value is refused. The target must be a view of the same scope.
If it is missing or refuses the filter, the user stays on the current view and reads why; the
target is never opened unfiltered.

**Order of apply.** A target that does not exist yet is accepted with a warning (stderr, exit 0);
the link works once the target is applied:

```
$ lm view apply -f docs/apps/demo/views/30-categories-links.yaml
applied: categories (scope catalogue)
! 30-categories-links.yaml — 0 error(s), 1 warning(s)

  WARN     spec.links[0].targetView
          targetView 'articles' is not a view of scope 'catalogue' (yet); the declaration is kept, and following the link is refused until that view is applied
```

If the target exists but does not show a filtered column, the warning names the column.

**External links:**

- `https://` only, with the host written out — no `{column}` before the first `/`, no credentials.
- The value is percent-encoded, so an id like `a/b?x` stays inside its segment.
- The page opens in a new tab without a referrer.
- **The other system receives the value** — link only to systems you trust with it.

The same rules apply to a [search result's link to your ERP](../search/search-indexes.md#linking-a-result-to-your-erp).

### A view over a raw table

No entity is needed: a view can point straight at a raw table the scope's connectors land — here
the order book, as the ERP exported it:

```yaml
--8<-- "docs/apps/demo/views/20-orders.yaml"
```

```
$ lm view -f docs/apps/demo/views/20-orders.yaml --filter 'status~litige'
Commandes · status~litige

order_no   code    status     amount   ordered_on
SO-000006  P-1008  EN LITIGE  4100.00  2026-08-07
SO-000010  P-1004  EN LITIGE  77.00    2026-08-12
```

The headers are the raw column names, because nothing declared a label. A
[`kind: Workflow`](workflows.md) is what declares what the status values *mean*.

## Rendering

```bash
lm view -f FILE                                # the list, from a manifest file
lm view -f FILE --filter city=Lyon             # filtered; repeatable, ANDed
lm view KEY -f FILE                            # one record — KEY matches the first declared column
lm view NAME [KEY]                             # the same, from a view saved in the store
```

A saved view renders without a file:

```
$ lm view acme-customers --filter segment=GOLD
Clients ACME · segment=GOLD

Code client  Raison sociale            Ville   Segment  Encours autorisé
P-1000       Hydraulique Lyon 1000     Lyon    GOLD     500
P-1003       Pneumatique Porto 1003    Porto   GOLD     911
P-1005       Transmission Aarhus 1005  Aarhus  GOLD     1185
P-1008       Filtration Lyon 1008      Lyon    GOLD     1596
```

Rendering needs `lm_user` and stays inside the [métier-scope boundary](../rag/scopes.md): a view
whose `spec.scope` you do not hold answers `403 scope not granted`.

| Bound | What happens |
|---|---|
| 1 000 rows per read | `lm view` prints the first 1 000 rows and does not say the list was cut. The hub's JSON answer carries `"truncated": true`; narrow the list with filters |
| 5-second statement timeout | refused with a `400` that says so |
| Order | the first declared column, nulls last, then the record's own `id` (for a fused entity, the remaining declared columns) |

A timestamp cell, in a render or a record's detail, is its UTC instant with the microseconds
stored, `"2026-07-01T00:00:00.000500Z"`: the text `lm data row` shows. Numbers and booleans stay
JSON numbers and booleans.

The PWA reads a view **page by page** (50 rows); `lm view` has no paging flag. Over HTTP the
door is `POST /api/views/render?pageSize=N` (1 to 200):

- the answer carries `"hasMore": true` and an opaque `"nextCursor"` when more rows exist;
- send it back as `&cursor=…` with the same manifest, filters and `pageSize`;
- any change (filter, page size, view, scope) makes the cursor `400` *this page link is no longer
  valid — reload the view from its first page*;
- no total is counted;
- on a table no row is skipped or repeated between pages; a fused entity is paged by position, so
  a row ingested between two page reads can shift its boundary by one.

**A slow page on a large table?** Narrow it with filters first. Then add an index matching the
paging order, `(tenant_id, first_column, id)` — the hub never creates one.

In the TUI, `:view` opens the same file interactively:

| Key | Action |
|---|---|
| `f` | add a filter |
| `F` | clear the filters |
| `r` | re-render |
| `Enter` | one record's detail |
| `o` | open another manifest |
| `g` | generate a manifest from a sentence |
| `a` | apply the rendered view to the store |

## Filter grammar

| Form | Meaning |
|---|---|
| `col=value` | exact match; a date against a date or timestamp column is equal as an instant (`due=2026-07-01` is midnight UTC); on a column with declared `values`, an out-of-domain value is **refused** |
| `col~text` | substring, case-insensitive |
| `col>v` `col<v` `col>=v` `col<=v` | ordering — `v` must be a number or an ISO-8601 date; a row whose value has neither shape is not matched |

The ordering forms work on the `text` columns a CSV or a fusion produces — `credit_limit>1000`
above, and on the order book:

```
$ lm view -f docs/apps/demo/views/20-orders.yaml --filter 'ordered_on>2026-08-10'
Commandes · ordered_on>2026-08-10

order_no   code    status     amount   ordered_on
SO-000009  P-1010             120.00   2026-08-11
SO-000010  P-1004  EN LITIGE  77.00    2026-08-12
SO-000011  P-1006  BROUILLON  3000.00  2026-08-12
SO-000012  P-1011  En cours   55.00    2026-08-13
```

Whether a column takes an ordering filter depends on its type:

| Column type | Ordering (`<` `>` `<=` `>=`) |
|---|---|
| number (floating-point included), date, timestamp | yes |
| text holding numbers or dates | yes |
| `money`, `time`, `interval`, `boolean`, `uuid` | refused, naming the column and its type — use `=` or `~` |

A bound may be written in scientific notation (`1e-5`, `6.022e23`); on a floating-point column it
must fit a double. A number orders a number or text column, a date a date or text column.

A date or date-time written without an offset is read as UTC, in the bound and in the column
alike: `ordered_on>2026-08-10` starts at midnight UTC, whatever time zone the hub runs in. A
process reads a date the same way, so a view and a process given the same filter pick the same
rows ([how a process compares a date](processes.md#watch-a-second-condition-on-the-same-table)).
`~` on a timestamp column searches its text written in UTC, `2026-01-01 00:30:00+00`.

### Refusal rules

Each refusal names the fix:

```
$ lm view -f docs/apps/demo/views/60-refused-unknown-column.yaml
Error: API error 400: column 'phone' is not in the 'desk' schema for table connector.v_acmecustomer — fix the column name or add it to the scope's entity
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter email~example
Error: API error 400: cannot filter on 'email' — the view does not show it; declare it in spec.columns to filter on it
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter 'city~'
Error: API error 400: filter 'city~' needs a column and a value — col=value, col~text, or col>value
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter segment=PLATINUM
Error: API error 400: 'PLATINUM' is not an allowed value for segment (allowed: GOLD, SILVER, BRONZE)
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter "city='Lyon'"
Error: API error 400: filter value 'Lyon' is quoted; a value here is a bare literal, so the quotes would be compared to city too — write city=Lyon
```

| Refused | Why |
|---|---|
| an unknown column in `columns:` | the scope's card does not have it |
| a filter on a column the view does not show | filters run over the view's own columns |
| a malformed filter | — |
| an ordering filter on a type that cannot be ordered | see the operator table above |
| an `=` value outside the column's declared domain | the entity's `values` — the same rule as the chat's [domain guard](../transparency/ask-honesty.md) |
| a quoted value | write `city=Lyon`. A lone apostrophe is data: `name~l'ecole` works |

A zero-row result is then meaningful: the filter is valid and matches nothing.

## The saved-view store

A manifest can also live **in the hub**, where the métier PWA at `/app/` finds it under its scope:

```
$ lm view apply -f docs/apps/demo/views/10-customers.yaml
applied: acme-customers (scope desk)
$ lm view apply -f docs/apps/demo/views/20-orders.yaml
applied: acme-orders (scope desk)
$ lm view list
NAME            SCOPE  TITLE
acme-customers  desk   Clients ACME
acme-orders     desk   Commandes
$ lm view get acme-customers -o acme.yaml
manifest written to acme.yaml — review it, then: lm view -f acme.yaml
```

`lm view get NAME` without `-o` prints the stored YAML, comments included, byte for byte.

| Command | Effect | Role |
|---|---|---|
| `lm view apply -f FILE` | validate-then-upsert: a view that cannot render is never saved; re-applying a name overwrites | `lm_integrator` |
| `lm view list [--scope TAG]` | `NAME · SCOPE · TITLE` — a scope-bound user sees only the granted scopes' views | `lm_user` |
| `lm view get NAME [-o FILE]` | the manifest YAML verbatim | `lm_user` |
| `lm view rm NAME` | removes the view for good, no prompt | `lm_integrator` |

```
$ lm view rm acme-orders
view acme-orders deleted
$ lm view rm acme-orders
Error: API error 404: no saved view named 'acme-orders'
```

- There is no disable: a saved view is live from `apply` until `rm`.
- `apply` and `rm` are scope-bound: a scope-bound integrator cannot publish or remove a view
  outside their grants.
- Every apply and removal is recorded in the [audit trail](../security/index.md#the-audit-trail)
  — actor, name, scope, and for an apply the manifest's SHA-256.
- The store is shared by all tenants of the deployment; every render reads only your tenant's rows.

A user holding only `scope:stock` sees nothing of the desk:

```
$ lm view list
NAME  SCOPE  TITLE
$ lm view list --scope desk
Error: access refused (403): scope not granted
```

The stored name must be a URL-safe slug, and four names are reserved:

```
$ lm view apply -f docs/apps/demo/views/62-refused-reserved-name.yaml
Error: API error 400: metadata.name must be a URL-safe slug ([a-z0-9_-], starting with a letter or digit) and not one of [detail, render, apply, generate]
```

## Generate a view with the AI

Describe the need; the hub's model selects the table, the columns and the filters, the hub
validates them like a render, and writes the YAML. The manifest is yours to review and keep.

```
$ lm view generate desk "les clients de Lyon" -o lyon.yaml
manifest written to lyon.yaml — review it, then: lm view -f lyon.yaml
$ cat lyon.yaml
# asked for: les clients de Lyon
# kept: city=Lyon — a condition of that description not listed here is NOT enforced
# lm view -f clients_of_lyon.yaml --filter 'city=Lyon'
apiVersion: "apps.lumnik.io/v1"
kind: "View"
metadata:
  name: "clients_of_lyon"
spec:
  scope: "desk"
  table: "connector.v_acmecustomer"
  title: "Clients of Lyon"
  columns:
  - "code"
  - "name"
  - "city"
  - "segment"
  - "credit_limit"
```

| Header line | Tells you |
|---|---|
| `# asked for:` | your sentence, on one line |
| `# kept:` | the filters the view enforces — anything else in your sentence is **not** enforced |
| `# lm view -f …` | a ready-to-run render; it names the file by the manifest's name, not your `-o` |

Within `generate`, `-o` is the output file, not the output format.

- A quoted value the model writes (`city='Lyon'`) is unquoted before validation.
- A filter on a column the model left out of `columns` adds that column.
- Generation is rate-limited per tenant: `lumnik.ratelimit.view-generate.rpm`, default 10 per
  minute; past it, `lm` prints `rate limited (429): rate limit exceeded, retry in Ns`.

### Generation contract

| Case | Answer |
|---|---|
| The scope cannot serve the description (*"les commandes"* over a scope with no orders) | the model **abstains**; you get its reason, never invented columns |
| The selection fails validation (unknown column, invalid filter) | one retry with the exact error; a second failure returns the error and the model's raw output |
| No card for the scope | `no schema for scope '…' — run discovery first` |
| No chat model on the hub | `generation abstained: no chat model is available on this hub — view generation needs one` |

Nothing checks that the model kept everything you asked for — read the `# kept:` line:

```
$ lm view generate desk "les commandes en litige de plus de 500 euros" -o litiges.yaml
manifest written to litiges.yaml — review it, then: lm view -f litiges.yaml
$ head -3 litiges.yaml
# asked for: les commandes en litige de plus de 500 euros
# kept: amount>500 — a condition of that description not listed here is NOT enforced
# lm view -f commandes_en_litige.yaml --filter 'amount>500'
```

Two conditions asked, one kept: *en litige* was dropped. Add the filter by hand, or rephrase.

"Run discovery first": `lm source rediscover --scope <tag>` rebuilds the
[schema card](../rag/http-api.md#schema-admin) (needs `lm_integrator` **and** `lm_admin`, and a
scope you hold). `lm source schema --scope <tag>` shows what the card holds.

## How to…

### Show one métier a filtered list on their phone

```bash
lm view apply -f docs/apps/demo/views/10-customers.yaml
```

**Result:** anyone holding `scope:desk` finds *Clients ACME* under the **Fiches** tab of the PWA
at `/app/`, with the same filter grammar as the CLI.

### Let the AI draft a view, then check it before anyone else sees it

```bash
lm view generate desk "les clients de Lyon" -o lyon.yaml
cat lyon.yaml                                             # review it — the third line is the suggested render
lm view -f lyon.yaml --filter city=Lyon                   # test it yourself
lm view apply -f lyon.yaml                                # only once it looks right
```

**Result:** only `apply` writes to the store. Nobody else sees the draft before that.

### Look at an export before any entity exists

Point `spec.table` at the raw `connector.t_*` table — `20-orders.yaml` above. **Result:** the
export's own columns, unlabelled, filterable the same way.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (`titel:`) | the message lists what `kind:View` honors — fix the spelling |
| render or apply refused: column `is not in the '…' schema for table …` | `spec.columns` names a column the scope's card does not have | fix the column name, or add it to the scope's entity |
| filter refused: `cannot filter on '…' — the view does not show it` | filtering on a column not listed in `spec.columns` | add the column to `spec.columns` |
| filter refused: `'…' is not an allowed value for …` | the entity declares a `values` domain for that column | one of the listed values — or fix the domain in the entity |
| filter refused: `'…' is not a number or ISO date — > needs an ordered value` | an ordering filter with a value that is neither | a number, or `YYYY-MM-DD` |
| apply answers with a warning on `spec.links[…].targetView` | the target view is not applied yet in that scope | apply the target; the link then works |
| apply refused: `spec.links[…].url: an external link is https://…` | `http:`, a relative address, or a host that is not written out | write the full `https://host/path/{column}` |
| following a link answers `Lien refusé : …` | the target refused the filter | fix the filter in `links[].filters`, or the target's columns |
| `403 … scope not granted` | you do not hold the `spec.scope` role | ask for that `scope:` role |
| apply refused: `metadata.name must be a URL-safe slug …` | the name is not a lowercase slug, or is `apply`/`render`/`detail`/`generate` | rename `metadata.name` |
| `rate limited (429): rate limit exceeded, retry in Ns` on generate | over 10 `lm view generate` calls per minute for the tenant | wait the seconds it names, then retry |
| `404 … no schema for scope '…' — run discovery first` | discovery has never run for that scope | `lm source rediscover --scope <tag>` (`lm_integrator` + `lm_admin`) |
| `generation abstained: …` / `generation failed: …` | the card cannot serve the description, the selection failed twice, or the hub has no chat model | rephrase, or write the filter by hand; `lm source schema --scope <tag>` shows what the card has |
| the title reads like a table name | no `spec.title`, and the entity declares no `label:` | set `spec.title`, or give the entity a `label:` |
| a list stops at exactly 1 000 rows | the render cap | narrow it with filters |
| `API error 403 (forbidden): no details from the server` | you lack the role: `lm_integrator` for apply and rm, `lm_user` for the rest | ask your administrator; no `lm` command lists your roles (`GET /api/bff/v1/me` returns them under `roles`) |

## See also

- [The demo desk](demo-desk.md) — the sources every screen here reads
- [Entities](../entities.md) — where the labels, the domain and the `_conflicts` ledger come from
- [Processes](processes.md) — react to what the view shows
- [The métier PWA](pwa.md) — where a saved view is displayed
- [Scopes](../rag/scopes.md) — the boundary a view renders within
