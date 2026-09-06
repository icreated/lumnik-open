# kind: View — see your data

> The first rung of the per-métier app ladder: a **declarative, read-only list** over a scope's
> table, rendered by the hub and displayed by `lm`. No code, no frontend — one YAML.

## The real manifest (from the WOW demo)

```yaml
--8<-- "docs/demo/wow-customer/clients-view.yaml"
```

## Fields

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | the view's name |
| `spec.scope` | yes | the scope (connector tag) whose [schema card](../rag/http-api.md#schema-admin) backs the view — the tables and columns discovery found for it |
| `spec.table` | no | defaults to the scope's single table; required if the scope has several — a scope's card lists both raw ingested tables and any `kind: Entity` views over them, so a view over one freshly-ingested source needs no Entity first |
| `spec.title` | no | display title (falls back to the table's label, then the table name) |
| `spec.columns[]` | yes (≥1) | columns to show — **every column must exist in the scope's card** |

That table is the whole surface: `apply` **refuses a key it does not honor** and names it, so a
misspelled `titel:` costs you a 400 rather than a silently missing title. The `apiVersion:` envelope
is accepted and ignored. Reads are untouched — a view already saved with an extra key still renders.

## Rendering

```bash
lm view -f clients-view.yaml                      # list
lm view -f clients-view.yaml --filter city=Lyon   # filtered list (repeatable, AND)
lm view C-200 -f clients-view.yaml                # one record's detail (matched on the view's
                                                  # first declared column — here customer_code)
```

What the filtered list and the detail print, on a view over a fused Customer entity:

```
$ lm view -f customers-view.yaml --filter city=Lyon
Customers · city=Lyon

Customer code  Name                City  Segment
C-001          Atelier Bernard     Lyon  GOLD
C-004          Boulangerie Morel   Lyon  GOLD
C-008          Librairie Fontaine  Lyon  BRONZE

$ lm view C-004 -f customers-view.yaml
Customers / C-004

  Customer code   C-004
  Name            Boulangerie Morel
  City            Lyon
  Segment         GOLD

  (!) Où les sources divergent
  City   t_customers «Lyon» · t_crm_contacts «Villeurbanne»
```

Column headers are the `label`s the entity manifest declares. The detail ends with the
`_conflicts` ledger when the sources disagree on that record — under the French heading
*Où les sources divergent*, "where the sources diverge".
For the CLI the manifest stays a **file you hold** — `lm view` posts it to
`POST /api/views/render` (list) or `POST /api/views/detail` (one record) and prints the result.
(A third endpoint, `POST /api/views/generate`, writes a manifest *for* you — see
[Generate a view with the AI](#generate-a-view-with-the-ai).)

Since the saved-view store, a manifest can **also live in the hub**, not just as a file you
hold. An integrator applies it with `POST /api/views/apply` — curated: the same parser +
schema-card gate as render, so a view that cannot render is not saved. Renderers without a
filesystem — the métier PWA at `/app/` — discover it through `GET /api/views` (scope-bound)
and fetch its YAML by name.

The CLI speaks to the store too:

| Command | Effect |
|---|---|
| `lm view apply -f FILE` | validate-then-upsert into the store |
| `lm view list` | lists views: `NAME` \| `SCOPE` \| `TITLE` |
| `lm view get NAME` | the manifest YAML verbatim (`-o file` saves it) |

```
$ lm view apply -f customers-view.yaml
applied: customers-desk (scope retail)
$ lm view list
NAME            SCOPE   TITLE
customers-desk  retail  Customers
```

That's the full round-trip: generate a manifest on one machine, apply it, get it back on
another.

In the TUI, `:view` opens the same file interactively:

| Key | Action |
|---|---|
| `f` | filters |
| `Enter` | opens detail |
| `g` | generates a manifest from a sentence |
| `a` | applies the rendered view to the store |

The detail includes the `_conflicts` ledger of the fused entity — you see *which source
disagreed* on a field, not a silently-picked winner.

Every view **read** endpoint requires the `lm_user` role. `POST /api/views/apply` is the
exception: it requires `lm_integrator` (alone — not `lm_user` on top). And the whole
surface, apply included, lives inside the [métier-scope boundary](../rag/scopes.md): a
manifest whose `spec.scope` you are not granted is the uniform
`403 {"error":"scope not granted"}` — render, detail, generate, the store reads, and a
scope-bound integrator publishing outside their grant alike. (Workflow apply is **not**
scope-bound — the asymmetry is named on [its page](workflows.md#applying-it).)

Rendering is bounded: at most **1000 rows** and a **5-second statement timeout** per read —
the same read-only, tenant-isolated boundary as ask. Rows come back ordered by the first
declared column (which is also the detail key). Both bounds speak when hit: a render cut
at the cap returns `"truncated": true` alongside the rows (narrow it with filters), and a
query that outlives the timeout is refused with a spoken 400 — never a bare 500.

## The saved-view store

`POST /api/views/apply` validates against the **applying tenant's** schema card, then upserts
into the store — re-applying the same name overwrites; there is no disable flag and no delete
endpoint yet (a saved view is live from the moment it is applied). Every apply **is recorded
in the [audit trail](../security/gdpr.md#where-personal-data-can-live)** — actor, name, scope
and the manifest's SHA-256 — so "who changed which screen when" is recoverable even though
the store itself keeps only the latest version. Two refusals exist only here:

- **name rules** — the stored name is `metadata.name` lowercased, and must be a URL-safe slug
  (`[a-z0-9_-]`, starting with a letter or digit): it becomes the store key and a URL path
  segment in `GET /api/views/{name}`;
- **reserved names** — `apply`, `render`, `detail`, `generate` are refused: a view so named
  would shadow a sibling endpoint — it would list but never fetch.

Reads are `lm_user`, scope-bound with opt-in semantics:

- `GET /api/views` — summaries (`name`, `scope`, `title`); an unbound user sees all, a
  métier-bound user only the granted scopes' views. `?scope=` narrows — and asking for an
  ungranted scope is the uniform 403, never a lying empty list;
- `GET /api/views/{name}` — the manifest YAML, byte-for-byte as applied (`application/yaml`);
  404 for an unknown name, the uniform 403 when the view's scope is not granted.

Stored manifests are deployment-global (the entity-manifest posture — one store, all tenants),
but validation is tenant-local at apply time and every render of *data* through a view stays
tenant-isolated.

## Filter grammar

| Form | Meaning |
|---|---|
| `col=value` | exact match; on a column with declared values, an out-of-domain value is **refused** |
| `col~text` | substring, case-insensitive |
| `col>v` `col<v` `col>=v` `col<=v` | ordering — numeric or ISO-date columns only |

## Refusal rules

A view never lies with an empty table:

- an **unknown column in `columns:`** → refused: the column "is not in the scope's schema
  for the table — fix the column name or add it to the scope's entity";
- a **filter on a column the view does not show** → refused: the view "does not show it;
  declare it in `spec.columns` to filter on it" — filters run over the view's own columns,
  not the whole card;
- a **malformed filter** → refused, not silently ignored;
- an `=` filter with a value **outside the column's declared domain** → refused with the
  domain hint (the same contract as the [domain guard](../transparency/ask-honesty.md)).

A zero-row result you *do* get is then meaningful: the filter is valid and genuinely matches
nothing.

## Generate a view with the AI

Describe the need; the hub's model selects the table, columns, and filters — then the SAME
gate that guards every render (unknown columns and invalid filters are refused) validates
the selection, and the server assembles the YAML deterministically. The manifest is yours
to review, edit, and keep: the file IS the app.

```bash
lm view generate clients "les clients gold de Lyon" -o gold.yaml
cat gold.yaml                                       # review — the suggested render command rides as the first line
lm view -f gold.yaml --filter 'segment=GOLD' --filter 'city~Lyon'
```

The CLI posts `{scope, description}` to `POST /api/views/generate` and receives the YAML.
The first line is a **ready-to-run comment**: its filters passed the same validation as a
real render before being suggested, and with `-o` the CLI points the command at *your*
filename. Note: within `generate`, `-o` means the **output file** (it shadows the global
`-o` output-format flag, which has no role here — the output is always the manifest).

What the model selects is **bounded**: it never writes YAML (the server assembles it), the
name is derived server-side, and every column and filter must survive the card-backed gate.
A generated manifest is indistinguishable from a hand-written one.

Generation is also the one **rate-limited** view surface — the `view-generate` bucket of the
hub's fixed-window limiter (`lumnik.ratelimit.view-generate.rpm`, default 10 requests/minute
per tenant). Past the limit the answer is `429 {"error":"rate limit exceeded"}` with a
`Retry-After` header; wait it out and retry.

### Generation contract

| Case | Answer |
|---|---|
| The scope cannot serve the description ("les commandes" over a clients scope) | the model **abstains**; you get the reason, never invented columns |
| The selection fails the gate (unknown column, invalid filter) | ONE corrective retry with the exact error; a second failure returns the error carrying the model's raw output |
| No card for the scope | `no schema for scope '…' — run discovery first` |

Refusals surface **verbatim**: an abstention is
`400 {"error":"generation abstained: <the model's reason>"}` — the reason is the model's own
sentence, not a paraphrase — and a post-retry failure carries the model's raw output in a
`raw` field, so you always see what actually happened.

"Run discovery first" means an admin gesture: `lm source rediscover --scope <tag>` rebuilds
and persists the [schema card](../rag/http-api.md#schema-admin). It lives under
`/api/platform/*`, so it needs **both** roles: `lm_integrator` to pass the platform door and
`lm_admin` for the endpoint itself — holding only one gets you a `403`. `lm source
schema --scope <tag>` shows what a card currently holds, and prints the same hint itself
when it's empty.

## How to…

### Show one métier a filtered list on their phone

```bash
lm view apply -f clients-view.yaml
```

**Result:** the manifest lands in the saved-view store. Anyone holding the `spec.scope`
scope now finds it under the **Fiches** tab of the PWA at `/app/` — the filter bar speaks
the same grammar (`col=val`, `col~text`, `col>v`) as the CLI, no file needed on their device.

### Let the AI draft a view, then check it before anyone else sees it

```bash
lm view generate clients "les clients gold de Lyon" -o gold.yaml
cat gold.yaml                                                       # review it
lm view -f gold.yaml --filter 'segment=GOLD' --filter 'city~Lyon'   # test it yourself
lm view apply -f gold.yaml                                          # only once it looks right
```

**Result:** generating and testing never write to the saved-view store — only `apply`
does. Nobody else sees the draft until you run that last command.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (e.g. `titel:`) | the message lists what `kind:View` honors — fix the spelling |
| render/apply refused: column "is not in the scope's schema for the table" | `spec.columns` names a column the scope's card doesn't have | fix the column name, or add it to the scope's entity |
| filter refused: view "does not show it" | filtering on a column not listed in `spec.columns` | add the column to `spec.columns` |
| `403 {"error":"scope not granted"}` | you don't hold the `spec.scope` role | ask an integrator to grant that `scope:` role |
| apply refused: `metadata.name must be a URL-safe slug …` | the name isn't a lowercase slug, or is `apply`/`render`/`detail`/`generate` | rename `metadata.name` |
| `429 {"error":"rate limit exceeded"}` on generate | over 10 `lm view generate` calls/minute for the tenant | wait for `Retry-After`, then retry |
| `404 {"error":"no schema for scope '…' — run discovery first"}` (apply or generate) | discovery has never run for that scope — no schema card exists | `lm source rediscover --scope <tag>` — needs **both** `lm_integrator` and `lm_admin` |
| `generation abstained: …` | the scope's schema card can't answer the description | rephrase, or run `lm source schema --scope <tag>` to see what the card actually has |
| `403` with an **empty body** (not `{"error": "…"}` like the scope refusal above) | you don't hold the role the endpoint requires (`lm_integrator` for apply, `lm_user` for everything else) | check your roles — `GET /api/bff/v1/me` returns them under `roles` — then ask whoever manages your Keycloak roles to grant the missing one |

## See also

- [Processes](processes.md) — react to what the view shows.
- [Scopes](../rag/scopes.md) — the boundary a view renders within.
