# kind: View — see

> The first rung of the per-métier app ladder: a **declarative, read-only list** over a
> scope's table, rendered by the hub and displayed by `lm`, the TUI or the PWA. No code, no
> frontend — one YAML that says which columns, and a filter grammar that refuses what it cannot
> honour instead of showing an empty table.

| | |
|---|---|
| **Kind** | `View` — `apps.lumnik.io/v1` |
| **Over** | any table of a scope's card: a raw `connector.t_*`, or a fused `connector.v_*` |
| **Renders** | `lm view -f FILE` (list, filters, one record's detail); the TUI's `:view`; the PWA's *Fiches* tab |
| **Store** | `lm view apply` — `lm_integrator`, validate-then-upsert, scope-bound; reads `lm_user` |
| **Bounds** | 1 000 rows and a 5-second statement timeout per read, read-only, tenant-isolated |
| **Edition** | Open |

A view is the smallest app there is: a title, a table and the columns worth looking at. It reads
through the same read-only, tenant-isolated boundary as the chat, it speaks the labels an
[entity](../entities.md) declared, and it ends a record's detail with the sources that disagree
about it. The file **is** the app — hand-written, or [drafted by the AI](#generate-a-view-with-the-ai)
and reviewed by you.

## Features

- **[Over a raw table or a fused entity.](#a-view-over-a-raw-table)** A freshly landed export is
  viewable as it is; an entity's view carries its labels and its `_conflicts` ledger.
- **[A filter grammar that refuses.](#filter-grammar)** Exact, substring and ordering filters —
  an unknown column, a column the view does not show, a malformed filter or a value outside a
  declared domain is refused, so a zero-row result is always meaningful.
- **[One record's detail, disagreements included.](#quickstart)** `lm view KEY` prints a
  record and, under *Où les sources divergent*, which source said what.
- **[A store the PWA discovers.](#the-saved-view-store)** `lm view apply` publishes a manifest to
  the hub; anyone holding the scope finds it on their phone, no file needed.
- **[Drafted by the AI, gated like a render.](#generate-a-view-with-the-ai)** The model selects
  table, columns and filters; the same gate that guards every render validates them; the server
  writes the YAML.
- **[Bounded, spoken.](#rendering)** A render cut at 1 000 rows says `truncated: true`; a query that
  outlives 5 seconds is a spoken `400`, never a bare `500`.

## Quickstart

!!! note "Every screen on this page reads the demo desk that ships with the repo"
    Two customer exports fused into one entity and an order book, under `docs/apps/demo/`. Land
    them and apply the entity first — [The demo desk](demo-desk.md) has the three commands and
    the hand count.

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

The headers are the `label`s the entity manifest declares — *Code client*, *Raison sociale* —
not the canonical names. Fourteen rows, ordered by the first declared column. Narrow it:

```
$ lm view -f docs/apps/demo/views/10-customers.yaml --filter segment=GOLD --filter 'credit_limit>1000'
Clients ACME · segment=GOLD · credit_limit>1000

Code client  Raison sociale            Ville   Segment  Encours autorisé
P-1005       Transmission Aarhus 1005  Aarhus  GOLD     1185
P-1008       Filtration Lyon 1008      Lyon    GOLD     1596
```

Filters are repeatable and ANDed, and the title says which ones applied. Then one record — the
first declared column is the key:

```
$ lm view P-1008 -f docs/apps/demo/views/10-customers.yaml
Clients ACME / P-1008

  Code client        P-1008
  Raison sociale     Filtration Lyon 1008
  Ville              Lyon
  Segment            GOLD
  Encours autorisé   1596

  (!) Où les sources divergent
  Ville   t_acme_desk_erp «Lyon» · t_acme_desk_crm «Villeurbanne»
```

The block under *Où les sources divergent* ("where the sources diverge") is the entity's
`_conflicts` ledger for that record: the ERP says Lyon, the CRM says Villeurbanne, the ERP won
because it is listed first, and the CRM's value was kept. A record everyone agrees on ends
differently:

```
$ lm view P-1005 -f docs/apps/demo/views/10-customers.yaml
Clients ACME / P-1005

  Code client        P-1005
  Raison sociale     Transmission Aarhus 1005
  Ville              Aarhus
  Segment            GOLD
  Encours autorisé   1185

  ✓ toutes les sources concordent
```

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | the view's name — in the store, lower-cased, a URL-safe slug (`[a-z0-9_-]`, starting with a letter or digit) |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) backs the view — the tables and columns discovery found for it |
| `spec.table` | no | defaults to the scope's single table; required when the scope has several. A card lists raw `connector.t_*` tables **and** the `connector.v_*` entity views over them |
| `spec.title` | no | the display title — falls back to the table's label (a fused entity's `label:`, see [Entities](../entities.md)), then its name |
| `spec.columns[]` | yes (≥ 1) | the columns to show, in order. **Every one must exist in the scope's card.** The first is the sort order and the detail key |

That table is the whole surface, and the store refuses a key it does not honour:

```
$ lm view apply -f docs/apps/demo/views/61-refused-unknown-key.yaml
Error: API error 400: unknown key(s) in the manifest: spec.titel — kind:View honors root {apiVersion, kind, metadata, spec}, metadata {name}, spec {columns, scope, table, title}
```

A misspelled `titel:` costs a `400` at apply rather than a silently missing title. Rendering a
file (`lm view -f`) is more lenient — the same manifest renders, under the table's name as its
title — so a view already saved with an extra key still renders. The `apiVersion:` envelope is
accepted and ignored.

### A view over a raw table

No entity is needed. A scope's card lists the raw tables its connectors land, and a view can
point straight at one — here the order book, exactly as the ERP exported it:

```yaml
--8<-- "docs/apps/demo/views/20-orders.yaml"
```

```
$ lm view -f docs/apps/demo/views/20-orders.yaml --filter 'status~litige'
Commandes · status~litige

order_no   code    status     amount   ordered_on
SO-000006  P-1008  EN LITIGE  4100.00  2026-08-07
SO-000010  P-1004  EN LITIGE  77.00    2026-08-12
SO-000013  P-1007  EN LITIGE  860.00   2026-08-14
```

The headers are the raw column names, because nothing declared a label for them; the status
reads `EN LITIGE` because that is what the export says. A [`kind: Workflow`](workflows.md) is
what tells the hub — and the chat — what those values *mean*.

## Rendering

```bash
lm view -f FILE                                # the list
lm view -f FILE --filter city=Lyon             # filtered; repeatable, ANDed
lm view KEY -f FILE                            # one record — KEY matches the first declared column
```

The manifest stays a **file you hold**: `lm view` posts it to `POST /api/views/render` (list) or
`POST /api/views/detail` (one record) and prints the answer. Every read endpoint requires
`lm_user` and lives inside the [métier-scope boundary](../rag/scopes.md): a manifest whose
`spec.scope` you are not granted is the uniform `403 {"error":"scope not granted"}`.

Rendering is bounded: at most **1 000 rows** and a **5-second statement timeout** per read — the
same read-only, tenant-isolated boundary as ask. Rows come back ordered by the first declared
column. Both bounds speak when hit: a render cut at the cap returns `"truncated": true` alongside
the rows (narrow it with filters), and a query that outlives the timeout is refused with a spoken
`400`, never a bare `500`.

In the TUI, `:view` opens the same file interactively:

| Key | Action |
|---|---|
| `f` | filters |
| `Enter` | one record's detail |
| `g` | generate a manifest from a sentence |
| `a` | apply the rendered view to the store |

## Filter grammar

| Form | Meaning |
|---|---|
| `col=value` | exact match; on a column with declared `values`, an out-of-domain value is **refused** |
| `col~text` | substring, case-insensitive |
| `col>v` `col<v` `col>=v` `col<=v` | ordering — `v` must be a number or an ISO-8601 date; the column's values are cast for the comparison, and a row whose value has neither shape is simply not matched |

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
SO-000013  P-1007  EN LITIGE  860.00   2026-08-14
```

### Refusal rules

A view never lies with an empty table. Five things are refused, each with the sentence that
fixes it:

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

- an **unknown column in `columns:`** — the card does not have it;
- a **filter on a column the view does not show** — filters run over the view's own columns, not
  the whole card;
- a **malformed filter**;
- an `=` filter with a value **outside the column's declared domain** — the same contract as the
  chat's [domain guard](../transparency/ask-honesty.md), fed by the entity's `values`;
- a **quoted value**. Quotes are ordinary characters here, so `city='Lyon'` used to be compared to
  the column quotes and all: accepted, matching nothing, saying nothing — the one thing this
  grammar exists to prevent. Write `city=Lyon`; the shell quotes in `--filter 'city=Lyon'` are the
  shell's, not the value's. Only a *balanced* pair is refused, because a lone apostrophe is data
  here (`name~l'ecole` is a real search).

A zero-row result you *do* get is then meaningful: the filter is valid and genuinely matches
nothing.

## The saved-view store

A manifest can also live **in the hub**. An integrator publishes it, and renderers without a
filesystem — the métier PWA at `/app/` — discover it under the scope:

```
$ lm view apply -f docs/apps/demo/views/10-customers.yaml
applied: acme-customers (scope desk)
$ lm view apply -f docs/apps/demo/views/20-orders.yaml
applied: acme-orders (scope desk)
$ lm view list
NAME            SCOPE   TITLE
acme-customers  desk    Clients ACME
acme-orders     desk    Commandes
$ lm view get acme-customers
kind: View
apiVersion: apps.lumnik.io/v1
metadata:
  name: acme-customers
spec:
  scope: desk
  table: connector.v_acmecustomer
  title: Clients ACME
  columns: [code, name, city, segment, credit_limit]
```

That is the full round-trip: write a manifest on one machine, apply it, get it back
byte-for-byte on another (`-o file` saves it).

| Command | Effect |
|---|---|
| `lm view apply -f FILE` | validate-then-upsert: the same parser and card gate as a render, so a view that cannot render is never saved; re-applying a name overwrites |
| `lm view list` | `NAME · SCOPE · TITLE` — an unbound user sees every view, a métier-bound user only the granted scopes' |
| `lm view get NAME` | the manifest YAML verbatim |

- **`apply` is `lm_integrator`** (alone — not `lm_user` on top) and **scope-bound**: a
  scope-bound integrator cannot publish a view outside their grants — the same boundary every
  app-ladder door applies, [workflow apply](workflows.md#applying-it) included.
- **Every apply is recorded in the [audit trail](../security/gdpr.md#where-personal-data-can-live)** —
  actor, name, scope and the manifest's SHA-256 — so "who changed which screen when" is
  recoverable even though the store keeps only the latest version.
- **There is no delete and no disable** yet: a saved view is live from the moment it is applied.
- **Two refusals exist only here.** The stored name must be a URL-safe slug, and four names would
  shadow a sibling endpoint:

    ```
    $ lm view apply -f docs/apps/demo/views/62-refused-reserved-name.yaml
    Error: API error 400: metadata.name must be a URL-safe slug ([a-z0-9_-], starting with a letter or digit) and not one of [detail, render, apply, generate]
    ```

Reads are `lm_user`, scope-bound with opt-in semantics: `GET /api/views` lists summaries
(`?scope=` narrows, and asking for an ungranted scope is the uniform `403`, never a lying empty
list); `GET /api/views/{name}` returns the YAML (`application/yaml`; `404` unknown, `403` when its
scope is not granted). Stored manifests are deployment-global — one store, all tenants, the
entity-manifest posture — but validation is tenant-local at apply time and every render of data
stays tenant-isolated.

## Generate a view with the AI

Describe the need; the hub's model selects the table, the columns and the filters; then the
**same gate that guards every render** validates the selection, and the server assembles the YAML
deterministically. The manifest is yours to review, edit and keep.

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

Three comment lines, and each answers a different question. **What you asked** is echoed back,
collapsed to one line. **What the view keeps** lists the filters that survived — and says plainly
that anything else in your sentence is not enforced, because the server cannot know what the model
dropped; only you can, and the two lines sit next to each other so that comparison takes one
glance. **The command** is ready to run: its filters passed the same validation as a real render
before being suggested. (The command names the manifest by ITS name, not by your `-o` filename.)

A filter's value is a bare literal in this grammar, so the SQL quotes a model tends to write around
one (`city='Lyon'`) are shed before the gate sees it. A quoted value you write by hand is refused
instead — see [Refusal rules](#refusal-rules).
Within `generate`, `-o` means the output file — it shadows the global output-format flag, which
has no role here.

What the model selects is **bounded**: it never writes YAML (the server assembles it), the name
is derived server-side, and every column and filter must survive the card-backed gate. A
generated manifest is indistinguishable from a hand-written one. Generation is also the one
**rate-limited** view surface — the `view-generate` bucket of the hub's fixed-window limiter
(`lumnik.ratelimit.view-generate.rpm`, default 10 requests/minute per tenant); past it the answer
is `429 {"error":"rate limit exceeded"}` with a `Retry-After` header.

### Generation contract

| Case | Answer |
|---|---|
| The scope cannot serve the description (*"les commandes"* over a scope with no orders) | the model **abstains**; you get its reason, never invented columns |
| The selection fails the gate (unknown column, invalid filter) | one corrective retry with the exact error; a second failure returns the error, carrying the model's raw output |
| No card for the scope | `no schema for scope '…' — run discovery first` |

Refusals surface **verbatim** — an abstention is `400 {"error":"generation abstained: <the
model's reason>"}` — and the gate is the same one every render passes.

A view must show the column it filters on, or its rows are narrowed by something the reader cannot
see. When a generated filter names a column of the table that the model left out of `columns`, that
column is **added** rather than the generation refused: the rule is satisfied instead of weakened,
and you get the column your filter acts on. A filter on a column the *table* does not have keeps
its refusal.

What is *not* checked is whether the model kept everything you asked for — nothing can check that
but you, which is why the header says so:

```
$ lm view generate desk "les commandes en litige de plus de 500 euros" -o litiges.yaml
manifest written to litiges.yaml — review it, then: lm view -f litiges.yaml
$ head -3 litiges.yaml
# asked for: les commandes en litige de plus de 500 euros
# kept: amount>500 — a condition of that description not listed here is NOT enforced
# lm view -f commandes_en_litige.yaml --filter 'amount>500'
```

Two conditions asked, one kept: *en litige* was dropped, and the second line is where you see it in
one glance instead of diffing the YAML against your own sentence. Add the filter by hand, or
rephrase and generate again.

"Run discovery first" is an admin gesture: `lm source rediscover --scope <tag>` rebuilds and
persists the [schema card](../rag/http-api.md#schema-admin). It lives under `/api/platform/*`, so
it needs **both** `lm_integrator` and `lm_admin` — and, like every other door working inside a
métier scope, it refuses a scope the caller does not hold. `lm source schema --scope <tag>` shows
what a card currently holds. Both are served by the hub, so both are in the open edition.

## How to…

### Show one métier a filtered list on their phone

```bash
lm view apply -f docs/apps/demo/views/10-customers.yaml
```

**Result:** the manifest lands in the store. Anyone holding `scope:desk` finds *Clients ACME*
under the **Fiches** tab of the PWA at `/app/`; the filter bar speaks the same grammar
(`col=val`, `col~text`, `col>v`) as the CLI, and no file is needed on their device.

### Let the AI draft a view, then check it before anyone else sees it

```bash
lm view generate desk "les clients de Lyon" -o lyon.yaml
cat lyon.yaml                                             # review it — the first line is the suggested render
lm view -f lyon.yaml --filter city=Lyon                   # test it yourself
lm view apply -f lyon.yaml                                # only once it looks right
```

**Result:** generating and testing never write to the store — only `apply` does. Nobody else
sees the draft until you run that last command.

### Look at an export before any entity exists

Point `spec.table` at the raw `connector.t_*` table — `20-orders.yaml` above. **Result:** the
export's own columns, unlabelled, filterable the same way. Add an entity later for labels and a
domain; add a workflow for what the status values mean.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (`titel:`) | the message lists what `kind:View` honors — fix the spelling |
| render or apply refused: column `is not in the '…' schema for table …` | `spec.columns` names a column the scope's card does not have | fix the column name, or add it to the scope's entity |
| filter refused: `cannot filter on '…' — the view does not show it` | filtering on a column not listed in `spec.columns` | add the column to `spec.columns` |
| filter refused: `'…' is not an allowed value for …` | the entity declares a `values` domain for that column | one of the listed values — or fix the domain in the entity |
| filter refused: `'…' is not a number or ISO date — > needs an ordered value` | an ordering filter with a value that is neither | a number, or `YYYY-MM-DD` |
| `403 {"error":"scope not granted"}` | you do not hold the `spec.scope` role | ask an integrator to grant that `scope:` role |
| apply refused: `metadata.name must be a URL-safe slug …` | the name is not a lowercase slug, or is `apply`/`render`/`detail`/`generate` | rename `metadata.name` |
| `429 {"error":"rate limit exceeded"}` on generate | over 10 `lm view generate` calls per minute for the tenant | wait for `Retry-After`, then retry |
| `404 {"error":"no schema for scope '…' — run discovery first"}` | discovery has never run for that scope | `lm source rediscover --scope <tag>` — needs **both** `lm_integrator` and `lm_admin`; a scope-bound caller gets the uniform `403 {"error":"scope not granted"}` for a scope they do not hold |
| `generation abstained: …` / `generation failed: …` | the card cannot serve the description, or the model's selection failed the gate twice | rephrase, or write the filter by hand; `lm source schema --scope <tag>` shows what the card has |
| the title reads like a table name | no `title` — or a misspelled one, which apply would have refused — and the backing entity declares no `label:` either, so both rungs below it are the name | set `spec.title`, or give the entity a `label:` so every view of it inherits one |
| `403` with an **empty body** | you do not hold the role the endpoint requires (`lm_integrator` for apply, `lm_user` for everything else) | `GET /api/bff/v1/me` returns your roles under `roles` |

## See also

- [The demo desk](demo-desk.md) — the sources every screen here reads
- [Entities](../entities.md) — where the labels, the domain and the `_conflicts` ledger come from
- [Processes](processes.md) — react to what the view shows
- [The métier PWA](pwa.md) — where a saved view is displayed
- [Scopes](../rag/scopes.md) — the boundary a view renders within
