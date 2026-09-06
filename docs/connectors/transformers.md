# Transformers

> Repair legacy data as it flows in — mojibake, `DD/MM/YYYY` dates, numbers in parentheses —
> declared in the manifest's `spec.mapping` block, no code.

## Where this applies

**Read this before promising a customer their data will be cleaned:**

- **`csv-file` connector runs only.** The csv-file connector parses `spec.mapping` and applies
  its hooks before writing.
- **jdbc and kafka write a 1:1 dump** — no `spec.mapping`. jdbc has one narrow knob for a source
  column colliding with a hub system column: `source.tables[].rename`, see
  [JDBC: Target](jdbc.md#target).
- **REST/GraphQL/SOAP** transform in the `promote:` block's `transform:` enum — a short fixed
  list, see [REST: the `transform:` enum](#rest-the-transform-enum).
- **Not the one-shot `lm csv ingest`.** That path writes directly; its `spec.mapping` (if any)
  is ignored. To transform, apply a `csv-file` connector manifest and run it.
- **Dirty status values are a different tool's job** — declare them as
  [workflow aliases](../apps/workflows.md#alias-semantics), which map legacy statuses without
  touching the data.
- **Rehearse before the live feed.** [`lm validate`](index.md#the-lifecycle-every-connector-same-three-commands)
  checks the manifest's shape, not your data. Run against a sample file first, read the result,
  then point the same manifest at the real feed.

## Three tiers, one order

```yaml
spec:
  mapping:
    dataset:                    # tier 1 — the whole chunk, once
      - skip-empty-rows: {}
    row:                        # tier 2 — one record; may emit 0..N rows
      - map-columns: { rename: { CUST_NM: name } }
    cell:                       # tier 3 — one value, mutated in place
      - fix-mojibake: { columns: [name] }
```

```mermaid
flowchart TB
  F["file chunk"] --> D
  subgraph D["1 · dataset — the whole chunk, once"]
    D1["require-headers · skip-empty-rows · filter-where · sort-by · enrich-context"]
  end
  D --> R
  subgraph R["2 · row — one record in, 0..N out"]
    R1["map-columns · coalesce · defaults · validate-row · split-row"]
  end
  R --> C
  subgraph C["3 · cell — one value, in place"]
    C1["clean · fix-mojibake · parse-* · lookup · mask · regex-* · split · to-uuid · kotlin …"]
  end
  C --> T[("target table")]
  D1 -. "filter-where / skip-empty-rows" .-> S["records_skipped"]
  R1 -. "validate-row" .-> Q["records_skipped + DLQ"]
  C1 -. "unreadable value → null" .-> E["cell_errors"]
```

- **Across tiers the order is fixed**: dataset → row → cell, always.
- **Within a tier, hooks run top to bottom** as listed — order is yours, and it matters
  (`validate-row` before `split-row`; `regex-extract` before the `default` that fills its misses).
- **One entry per hook**: `- hook-name: { params }`. An unknown name fails the run
  (`unknown dataset hook: <name>`).
- **Three exits, three counters.** A row `filter-where` or `skip-empty-rows` excludes →
  `records_skipped`. A row `validate-row` drops → `records_skipped` **and** the DLQ. A value a
  cell hook cannot read → `null` in a row that is still written, counted in `cell_errors`.
  Nothing is dropped silently.

## Dataset hooks — the whole chunk, once

Run **once per chunk**, before the row and cell tiers. A dataset hook may rewrite the whole
chunk, never more — it never sees the full file. Every csv-file run, local or remote (sftp/s3),
commits in fixed **500-row chunks**, and a large file spans as many chunks as it needs. A
chunk and a file are different things: a remote chunk holds rows from one file only, while a
local glob run fills the chunk from the **next** file when the current one runs out.
Consequence: `sort-by` orders **within a chunk** — not the file, not the run.

### `require-headers` — refuse a file with the wrong shape

```yaml
dataset:
  - require-headers: { columns: [c_bpartner_id, name], any-of: [created, isactive] }
```

| the file's header | result |
|---|---|
| `c_bpartner_id,name,created,isactive` | passes |
| `c_bpartner_id,name,created` | passes — `columns` all present, one of `any-of` present |
| `c_bpartner_id,name` | **fails the run**: `none of required headers found: [created, isactive]` |
| `name,created` | **fails the run**: `required header missing: c_bpartner_id` |

- `columns` — every name must exist. `any-of` — at least one must.
- With `has_header: false`, "the header" is the `parser.columns` list you declared.
- An empty chunk passes: there is no header to check.

### `skip-empty-rows` — blank lines in the export

```yaml
dataset:
  - skip-empty-rows: {}
```

Before:

```csv
code,name
C1,Acme Corp
,
C2,Globex
```

After: `C1` and `C2`. The blank row (every cell null or whitespace) is counted in
`records_skipped`, reason `PolicyExcluded("skip-empty-rows")` — **not** sent to the DLQ: a
declared filter is not a quality failure.

### `filter-where` — keep only the rows you asked for

```yaml
dataset:
  - filter-where: { column: status, in: [ACTIVE, PENDING] }
```

| `status` | `in: [ACTIVE, PENDING]` | `equals: ACTIVE` | `regex: "[0-9]+"` |
|---|---|---|---|
| `ACTIVE` | kept | kept | dropped |
| `PENDING` | kept | dropped | dropped |
| `1042` | dropped | dropped | kept |
| `10a` | dropped | dropped | dropped — `regex` is a **full** match |
| *(null)* | dropped | dropped | dropped — a null compares as `""` |

- Declare any of `equals`, `in`, `regex`; several combine with **AND**.
- A dropped row is counted in `records_skipped`, reason `PolicyExcluded("filter-where")` — not
  DLQ'd.
- A bad `regex` is refused when the run starts, naming the hook and the knob.

### `sort-by` — order within the chunk

```yaml
dataset:
  - sort-by: { columns: [c_bpartner_id] }        # max-rows: 100000 by default
```

| before | after |
|---|---|
| `1000280`, `1000001`, *(null)*, `1000117` | *(null)*, `1000001`, `1000117`, `1000280` |

- Lexicographic (text order — `9` sorts after `10`), in memory, **within the 500-row chunk**
  — a file longer than that is sorted piecewise, never as a whole; on a local glob run a chunk
  may even straddle two files.
- Nulls sort first, as empty strings.
- Over `max-rows` the run refuses: `sort-by max-rows exceeded: <n> > <max> (input must be
  pre-sorted or increase max-rows)`.

### `enrich-context` — stamp provenance on every row

```yaml
dataset:
  - enrich-context: { source_system: siebel, ingested_at: "${now}" }
```

Before:

```csv
code,name
C1,Acme Corp
```

After:

```csv
code,name,source_system,ingested_at
C1,Acme Corp,siebel,2026-08-20T14:03:11.482Z
```

- Adds a constant column to every row of the chunk; `${now}` resolves once, to the ingestion
  instant.
- An existing value in that column is **never overwritten**.
- It is also the cheapest way to hand a cell hook a test value — the
  [full example](#one-manifest-every-transformer) mints its dirty inputs this way.

## Row hooks — one record in, 0..N out

A row hook can drop, duplicate, or reshape rows.

### `map-columns` — legacy column names (`CUST_NM`)

```yaml
row:
  - map-columns:
      rename: { CUST_NM: name, CUST_CITY: city }
      drop: [internal_flag]
```

Before:

```csv
CUST_NM,CUST_CITY,internal_flag
Bénédict Martin,Montréal,X
```

After:

```csv
name,city
Bénédict Martin,Montréal
```

- Renames first, **then** drops — so `drop` names the column *as it is by then*: for a column
  renamed in the same hook, its **new** name. Naming the old one removes nothing.
- A renamed column keeps its place even when the value is empty: the target column always
  appears, so the table's shape does not depend on which row arrived first.

!!! tip "Six shapes are refused when the run starts — one is only counted"
    `lm apply` accepts these today; the run refuses them **before its first row** — the engine
    checks every hook's parameters once per chunk, even an empty one — naming the entry:

    - `CUST_NM:` with nothing after the colon.
    - a target that is not a plain column name (letters, digits, underscore, not starting with
      a digit).
    - a target that is one of the seven hub columns (`id`, `tenant_id`, `_row_hash`,
      `_ingested_at`, `_source`, `_run_id`, `_mapping_version`). Renaming **away** from one is
      what this hook is for; renaming *into* one recreates the collision.
    - two source columns renamed onto the **same** target — one would be lost.
    - a `rename:` that is not a map (a list, a bare value).
    - a `drop:` that is not a list — `drop: internal_flag` instead of `drop: [internal_flag]`.
      The one that matters most: **a drop that does nothing publishes the column you meant to
      keep out.**

    One shape depends on the data, so it cannot be refused: **the target already exists in the
    row.** `rename: { customer_id: ref }` on a file that already has a `ref` column overwrites
    it. The rename wins and the run counts one [cell error](#reference-cell-hooks) per row —
    if `cell_errors` climbs on a mapping that only renames, check the source's real header.

### `coalesce` — first non-blank of several columns

```yaml
row:
  - coalesce: { column: display_name, from: [trade_name, name] }
```

| `display_name` | `trade_name` | `name` | → `display_name` |
|---|---|---|---|
| *(absent)* | `Dupont` | `DUPONT SARL` | `Dupont` |
| *(absent)* | *(blank)* | `DUPONT SARL` | `DUPONT SARL` |
| `Kept` | `Dupont` | `DUPONT SARL` | `Kept` — a filled target is never touched |
| *(absent)* | *(absent)* | *(blank)* | *(absent)* — nothing to take |

- Copies the value **as it stands in the row tier** — before any cell hook. In the full example
  `display_name` keeps `DUPONT SARL` while `name` goes on to be title-cased.

### `defaults` — fill blanks in several columns

```yaml
row:
  - defaults: { is_active: "Y", country: FR }
```

| `is_active` | → |
|---|---|
| `N` | `N` — never overwrites |
| *(blank)* | `Y` |
| *(absent)* | `Y` — the column is created |

The cell-tier twin is [`default`](#default-fill-one-blank-column) (one column). Same rule,
different tier — pick by where you need it in the order.

### `validate-row` — rows missing mandatory fields

```yaml
row:
  - validate-row: { required: [code, name] }
```

Before:

```csv
code,name
C1,Acme Corp
C2,
```

After: `C1` only. `C2` (blank `name`) never reaches the target table — it is:

- counted in `records_skipped`;
- captured to the DLQ, reason `ValidationFailed`, message
  `required column 'name' is missing or blank`;
- stored **as it stood in the row tier** — raw, before any cell hook (`mask` included) ran.
  Replay or discard it via [`lm dlq list`](../cli/lm-reference.md) / `lm dlq get`.

!!! warning "`validate-row` after `split-row` is refused"
    `split-row` fans one source row into N fragments; a `validate-row` placed after it would
    drop fragments one at a time instead of once per source row and corrupt the run arithmetic
    (`records_out` could go negative). `lm validate` / `lm apply` refuse the manifest:

    > validate-row after split-row is not supported — validation would drop fanned
    > fragments and corrupt the run arithmetic. Validate before splitting.

### `split-row` — one row per value of `a;b;c`

```yaml
row:
  - split-row: { by: tags, sep: ";" }
```

Before:

```csv
id,tags
1,a;b;c
```

After:

```csv
id,tags
1,a
1,b
1,c
```

- Parts are trimmed; every other column is copied onto each fragment.
- `explode: <column>` does the same over a column that already holds a list.
- Its cell-tier cousin [`split`](#split-parts-into-new-columns) stays **within** the row.

### `aggregator` — exists, but csv-file runs refuse it

Connector runs are chunked transactions with a fresh mapping state per chunk — the aggregator's
cross-chunk buffer would be silently dropped at every chunk boundary. `lm validate` /
`lm apply` refuse the manifest:

> aggregator is not supported on csv-file connector runs — each chunk commits separately,
> buffered rows would be silently lost at chunk boundaries

Pre-aggregate in the source (a grouped export), or drop the hook.

## Cell hooks — one value, in place

Two parameter conventions:

- **Multi-column hooks** take `columns: [a, b]`; omitting `columns` (or `columns: ["*"]`)
  applies the hook to **every column** of the row.
- **Single-column hooks** take `column: <name>` and refuse to guess.

Cell hooks fail **soft**: the row is still written, nothing is dropped or dead-lettered, and
`cell_errors` counts what a hook did not deliver as declared —
[reading `cell_errors`](#reference-cell-hooks).

### `clean` — BOM, stray whitespace, control characters

```yaml
cell:
  - clean: { columns: [name, note], strip: true, remove-control-chars: true, normalize-unicode: NFC }
```

| in | out |
|---|---|
| `﻿  hello\t` (a BOM, two spaces, a tab) | `hello` |
| `Café` as `e` + combining accent | `Café` as one precomposed `é` (`NFC`) |

- A leading BOM is **always** stripped; the three knobs are off by default.
- `remove-control-chars` also removes tabs and newlines inside a value.
- `normalize-unicode`: `NFC`, `NFD`, `NFKC` or `NFKD`. Use `NFC` when two exports spell the
  same accent two ways and your keys stop matching.

### `fix-mojibake` — accents came out as `BÃ©nÃ©dict`

**Mojibake** is text written as UTF-8 and read back as Latin-1. This hook recovers it — and
only when it recognizes the pattern.

```yaml
cell:
  - fix-mojibake: { columns: [name, city] }
```

| in | out |
|---|---|
| `BÃ©nÃ©dict Martin` | `Bénédict Martin` |
| `CÃ´te d'Azur` | `Côte d'Azur` |
| `Bénédict Martin` | unchanged — no mojibake pattern, a clean value is never touched |
| `BÃ©nÃ©dict €` | **unchanged**, +1 `cell_errors` — declined, see below |

- Different from the parser's encoding auto-detection ([CSV & files → Parser](csv.md#parser)),
  which runs first, when the file is read. `fix-mojibake` fixes values that were *already*
  stored garbled in the source system, not a wrong read of a clean file.
- A multi-line cell (a quoted postal address) is one value: garbled text on the second line is
  repaired like the first.

!!! tip "When the repair is declined — and how you find out"
    The repair reads the garbled text back as the bytes it came from, which only works for
    characters Latin-1 could hold. A value that mixes mojibake with anything outside that range
    — Japanese, Cyrillic, Greek, `€`, an emoji — cannot be repaired without losing those
    characters, so it is **not repaired at all**:

    - the value lands exactly as it arrived, garbled — not half-repaired, not quietly changed;
    - the run counts one `cell_errors` for it;
    - the fix is at the source: re-export with the encoding declared correctly. No hook can
      recover a character the export never carried.

### `decode-html-entities` — `&amp;` in text

```yaml
cell:
  - decode-html-entities: { columns: [slogan] }
```

| in | out |
|---|---|
| `Eau &amp; Soleil` | `Eau & Soleil` |
| `Coffee &lt;hot&gt;` | `Coffee <hot>` |
| `caf&eacute;` | `café` (the HTML4 entity set) |

### `replace-smart-quotes` — curly quotes from Word and Excel

```yaml
cell:
  - replace-smart-quotes: { columns: [slogan] }
```

| in | out |
|---|---|
| `“Eau & Soleil” – depuis 1998…` | `"Eau & Soleil" - depuis 1998...` |
| `l’été` | `l'été` |
| `«bonjour»` | `"bonjour"` |

Also: en/em dash → `-`, ellipsis → `...`, non-breaking space → space.

### `strip-invisible` — a code that "looks equal" but never matches

```yaml
cell:
  - strip-invisible: { columns: [code] }
```

| in | out |
|---|---|
| `AB​CD` (a zero-width space inside) | `ABCD` |
| `soft­hyphen` | `softhyphen` |

Removes every Unicode *format* character (`Cf`): zero-width space/joiner/non-joiner, BOM,
word joiner, soft hyphen. The classic symptom: two codes that print identically and still fail
a join.

### `remove-diacritics` — ASCII-only identifiers

```yaml
cell:
  - remove-diacritics: { columns: [city] }
```

| in | out |
|---|---|
| `Montréal` | `Montreal` |
| `ñoño` | `nono` |

### `upper` / `lower` / `titlecase` — case

```yaml
cell:
  - upper: { columns: [legal_form] }
  - lower: { columns: [city] }
  - titlecase: { columns: [name] }
```

| hook | in | out |
|---|---|---|
| `upper` | `sarl` | `SARL` |
| `lower` | `Montreal` | `montreal` |
| `titlecase` | `DUPONT SARL` | `Dupont Sarl` |
| `titlecase` | `MARTIN  DUPONT` (two spaces) | `Martin Dupont` — whitespace **normalised** |

- **Text cells only**: a cell already holding a date, a number or binary data is left as it is,
  so its column keeps its type.
- Locale-independent folding.
- `titlecase` collapses runs of spaces, tabs and newlines to one space and drops trailing space.
  If a column's spacing is meaningful, don't titlecase it.

### `truncate` — hard cut

```yaml
cell:
  - truncate: { column: name, length: 60 }
```

| in | out |
|---|---|
| a 75-character name | its first 60 characters |
| a 12-character name | unchanged |

A negative `length` is refused when the run starts.

### `regex-replace` — normalise with a pattern

```yaml
cell:
  - regex-replace: { column: name, pattern: "\\s+", replacement: " " }
```

| in | out |
|---|---|
| `DUPONT   SARL` | `DUPONT SARL` |
| `DUPONT\tSARL` | `DUPONT SARL` |

- `replaceAll` over the cell; `replacement` defaults to `""` (delete the match).
- In `replacement`, `$` and `\` are group references: a lone `$` or a group the pattern does
  not have fails the run naming the hook — escape them (`\\$`).
- A bad pattern is refused when the run starts.

### `regex-extract` — pull a piece into its own column

```yaml
cell:
  - regex-extract:
      column: name
      pattern: ".*\\b(SARL|SAS|EURL|SCI)\\b.*"
      groups: { 1: legal_form }
```

| `name` | → `legal_form` |
|---|---|
| `DUPONT SARL` | `SARL` |
| `Standard` | *(null)* — no match, **not** counted |
| *(empty)* | *(null)* — no cell, no extraction |

- Tries a full match, then a find; each `groups` entry writes one capture group into a named
  column (`0` is the whole match).
- **Every named column exists on every row** — matched, missed, or empty — so the table's shape
  never depends on which row arrived first. An all-null column is how you see the extraction
  never happened.
- A miss is deliberately **not** counted in `cell_errors`: an optional field misses on every
  row, and a count firing on a normal condition would bury the parse-hook failures that share
  the same number.
- Refused when the run starts: a `groups` key that is not a number, is negative, or exceeds the
  pattern's group count.
- Follow it with [`default`](#default-fill-one-blank-column) to give the misses a value.

### `default` — fill one blank column

```yaml
cell:
  - default: { column: legal_form, value: "NONE" }
```

| in | out |
|---|---|
| `SARL` | `SARL` — never overwrites |
| *(null or blank)* | `NONE` |

Row-tier twin, several columns at once: [`defaults`](#defaults-fill-blanks-in-several-columns).

### `lookup` — translate codes with an inline table

```yaml
cell:
  - lookup: { column: is_active, table: { "Y": true, "N": false }, on-missing: null }
```

| in | `on-missing: keep` *(default)* | `null` | `error` | `INACTIVE` (any literal) |
|---|---|---|---|---|
| `Y` | `true` | `true` | `true` | `true` |
| `?` | `?` | *(null)*, +1 `cell_errors` | **run fails**: `lookup: no match for '?' in column 'is_active'` | `INACTIVE` |

- `table` must be a map — a list or a scalar is refused when the run starts (it would make
  every lookup miss in silence).
- Replacement values land as **text** (`true`, not a boolean).

### `parse-date` — dates are `31/12/2019`

```yaml
cell:
  - parse-date: { column: signup_date, formats: ["dd/MM/yyyy", "yyyy-MM-dd HH:mm:ss"] }
```

| in | out |
|---|---|
| `31/12/2019` | `2019-12-31` — a **date** |
| `2004-10-18 23:42:39` | `2004-10-18 23:42:39+00` — a **timestamp** |
| `12/31/2019` | *(null)*, +1 `cell_errors` — no format matched |
| already a date | unchanged |

- Formats are tried in order. A pattern with no `H`/`h`/`m`/`s` parses to a date; one with a
  time component parses to a timestamp.
- Cheat sheet: `dd` = day, `MM` = month — capital **M**, lowercase `mm` means *minutes*, the
  classic trap — `yyyy` = year. Add `H`/`h`/`m`/`s` only when the source carries a time.

### `parse-number` — numbers are `1 234,56 €` or `(123)`

```yaml
cell:
  - parse-number: { column: balance, decimal-separator: ",", negatives-in-parens: true }
```

| in | out |
|---|---|
| `1 234,56 €` | `1234.56` |
| `(123)` | `-123` |
| `$1,234.50` with `decimal-separator: "."` | `1234.50` |
| `n/a` | *(null)*, +1 `cell_errors` |

- Strips currency symbols (`$€£¥`) and spaces, then resolves which of `.`/`,` is the decimal
  separator (`auto` by default). The result is a decimal.

### `parse-email` — trim, lowercase, validate

```yaml
cell:
  - parse-email: { column: contact_email, lower: true }
```

| in | out |
|---|---|
| `  Contact@Example.COM ` | `contact@example.com` |
| `contact at example` | *(null)*, +1 `cell_errors` |

### `parse-phone` — one format for every phone

```yaml
cell:
  - parse-phone: { column: phone, default-region: FR }     # default-region: US by default
```

| in | out |
|---|---|
| `06 12 34 56 78` | `+33612345678` |
| `+1 415 555 2671` | `+14155552671` — a full international number ignores the region |
| `abc` | *(null)*, +1 `cell_errors` |

`default-region` is the country assumed for a number with no `+` prefix — set it to the
source's country or every national number lands wrong.

### `mask` — a column holds card/PII data

```yaml
cell:
  - mask: { column: card_number, keep-last: 4 }       # keep-first: 0, mask-char: "*"
```

| in | out |
|---|---|
| `4556123498761234` | `************1234` |
| `contact@example.com` with `keep-first: 2, keep-last: 4` | `co*************.com` |
| `1234` | `1234` — **unmasked**: not longer than `keep-first + keep-last` |

Counts raw characters — spaces and separators in `4556 1234 9876 1234` count like digits.

!!! warning "Two things to know before trusting `mask` for PII"
    - **A value not longer than `keep-first + keep-last` passes through unmasked.** If a column
      must **never** land readable regardless of length, add a `regex-replace` whose pattern
      matches the whole cell, as a hard backstop.
    - **A row dead-lettered keeps its raw, unmasked value.** `mask` is a cell hook — it only
      touches rows that reach the cell tier. A row the reader rejects as malformed goes to the
      DLQ **before** the mapping runs; a row `validate-row` drops is snapshotted **before** the
      cell tier. Either way, what [`lm dlq list`](../cli/lm-reference.md) stores is unmasked.

### `split` — parts into new columns

```yaml
cell:
  - split: { column: tags, by: ";", into: [tag1, tag2, tag3] }
```

| `tags` | `tag1` | `tag2` | `tag3` | `cell_errors` |
|---|---|---|---|---|
| `retail;online;b2b` | `retail` | `online` | `b2b` | — |
| `retail;online` | `retail` | `online` | *(null)* | **+1** — fewer parts than declared |
| `a;b;c;d` | `a` | `b` | `c` | **+1** — `d` has nowhere to go |

- Stays **within** the row; the original cell is untouched. The row-tier cousin
  [`split-row`](#split-row-one-row-per-value-of-abc) duplicates rows instead.
- Parts are trimmed. Every name in `into` gets its column on every row (`null` when unfilled).
- A **trailing separator produces a part**: `A,B,,` split on `,` is four values.
- `into-array: true` replaces the cell with an array instead. The column is typed `jsonb` when
  the chunk that creates it carries at least one array — then `WHERE tags @> '["A"]'` works and
  `USING GIN (tags)` indexes it. Null-only in the creating chunk → typed `text`, never widened
  afterwards, so later arrays land as JSON text.

### `to-uuid` — an id column holds junk like `N/A` or `0`

`to-uuid` answers **two separate questions** with **two separate knobs** — mixing them up is
how a made-up identifier ends up looking exactly like a real one.

```yaml
cell:
  - to-uuid:
      column: customer_id
      generate: true          # an EMPTY cell gets a fresh id
      on-invalid: keep        # a cell holding "N/A" is left alone for you to look at
```

| the cell holds | `generate` | `on-invalid` | result | `cell_errors` |
|---|---|---|---|---|
| a valid UUID | anything | anything | unchanged | — |
| nothing (empty) | `false` *(default)* | anything | stays empty | — |
| nothing (empty) | `true` | anything | a fresh id | — |
| `N/A` | anything | `null` *(default)* | emptied | **+1** |
| `N/A` | anything | `generate` | a fresh id **in place of** `N/A` | **+1** |
| `N/A` | anything | `keep` | `N/A`, untouched | — |

A column that does not exist yet counts as empty — `generate: true` on `row_uuid` mints one
per row.

!!! warning "`on-invalid: generate` invents an identifier — use it only for a surrogate key"
    The minted id replaces a value your source really sent. It joins to **nothing** in any
    other system, and downstream nobody can tell it apart from an id that genuinely arrived.
    Fine when you are deliberately building a surrogate key for rows whose real ids are
    unusable; a trap in every other case — which is why it is not the default and why each
    minted id increments `cell_errors`.

    Reaching for it because a *few* rows are dirty? Use `keep` first, run once, and look at what
    those rows actually contain — `on-invalid` applies to the whole column.

### `kotlin` — the escape hatch

```yaml
cell:
  - kotlin:
      script: classpath:hooks/samples/normalize-date.kts
      context: { defaultCountry: FR }    # optional; the script reads it as `context`
```

| `signup_date` in | out |
|---|---|
| `31/12/2019` | `2019-12-31` |
| `2019-12-31` | unchanged — the sample script only rewrites `DD/MM/YYYY` |

Details in [Kotlin escape hatch](#kotlin-escape-hatch) below.

## One manifest, every transformer

Everything above, in one real manifest that ran tonight against an S3 bucket: a headerless,
tab-separated business-partner export (four columns), with every other input **minted by
`enrich-context`** so each hook has something to bite. It declares every transformer the
csv-file connector offers except `aggregator` (refused on chunked runs).

~~~~yaml
--8<-- "docs/connectors/csv/60-s3-every-transformer.yaml"
~~~~

```
$ lm validate -f docs/connectors/csv/60-s3-every-transformer.yaml
✓ docs/connectors/csv/60-s3-every-transformer.yaml — valid
```

### One source row, column by column

One line of the file (tab-separated, no header). The export is a customer's, so the partner's
name is changed here; every other value is traced as it ran:

```
1000117	"DUPONT SARL"	"2004-10-18 00:00:00"	"N"
```

What lands in `connector.t_s3_c_bpartner_clean` — **twice**, because `split-row` fans
`web;store` into two rows — as `SELECT` shows it:

| column | arrives as | lands as | done by |
|---|---|---|---|
| `c_bpartner_id` | `1000117` | `1000117` | passes `filter-where` (`[0-9]+`), ordered by `sort-by` |
| `name` | `DUPONT SARL` | `Dupont Sarl` | `clean` → `fix-mojibake` → `regex-replace` → `titlecase` → `truncate` |
| `display_name` | — | `DUPONT SARL` | `coalesce` copied `name` in the row tier, **before** `titlecase` |
| `legal_form` | — | `SARL` | `regex-extract` on `name`, then `upper`; a name with no legal form gets `NONE` from `default` |
| `created` → `created_at` | `2004-10-18 00:00:00` | `2004-10-18 00:00:00+00` (timestamp) | `map-columns` rename, `parse-date` |
| `isactive` → `is_active` | `N` | `false` | `map-columns` rename, `defaults` (`Y` when blank), `lookup` |
| `channel_raw` | `web;store` | `web` on one row, `store` on the other | `split-row` |
| `contact_email` | `  Contact@Example.COM ` | `co*************.com` | `parse-email` (trim + lower), then `mask` 2/4 |
| `email_domain` | — | `example.com` | `regex-extract` — placed **before** `mask`, or it would see stars |
| `phone_raw` | `06 12 34 56 78` | `+33612345678` | `parse-phone`, `default-region: FR` |
| `balance_raw` | `1 234,56 €` | `1234.5600` | `parse-number` |
| `signup_date` | `31/12/2019` | `2019-12-31` (date) | `kotlin` script, then `parse-date` |
| `tags_raw` | `retail;online;b2b` | unchanged, plus `tag1`/`tag2`/`tag3` = `retail`/`online`/`b2b` | `split` |
| `region_raw` | `CÃ´te d'Azur` | `Côte d'Azur` | `fix-mojibake` |
| `slogan_raw` | `“Eau &amp; Soleil” – depuis 1998…` | `"Eau & Soleil" - depuis 1998...` | `decode-html-entities`, `replace-smart-quotes` |
| `city_raw` | `Montréal` | `montreal` | `remove-diacritics`, `lower` |
| `code_raw` | `AB​CD` (zero-width space inside) | `ABCD` | `strip-invisible` |
| `note_raw` | a BOM, `  hello`, a tab | `hello` | `clean` |
| `row_uuid` | — | `c91c9560-6f8e-4572-89be-3a1397586a21` — a fresh one per row | `to-uuid`, `generate: true` |
| `ingested_from`, `loaded_at` | — | `erp-export-s3`, `2026-09-06T18:10:17.899174761Z` | `enrich-context` |
| `junk` | `dropped by map-columns` | *(no column)* | `map-columns` drop |

Three placements to notice — each is an ordering decision, not a coincidence:

- `validate-row` sits **before** `split-row` (the validator refuses the other order).
- `regex-extract` on `contact_email` sits **before** `mask` — after it, the domain is stars.
- `default: NONE` sits **after** `regex-extract` — a miss leaves `legal_form` null, and the
  default is what turns that null into a value.

### The run, as `lm` shows it

```
$ lm run get 53ec702e
ID        CONNECTOR            STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
53ec702e  s3-c-bpartner-clean  Completed  19019  19014  5        5             2026-09-06 20:09  2026-09-06 20:10
⚠ 5 rows quarantined — see: lm dlq list --run 53ec702e-4c92-4eb6-877d-f66f5ea383f8
```

```
$ lm dlq list --run 53ec702e-4c92-4eb6-877d-f66f5ea383f8
ID        CONNECTOR            RUN       REASON           ATTEMPTS  MESSAGE
c47ecf87  s3-c-bpartner-clean  53ec702e  MalformedSource  1         billing/C_BPartner_Format_malformed.csv:10 — java.io.IOExc…
8870766c  s3-c-bpartner-clean  53ec702e  MalformedSource  1         billing/C_BPartner_Format_malformed.csv:7 — Index for head…
9c3d0c40  s3-c-bpartner-clean  53ec702e  MalformedSource  1         billing/C_BPartner_Format_malformed.csv:5 — Record has 5 v…
dee41893  s3-c-bpartner-clean  53ec702e  MalformedSource  1         billing/C_BPartner_Format_malformed.csv:4 — Index for head…
50548d10  s3-c-bpartner-clean  53ec702e  MalformedSource  1         billing/C_BPartner_Format_malformed.csv:2 — Record has 5 v…
```

How to read it:

- **`IN 19019`** — every record the reader produced from the two files under `billing/`: the
  export, and a small malformed one dropped beside it on purpose.
- **`SKIPPED 5` = `DEAD-LETTERS 5`** — five malformed records (a fifth column, a missing one,
  an unclosed quote). The **reader** rejected them, so they never reached the mapping: reason
  `MalformedSource`, stored raw. Not one hook in the manifest dropped a row.
- **`OUT 19014`** — the source rows that landed. The table holds **38 028** rows: `split-row`
  doubled each one, and the run counts source rows, so `IN = OUT + SKIPPED` still holds.
- **No `cell_errors` alert** — every parse hook read every value. The line
  `⚠ N cell(s) a hook did not deliver as declared` appears only when the count is above zero.

## Reference — cell hooks

`cell_errors` counts the cells a hook did not deliver as declared. A cell reaches that count in
one of **four shapes**:

- **emptied** — a hook could not read the value, so the cell was written `null`: `parse-date`,
  `parse-number`, `parse-email`, `parse-phone`, `lookup` with `on-missing: null`, `to-uuid` in
  its default `on-invalid: null`.
- **left as it arrived** — `fix-mojibake` **declined** (the repair would have lost more than it
  fixed); `split` did split, but the value had more or fewer parts than `into` declares.
- **replaced** — the cell holds a value your source never sent: only `to-uuid` with
  `on-invalid: generate`, and only because you asked for it.
- **overwritten** — `map-columns` renamed a column onto a name the row already used. The only
  shape produced outside the `cell:` tier.

One per cell, readable once the run ends:

- `lm run get <id>` prints a stderr alert — `⚠ N cell(s) a hook did not deliver as declared —
  the rows were still written` — only when the count is above zero. The alert does not say
  which shape: look at which hooks the mapping declares.
- `lm run get <id> -o json` always carries `cellErrors` (0 on a clean run, or on a hub older
  than this counter); `-o yaml` carries it as `cellerrors` (all lowercase, like
  `recordsskipped`).
- The TUI's run describe panel (select a run under `:runs`, press `d`) shows a `cell_errors:`
  line right after `records_skipped:`.
- It counts **cells**, not rows, so it is deliberately absent from the `lm run` table and the
  TUI's `RECORDS in/out/skip` cell — those complete `records_in = records_out +
  records_skipped`, and a cell count has nothing to add to that arithmetic.

| Hook | Params (default) | One line |
|---|---|---|
| `clean` | `columns`; `strip` (false); `remove-control-chars` (false); `normalize-unicode` (off; `NFC`/`NFD`/`NFKC`/`NFKD`) | always strips a leading BOM; then optional trim, control-char removal, Unicode normalization |
| `fix-mojibake` | `columns` | `BÃ©nÃ©dict` → `Bénédict`; declines (+1 `cell_errors`, value untouched) when a non-Latin-1 character would be lost |
| `decode-html-entities` | `columns` | `&amp;` → `&` (HTML4 entity set) |
| `replace-smart-quotes` | `columns` | curly quotes/guillemets → ASCII quotes, en/em dash → `-`, ellipsis → `...`, NBSP → space |
| `strip-invisible` | `columns` | removes every Unicode `Cf` character (ZWSP, ZWJ, BOM, soft hyphen…) |
| `remove-diacritics` | `columns` | `Café` → `Cafe` |
| `upper` / `lower` | `columns` | case, text cells only, locale-independent |
| `titlecase` | `columns` | `DUPONT SARL` → `Dupont Sarl`; text cells only; **normalises whitespace** |
| `truncate` | `column`; `length` (unlimited) | hard cut at `length` characters; negative refused |
| `regex-replace` | `column`; `pattern`; `replacement` (`""`) | `replaceAll` over the cell; `$`/`\` in `replacement` are group references |
| `regex-extract` | `column`; `pattern`; `groups` (map group-index → column) | capture groups into named columns; a miss writes `null`, **not** counted; bad `groups` keys refused at start |
| `default` | `column`; `value` | fills a null or blank cell; never overwrites |
| `lookup` | `column`; `table` (inline map); `on-missing` (`keep`; or `null` / `error` / any literal) | replaces via the map; on a miss: keep, null (+1), fail the run, or the literal |
| `parse-date` | `column`; `formats` (list of Java `DateTimeFormatter` patterns) | first matching format wins; `H/h/m/s` → timestamp, else date; no match → `null` +1 |
| `parse-number` | `column`; `decimal-separator` (`auto`; or `","`/`"."`); `negatives-in-parens` (false) | strips `$€£¥` and spaces, `(123)` → `-123`; decimal out; failure → `null` +1 |
| `parse-email` | `column`; `lower` (false) | trim, optional lowercase, validate; invalid → `null` +1 |
| `parse-phone` | `column`; `default-region` (`US`) | E.164 (`+33612345678`); failure → `null` +1 |
| `mask` | `column`; `keep-first` (0); `keep-last` (0); `mask-char` (`*`) | masks the middle; a value not longer than `keep-first + keep-last` is left untouched |
| `split` | `column`; `by` (`,`); `into` (list) or `into-array` (false) | parts into named columns (or an array); part-count mismatch → +1 |
| `to-uuid` | `column`; `generate` (false); `on-invalid` (`null`) | `generate` fills an **empty** cell; `on-invalid` decides a non-UUID — [every case](#to-uuid-an-id-column-holds-junk-like-na-or-0) |
| `kotlin` | `script` (required, `classpath:` only); `context` (map) | the escape hatch — [below](#kotlin-escape-hatch) |

## Reference — row hooks

| Hook | Params (default) | One line |
|---|---|---|
| `map-columns` | `rename` (map old → new); `drop` (list) | renames, then drops |
| `coalesce` | `column`; `from` (list of source columns) | when the target is null/blank, the first non-blank source value |
| `defaults` | a map `column: value` | fills every listed column that is null or blank |
| `validate-row` | `required` (list of columns) | drops the row: `records_skipped` + DLQ (`ValidationFailed`, raw payload); refused after `split-row` |
| `split-row` | `by` (column) + `sep` (`,`) — or `explode` (column holding a list) | one row per part, the other columns copied |
| `aggregator` | `emit-on-change`; `keep-first`; `merge` | groups consecutive rows — **refused on csv-file runs** |

## Reference — dataset hooks

| Hook | Params (default) | One line |
|---|---|---|
| `require-headers` | `columns` (all must exist); `any-of` (at least one) | fails the run: `required header missing: <col>` / `none of required headers found: [...]` |
| `skip-empty-rows` | — | drops all-blank rows; `records_skipped`, `PolicyExcluded("skip-empty-rows")`, no DLQ |
| `filter-where` | `column`; `equals`; `in` (list); `regex` (full match) | keeps rows matching **every** criterion; null compares as `""`; drops → `records_skipped`, `PolicyExcluded("filter-where")`, no DLQ |
| `sort-by` | `columns` (list); `max-rows` (100000) | lexicographic sort within the chunk, nulls first; over the cap the run refuses |
| `enrich-context` | a map `column: value`; `${now}` = the ingestion instant | adds constant columns; existing values are not overwritten |

## Kotlin escape hatch

When no built-in fits, a `kotlin` cell hook runs a `.kts` script over the whole row:

```yaml
mapping:
  cell:
    - kotlin:
        script: classpath:hooks/samples/normalize-date.kts
        context: { defaultCountry: FR }    # optional; the script reads it as `context`
```

The script that ships with the platform, the one the full example runs:

~~~~kotlin
--8<-- "docs/connectors/csv/hooks/normalize-date.kts"
~~~~

- The script gets `bindings["cells"]` (the mutable row map) and `bindings["context"]` (a copy
  of the hook's `context:` values, rebuilt for every row). Changes to `cells` are visible to the
  hooks that follow and to the writer; changes to `context` reach nothing — treat it as
  read-only, the platform does not enforce it.
- **Full JVM trust** — no sandbox, no timeout, no memory cap. A script failure fails the run.
- Scripts live on the **platform classpath**: `classpath:` refs only; a `file:` path is
  rejected (`only classpath: scripts supported in v1`). Same trust boundary as the
  developer-facing lifecycle/action hooks documented beside the code
  (`lumnik-hub/src/main/java/io/lumnik/hub/hook/README.md`).
- Runnable example: `docs/connectors/csv/50-kotlin-row-hook.yaml`.

## REST: the `transform:` enum

REST/GraphQL/SOAP endpoints do not use `spec.mapping`. Their transformation point is the
`promote:` block of a hybrid target — each promoted column takes an optional `transform:`

| Value | Effect |
|---|---|
| `from_epoch` | Unix epoch **seconds** → timestamp (UTC); for milliseconds, divide upstream |
| `parse_iso8601` | ISO-8601 string → timestamp |
| `to_lower` / `to_upper` | case conversion |
| `trim` | whitespace trim |
| `to_json_string` | serializes the extracted value to a JSON string |

See the [REST connector](rest.md) for the `promote:` block itself.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| rows missing, but the DLQ is empty | `filter-where` or `skip-empty-rows` excluded them by declared policy — counted in `records_skipped`, not DLQ'd; `validate-row` drops, by contrast, DO reach the DLQ (`ValidationFailed`) | check `SKIPPED` in `lm run get` (or the TUI's `:runs` RECORDS `in/out/skip`); confirm which hook in `spec.mapping` is doing the dropping |
| `DEAD-LETTERS` > 0 with reason `MalformedSource` | the **reader** rejected the row (wrong column count, unclosed quote) before any hook ran | `lm dlq get <id>` shows the raw line; fix the export or the `parser:` block — no mapping hook can see that row |
| run fails: `unknown <tier> hook: <name>` | typo in a hook name, or the hook listed under the wrong tier | check the reference tables above for the exact name and its tier |
| mapping block seems to have no effect | applied via the one-shot `lm csv ingest`, which never runs `spec.mapping` | apply a [`csv-file` connector manifest](csv.md) and run it instead |
| `only classpath: scripts supported in v1` | a `kotlin` hook's `script:` points at a `file:` path | move the script under the platform classpath and reference it as `classpath:...` |
| a parsed column is blank for some rows, but the row itself landed | a cell hook couldn't read that value and nulled it by design — written, not dropped | check `cell_errors`: `lm run get <id>` (stderr alert when > 0, or `-o json`/`-o yaml`), or the TUI describe panel (`:runs`, select the run, `d`) |
| a `regex-extract` column is null on every row | the pattern never matched — a miss is not counted, the empty column is the signal | test the pattern on a real value; remember the match is tried in full first, then as a find |

## See also

- [CSV & files](csv.md) — the envelope `spec.mapping` lives in.
- `docs/connectors/csv/` in the repository — the runnable manifests, including the full example.
