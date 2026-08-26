# Transformers

> Repair legacy data as it flows in — mojibake, `DD/MM/YYYY` dates, numbers in parentheses —
> declared in the manifest's `spec.mapping` block, no code.

## Where this applies

**Read this list before promising a customer their data will be cleaned** — transformers
run on one connector family only, and dirty *status values* are a different tool's job:
declare them as [workflow aliases](../apps/workflows.md#alias-semantics), which map dirty
legacy statuses without touching the data.

- **`csv-file` connector runs only.** The csv-file connector type parses `spec.mapping` and
  applies its hooks before writing. The **jdbc** and **kafka** connectors write a 1:1 dump —
  their specs carry no `spec.mapping` hooks (kafka: each message's top-level JSON fields become
  table columns, as-is). jdbc has its own, narrower knob for the one case that actually needs
  it — a source column colliding with a hub system column — via `source.tables[].rename`, not
  `spec.mapping`; see [JDBC: Target](jdbc.md#target).
- **REST/GraphQL/SOAP** endpoints don't use `spec.mapping` at all — their transformation point
  is the `promote:` block's `transform:` enum, a short fixed list (epoch/ISO dates, case, trim,
  JSON-stringify). See [REST: the `transform:` enum](#rest-the-transform-enum) below.
- **Connector runs only — not one-shot `lm csv ingest`.** The one-shot path
  (`POST /api/platform/sources/csv/ingest`) writes directly without the mapping engine; its
  `spec.mapping` (if any) is ignored. To transform, apply a `csv-file` connector manifest and
  run it.
- **Rehearse before the live feed.** [`lm validate`](index.md#the-lifecycle-every-connector-same-three-commands)
  checks the manifest's shape, not your data — it cannot tell you whether a hook will actually
  repair a given file. Apply and run against a sample file first, read the result, then point
  the same manifest at the real feed.

Hooks are declared in three tiers, one entry per hook:

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

Execution order is fixed **across tiers**: dataset → row → cell, always. **Within** a tier,
hooks run in the order they're listed, top to bottom. Each entry is a single-key object
(`- hook-name: { params }`); an unknown hook name fails the run (`unknown dataset hook: <name>`).

## By symptom

Each entry below is self-contained: the raw data that arrives, the mapping block that
repairs it, and the result.

### Accents came out as `BÃ©nÃ©dict`

This is **mojibake** — text that looks garbled because it was written as UTF-8 but read back
as Latin-1 (or vice-versa). `fix-mojibake` recovers it, but only when it recognizes the
mojibake pattern — a clean value is never touched. It is a separate, later repair from the
parser's own encoding auto-detection ([CSV & files → Parser](csv.md#parser)), which runs
first, when the file is read: `fix-mojibake` only fixes values that were *already* stored
garbled in the source system, not a wrong read of a clean file.

Before:

```csv
name,city
BÃ©nÃ©dict Martin,MontrÃ©al
```

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }
```

After:

```csv
name,city
Bénédict Martin,Montréal
```

### Dates are `31/12/2019`

`parse-date` tries each declared format in order. A pattern with no `H`/`h`/`m`/`s` in it
parses to a **date**; one that includes a time component parses to a **timestamp**.
`dd/MM/yyyy` has none, so the result below is a date.

Pattern-letter cheat sheet: `dd` = day, `MM` = month (capital **M** — lowercase `mm` means
*minutes*, the classic trap), `yyyy` = year. Add `H`/`h`/`m`/`s` only when the source value
actually carries a time.

Before:

```csv
name,signup_date
Bénédict Martin,31/12/2019
```

```yaml
mapping:
  cell:
    - parse-date:
        column: signup_date
        formats: ["dd/MM/yyyy"]
```

After:

```csv
name,signup_date
Bénédict Martin,2019-12-31
```

### Numbers are `1 234,56` or `(123)`

`parse-number` strips currency symbols and spaces, then resolves which of `.`/`,` is the
decimal separator; with `negatives-in-parens: true`, a parenthesized value becomes negative.

Before:

```csv
code,balance
C1,"1 234,56"
C2,(123)
```

```yaml
mapping:
  cell:
    - parse-number:
        column: balance
        decimal-separator: ","
        negatives-in-parens: true
```

After:

```csv
code,balance
C1,1234.56
C2,-123
```

### A column holds card/PII data

`mask` blanks out the middle of a value, keeping only the edges you declare. It counts raw
characters — spaces and separators (e.g. in `4556 1234 9876 1234`) count toward the length
exactly like digits do.

Before:

```csv
name,card_number
Bénédict Martin,4556123498761234
Test Co,1234
```

```yaml
mapping:
  cell:
    - mask: { column: card_number, keep-last: 4 }
```

After:

```csv
name,card_number
Bénédict Martin,************1234
Test Co,1234
```

!!! warning "Two things to know before trusting `mask` for PII"
    - **A value not longer than `keep-first + keep-last` passes through unmasked** — see
      `Test Co,1234` above: nothing is starred out, because a 4-character value is already
      "all edge" at `keep-last: 4`. If a column must **never** land readable regardless of
      value length, don't rely on `mask` alone — use `regex-replace` with a pattern that
      matches the whole cell instead (or in addition, as a hard backstop).
    - **A row dead-lettered before or during mapping keeps its raw, unmasked value.** `mask`
      is a *cell* hook — it only ever touches rows that reach the cell tier. A row the reader
      rejects as malformed is captured to the run's dead-letter queue (**DLQ** — the holding
      area for records a run couldn't ingest cleanly) **before** the mapping pipeline runs at
      all; a row `validate-row` drops in the row tier is captured the same way, with the
      snapshot taken **before** the cell tier (and any `mask` hook in it) runs. Either way,
      what an [`lm dlq list`](../cli/lm-reference.md) entry stores is unmasked, no matter what
      `mask` hook is declared for that column.

### One cell holds many values (`a;b;c`)

Two different hooks answer this, and they don't do the same thing: `split-row` **duplicates
the row**, one per part; `split` stays **within the row**, landing the parts in new columns.

**`split-row`** — one row per part, the other columns copied:

Before:

```csv
id,tags
1,a;b;c
```

```yaml
mapping:
  row:
    - split-row: { by: tags, sep: ";" }
```

After:

```csv
id,tags
1,a
1,b
1,c
```

**`split`** — parts land in new named columns, the original cell is untouched:

Before:

```csv
id,tags
1,a;b;c
```

```yaml
mapping:
  cell:
    - split:
        column: tags
        by: ";"
        into: [tag1, tag2, tag3]
```

After:

```csv
id,tags,tag1,tag2,tag3
1,a;b;c,a,b,c
```

### Legacy column names (`CUST_NM`)

`map-columns` renames first, then drops — both are optional, and dropping happens after any
rename so a `drop` entry can name either the old or the new column name.

Before:

```csv
CUST_NM,CUST_CITY,internal_flag
Bénédict Martin,Montréal,X
```

```yaml
mapping:
  row:
    - map-columns:
        rename: { CUST_NM: name, CUST_CITY: city }
        drop: [internal_flag]
```

After:

```csv
name,city
Bénédict Martin,Montréal
```

### Rows missing mandatory fields

`validate-row` drops any row that is null or blank in a required column — it never reaches
the target table. The drop is counted in the run's `records_skipped` stat and captured to
the DLQ with reason `ValidationFailed` (message: `required column '<col>' is missing or
blank`), payload = the row as it stood in the row tier — raw, before any cell hook (`mask`
included) has touched it. Replay or discard it via [`lm dlq list`](../cli/lm-reference.md) /
`lm dlq get`. `validate-row` cannot be placed after `split-row` in the same manifest — see
the warning under "Reference — row hooks" below.

Before:

```csv
code,name
C1,Acme Corp
C2,
```

```yaml
mapping:
  row:
    - validate-row: { required: [code, name] }
```

After:

```csv
code,name
C1,Acme Corp
```

(`C2` is dropped — blank `name` — counted in `records_skipped` and captured to the DLQ,
reason `ValidationFailed`.)

### Stamp provenance on every row

`enrich-context` adds constant columns to every row of the chunk; `${now}` resolves once, to
the ingestion instant. An existing value in that column is never overwritten.

Before:

```csv
code,name
C1,Acme Corp
```

```yaml
mapping:
  dataset:
    - enrich-context: { source_system: siebel, ingested_at: "${now}" }
```

After:

```csv
code,name,source_system,ingested_at
C1,Acme Corp,siebel,2026-08-20T14:03:11.482Z
```

## Reference — cell hooks

Operate on one value at a time. Two parameter conventions:

- **Multi-column hooks** take `columns: [a, b]`; omitting `columns` (or `columns: ["*"]`)
  applies the hook to **every column** of the row.
- **Single-column hooks** take `column: <name>` and refuse to guess.

Parse hooks fail **soft** by design: a value that cannot be parsed becomes `null` and the row
is still written — nothing is dropped, nothing is dead-lettered, the run continues. Every
nulled cell increments the run's `cell_errors` counter, readable once the run ends:

- `lm run get <id>` prints a stderr alert — `⚠ N cell(s) emptied by a parse hook that could
  not read the value` — but only when the count is greater than zero; a clean run prints
  nothing.
- `lm run get <id> -o json` always carries the field, as `cellErrors` (0 on a clean run, or
  on a hub older than this counter).
- `lm run get <id> -o yaml` carries the same value under the key `cellerrors` (all lowercase,
  no underscore) — the same shape `recordsskipped` already takes; not a quirk of this field.
- the TUI's run describe panel (select a run under `:runs`, press `d`) shows a
  `cell_errors:` line right after `records_skipped:`.

`cell_errors` counts **cells**, not rows, so it deliberately doesn't appear in the `lm run`
table or the TUI's `RECORDS in/out/skip` cell — those three numbers already complete
`records_in = records_out + records_skipped`, and a cell count has nothing to add to that
arithmetic. A non-zero `cell_errors` on an otherwise-clean run is how you tell "a parse hook
gave up on some values" from "the source column really was empty" — open the run to check it.

| Hook | Params (default) | Behavior |
|---|---|---|
| `clean` | `columns`; `strip` (false); `remove-control-chars` (false); `normalize-unicode` (off; `NFC`/`NFD`/`NFKC`/`NFKD`) | always strips a leading BOM; then optional trim, control-char removal, Unicode normalization |
| `fix-mojibake` | `columns` | recovers UTF-8 read as Latin-1 (`BÃ©nÃ©dict` → `Bénédict`); applies only when the mojibake pattern is detected, keeps the original if recovery would produce `�` |
| `decode-html-entities` | `columns` | `Coffee &amp; cream` → `Coffee & cream` (HTML4 entity set) |
| `replace-smart-quotes` | `columns` | curly quotes/guillemets → ASCII quotes, en/em dash → `-`, ellipsis → `...`, NBSP → space |
| `strip-invisible` | `columns` | removes zero-width and format characters (ZWSP, ZWJ, BOM, soft hyphen — all Unicode `Cf`) |
| `remove-diacritics` | `columns` | `Café` → `Cafe` (NFD + combining-mark strip) |
| `upper` / `lower` | `columns` | case conversion |
| `titlecase` | `columns` | first letter of each whitespace-separated word up, rest down |
| `parse-date` | `column`; `formats` (list of Java `DateTimeFormatter` patterns) | tries each format in order; a pattern containing `H/h/m/s` parses to a timestamp, otherwise to a date; no match → `null` + cell error; already-temporal values pass through |
| `parse-number` | `column`; `decimal-separator` (`auto`; or `","`/`"."`); `negatives-in-parens` (false) | strips currency symbols (`$€£¥`) and spaces, resolves thousand vs decimal separators, `(123)` → `-123` when enabled; result is a decimal; failure → `null` + cell error |
| `parse-email` | `column`; `lower` (false) | trims, optionally lowercases, validates; invalid → `null` + cell error |
| `parse-phone` | `column`; `default-region` (`US`) | libphonenumber parse → E.164 (`+33612345678`); failure → `null` + cell error |
| `default` | `column`; `value` | sets `value` when the cell is null or blank; never overwrites |
| `lookup` | `column`; `table` (inline map); `on-missing` (`keep`; or `null` / `error` / any literal) | replaces the value via the map; on a miss: keep the original, null it (+ cell error), fail the run, or substitute the literal |
| `mask` | `column`; `keep-first` (0); `keep-last` (0); `mask-char` (`*`) | masks the middle: `4556123498761234` with `keep-last: 4` → `************1234`; values not longer than `keep-first + keep-last` are left untouched |
| `truncate` | `column`; `length` (unlimited) | hard cut at `length` characters |
| `split` | `column`; `by` (`,`); `into` (list of new column names) or `into-array` (false) | splits **within** the row: parts (trimmed) land in named columns, or replace the cell as an array — distinct from `split-row`, which duplicates rows |
| `regex-extract` | `column`; `pattern`; `groups` (map group-index → target column) | tries a full match, then a find; writes captured groups into the named columns |
| `regex-replace` | `column`; `pattern`; `replacement` (`""`) | `replaceAll` over the cell |
| `to-uuid` | `column`; `generate` (false) | valid UUID passes through; invalid → freshly generated UUID or `null`; a null cell is filled only when `generate: true` |
| `kotlin` | `script` (required, `classpath:` only); `context` (map) | the escape hatch — see [below](#kotlin-escape-hatch) |

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }
    - parse-date:
        column: signup_date
        formats: ["dd/MM/yyyy", "yyyy-MM-dd"]
    - parse-number:
        column: balance
        decimal-separator: ","
        negatives-in-parens: true
    - mask: { column: card_number, keep-last: 4 }
```

## Reference — row hooks

One record in, **0..N records out** — a row hook can drop, duplicate, or reshape rows.

| Hook | Params (default) | Behavior |
|---|---|---|
| `map-columns` | `rename` (map old → new); `drop` (list) | renames then drops columns |
| `coalesce` | `column`; `from` (list of source columns) | when the target is null/blank, takes the first non-blank source value |
| `defaults` | a map `column: value` | fills every listed column that is null or blank |
| `validate-row` | `required` (list of columns) | a row missing (or blank in) any required column is **dropped**: counted in `records_skipped` and captured to the DLQ, reason `ValidationFailed`, payload = the row before any cell hook ran — refused after `split-row` in the same manifest, see below |
| `split-row` | `by` (column) + `sep` (`,`) — or `explode` (column holding a list) | one row per part/list element (parts trimmed), the other columns copied — the inverse of an aggregation |
| `aggregator` | `emit-on-change` (key column); `keep-first` (columns copied from the group's first row); `merge` (map out-column → `{collect, into: array\|set\|csv, dedup: false, sep: ","}`) | groups consecutive rows by key — **refused on csv-file connector runs, see below** |

```yaml
mapping:
  row:
    - map-columns:
        rename: { CUST_NM: name, CUST_CITY: city }
        drop: [internal_flag]
    - validate-row: { required: [code, name] }
    - split-row: { by: tags, sep: ";" }
```

!!! warning "`aggregator` exists in the engine, but csv-file runs refuse it"
    Connector runs are chunked transactions with a fresh mapping state per chunk — the
    aggregator's cross-chunk buffer would be silently dropped at every chunk boundary.
    `lm validate` / `lm apply` refuse the manifest instead:

    > aggregator is not supported on csv-file connector runs — each chunk commits
    > separately, buffered rows would be silently lost at chunk boundaries

    with the hint: *Pre-aggregate in the source (e.g. a grouped export), or drop the
    aggregator hook.*

!!! warning "`validate-row` after `split-row` is refused"
    `split-row` fans one source row into N fragments; a `validate-row` placed after it
    would drop failing fragments one at a time instead of once per source row, corrupting
    the run's source-row arithmetic (`records_out` could go negative). `lm validate` /
    `lm apply` refuse the manifest instead:

    > validate-row after split-row is not supported — validation would drop fanned
    > fragments and corrupt the run arithmetic. Validate before splitting.

    with the hint: *Validate before splitting, or drop the validate-row hook.*

## Reference — dataset hooks

Run **once per chunk**, before the row and cell tiers. The contract: a dataset hook *may
rewrite the whole chunk, never more* — it never sees the full file. Local csv-file runs
commit in fixed 500-row chunks (remote sftp/s3 transports read one whole file per
transaction, so there the chunk *is* the file). Consequence: `sort-by` orders **within a
chunk**, not globally.

| Hook | Params (default) | Behavior |
|---|---|---|
| `require-headers` | `columns` (all must exist); `any-of` (at least one must exist) | fails the run when the file's shape is wrong: `required header missing: <col>` / `none of required headers found: [...]` |
| `skip-empty-rows` | — | drops rows whose every cell is null or blank; counted in `records_skipped`, reason `PolicyExcluded("skip-empty-rows")` — not sent to the DLQ (a declared filter, not a quality failure) |
| `filter-where` | `column`; `equals`; `in` (list); `regex` (full match) | keeps rows satisfying **every** configured criterion (AND); a null cell compares as `""`; a dropped row is counted in `records_skipped`, reason `PolicyExcluded("filter-where")` — not sent to the DLQ |
| `sort-by` | `columns` (list); `max-rows` (100000) | lexicographic in-memory sort within the chunk (nulls sort first, as empty strings); over the cap it refuses: `sort-by max-rows exceeded: <n> > <max> (input must be pre-sorted or increase max-rows)` |
| `enrich-context` | a map `column: value`; the value `${now}` resolves to the ingestion instant | adds constant columns to every row (existing values are not overwritten) |

```yaml
mapping:
  dataset:
    - require-headers: { columns: [code, name] }
    - skip-empty-rows: {}
    - filter-where: { column: status, in: [ACTIVE, PENDING] }
    - enrich-context: { source_system: siebel, ingested_at: "${now}" }
```

## Kotlin escape hatch

When no built-in fits, a `kotlin` cell hook runs a `.kts` script over the whole row:

```yaml
mapping:
  cell:
    - kotlin:
        script: classpath:hooks/samples/normalize-date.kts
        context: { defaultCountry: FR }    # optional, read-only in the script
```

The script gets `bindings["cells"]` (the mutable row map) and `bindings["context"]`.
Scripts run with **full JVM trust** — no sandbox, no timeout, no memory cap — and must
live on the **platform classpath**: `classpath:` refs only, a `file:` path is rejected
(`only classpath: scripts supported in v1`). This is the same trust boundary every Kotlin
script runs under in the platform, including the developer-facing lifecycle/action hook
family documented beside the code (`lumnik-hub/src/main/java/io/lumnik/hub/hook/README.md`).
A script failure fails the run.

Runnable example: `docs/connectors/csv/50-kotlin-row-hook.yaml` (its script source is
mirrored at `docs/connectors/csv/hooks/normalize-date.kts`).

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

## How to…

### Clean a whole legacy export in one manifest

One `mapping:` block, chaining `require-headers` and `skip-empty-rows` (dataset tier),
`map-columns` and `validate-row` (row tier), then `fix-mojibake`, `parse-date` and
`parse-number` (cell tier):

```yaml
mapping:
  dataset:
    - require-headers: { columns: [CUST_NM, CODE] }
    - skip-empty-rows: {}
  row:
    - map-columns:
        rename:
          CUST_NM: name
          CUST_CITY: city
          SIGNUP_DT: signup_date
          BALANCE: balance
          CODE: code
    - validate-row: { required: [code, name] }
  cell:
    - fix-mojibake: { columns: [name, city] }
    - parse-date:
        column: signup_date
        formats: ["dd/MM/yyyy"]
    - parse-number:
        column: balance
        decimal-separator: ","
        negatives-in-parens: true
```

Before:

```csv
CUST_NM,CUST_CITY,SIGNUP_DT,BALANCE,CODE
BÃ©nÃ©dict Martin,MontrÃ©al,31/12/2019,"1 234,56",C1
,Lyon,01/01/2020,500,C2
```

After (target table):

```csv
name,city,signup_date,balance,code
Bénédict Martin,Montréal,2019-12-31,1234.56,C1
```

**Result:** one row lands in the target table, fully repaired — accents fixed, date and
number parsed, columns renamed. The second row (`C2`) never gets there: blank `name` fails
`validate-row` in the row tier, before the cell-tier repairs even run. The drop is counted
in `records_skipped` and captured to [`lm dlq list`](../cli/lm-reference.md) with reason
`ValidationFailed`, payload = the row as read — raw, unmasked (see [Rows missing mandatory
fields](#rows-missing-mandatory-fields) above).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| rows missing, but the DLQ is empty | `filter-where` or `skip-empty-rows` excluded them by declared policy — counted in `records_skipped`, but not DLQ'd (a declared filter isn't a quality failure); `validate-row` drops, by contrast, DO reach the DLQ (reason `ValidationFailed`, see [Rows missing mandatory fields](#rows-missing-mandatory-fields)) | check `SKIPPED` in `lm run get` (or the TUI's `:runs` RECORDS `in/out/skip`) — that's the count; confirm which hook in `spec.mapping` is doing the dropping |
| run fails: `unknown <tier> hook: <name>` | typo in a hook name, or the hook listed under the wrong tier (`dataset`/`row`/`cell`) | check the reference tables above for the exact name and its tier |
| mapping block seems to have no effect | applied via the one-shot `lm csv ingest`, which never runs `spec.mapping` | apply a [`csv-file` connector manifest](csv.md) and run it instead |
| `only classpath: scripts supported in v1` | a `kotlin` hook's `script:` points at a `file:` path | move the script under the platform classpath and reference it as `classpath:...` |
| a parsed column (date/number/email/phone, or a `lookup`) is blank for some rows, but the row itself landed | a cell hook in the [reference table above](#reference-cell-hooks) couldn't read that value and nulled it by design — the row was written, not dropped or dead-lettered | check `cell_errors` on the run: `lm run get <id>` (stderr alert when > 0, or `-o json`/`-o yaml`), or the TUI describe panel (`:runs`, select the run, `d`) — a non-zero count means parse failures, not a genuinely empty source |

## See also

- [CSV & files](csv.md) — the envelope `spec.mapping` lives in.
