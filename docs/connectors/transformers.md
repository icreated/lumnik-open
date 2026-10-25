# Transformers — repair the data at the door

> Legacy exports arrive dirty: broken accents, `31/12/2019` dates, `1 234,56 €` amounts, a value
> written once for a whole block. Transformers repair them as the file is read. You declare them
> in the connector manifest; no code.

Every parameter, on one page: [Transformers — reference](transformers-reference.md).

## Where it applies

- **`csv-file` connectors only.** Transformers go in the manifest's `spec.mapping` block.
- **Not `lm csv ingest`.** The one-shot load never transforms. Apply a `csv-file` connector.
- **A REST, GraphQL or SOAP endpoint** has its own short list: [`transform:`](rest.md#transform-a-promoted-value).
- **A JDBC or Kafka connector** writes what it reads. A JDBC table can only rename a column:
  [JDBC: the hub table](jdbc.md#the-hub-table).
- **Dirty status values** (`fini`, `TERMINE `) are not repaired here. Declare them as
  [workflow aliases](../apps/workflows.md#alias-semantics).

Try a manifest on a sample file first, read the result, then point it at the real feed.
`lm validate` checks the manifest, not your data.

## How a mapping reads

```yaml
spec:
  mapping:
    dataset:                    # 1 — the whole batch of rows
      - skip-empty-rows: {}
    row:                        # 2 — each row; may drop it or split it
      - map-columns: { rename: { CUST_NM: name } }
    cell:                       # 3 — each value, in place
      - fix-mojibake: { columns: [name] }
```

- The three tiers always run in this order: `dataset`, then `row`, then `cell`.
- Inside a tier, hooks run top to bottom. The order is yours, and it matters.
- One entry per hook: `- hook-name: { parameters }`.
- `lm validate` refuses a misspelt hook or parameter, and names the entry:
  `spec.mapping.cell[0].upper.colums` → `upper hook: unknown parameter 'colums' — accepted: columns`.
- It refuses a misspelt tier too: `spec.mapping.cel` → `mapping: unknown key 'cel' — accepted: dataset, row, cell, on-error`.

Nothing is dropped silently. A row leaves by one of three doors:

| The row | Hook | Counted in |
|---|---|---|
| excluded by a declared filter | `filter-where`, `skip-empty-rows` | `SKIPPED` |
| missing a mandatory field | `validate-row` | `SKIPPED` **and** the dead letters |
| written, one value emptied | a cell hook that cannot read the value | `cell_errors` |

## The file has the wrong shape

### `require-headers` — refuse a file with the wrong shape

```yaml
dataset:
  - require-headers: { columns: [c_bpartner_id, name], any-of: [created, isactive] }
```

| The file's header | Result |
|---|---|
| `c_bpartner_id,name,created,isactive` | passes |
| `c_bpartner_id,name,created` | passes: every `columns`, one of `any-of` |
| `c_bpartner_id,name` | **fails the run**: `none of required headers found: [created, isactive]` |
| `name,created` | **fails the run**: `required header missing: c_bpartner_id` |

A headerless file is checked against the `parser.columns` you declared.

### `skip-empty-rows` — blank lines in the export

```yaml
dataset:
  - skip-empty-rows: {}
```

| In | Out |
|---|---|
| `C1,Acme Corp` | kept |
| `,` | dropped, counted in `SKIPPED` |
| `C2,Globex` | kept |

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
| `10a` | dropped | dropped | dropped: the pattern must match the whole value |
| *(empty)* | dropped | dropped | dropped |

- Several criteria combine: a row must match them all.
- A dropped row counts in `SKIPPED`. It is not a dead letter.

## Legacy column names

### `map-columns` — legacy column names (`CUST_NM`)

```yaml
row:
  - map-columns:
      rename: { CUST_NM: name, CUST_CITY: city }
      drop: [internal_flag]
```

| In | Out |
|---|---|
| `CUST_NM,CUST_CITY,internal_flag` | `name,city` |
| `Bénédict Martin,Montréal,X` | `Bénédict Martin,Montréal` |

- It renames first, then drops: `drop` uses the **new** names.
- Rename a column away from a name the hub keeps for itself (`id`, `tenant_id`). `lm validate`
  refuses the opposite:

    ```
      ERROR    spec.mapping.row[0].map-columns.rename.CUST_ID
              targets 'id', a hub system column (_ingested_at, _mapping_version, _row_hash, _run_id, _source, id, tenant_id) — renaming a source column AWAY from one of these is what the rename is for, renaming into one only recreates the collision
    ```

- Renaming onto a column the file already has overwrites it, and counts one `cell_errors` per row.

### `coalesce` — first non-blank of several columns

```yaml
row:
  - coalesce: { column: display_name, from: [trade_name, name] }
```

| `display_name` | `trade_name` | `name` | → `display_name` |
|---|---|---|---|
| *(absent)* | `Dupont` | `DUPONT SARL` | `Dupont` |
| *(absent)* | *(blank)* | `DUPONT SARL` | `DUPONT SARL` |
| `Kept` | `Dupont` | `DUPONT SARL` | `Kept`: a filled target is never touched |

It copies the value as it arrived, before the cell hooks change it.

### `defaults` — fill blanks in several columns

```yaml
row:
  - defaults: { is_active: "Y", country: FR }
```

| `is_active` | → |
|---|---|
| `N` | `N`: never overwritten |
| *(blank)* | `Y` |
| *(absent)* | `Y`: the column is created |

One column, later in the order: [`default`](#default-fill-one-blank-column).

## Rows that must not land incomplete

### `validate-row` — rows missing mandatory fields

```yaml
row:
  - validate-row: { required: [code, name] }
```

A file with `C1,Acme Corp` and `C2,` (no name):

```
$ lm run get fc9b4ba6
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
fc9b4ba6  csv-customers  default   Completed  2   1    1        1             2026-10-19 07:19  2026-10-19 07:19
⚠ 1 rows quarantined — see: lm dlq list --run fc9b4ba6-5b32-49a3-af64-f96b8c344a2c
```

- `C1` lands. `C2` goes to the dead letters, with the reason `required column 'name' is missing or blank`.
- Place `validate-row` **before** `split-row`. `lm validate` refuses the other order.
- Fix the source, run again, then close the dead letter:
  [Diagnosing a failed run](../recipes/diagnose-a-run.md#3-fix-re-run-close-the-loop).

### An empty amount is a wrong row: `on-error: dlq`

By default, a value a hook cannot read is emptied, and the row lands. For an invoice, a row
without its amount is wrong. Say so for that column. The file:

```csv
--8<-- "docs/connectors/csv/data/invoices.csv"
```

The manifest's mapping:

```yaml
  mapping:
    on-error:
      fields: { amount_eur: dlq }   # this column: set the row aside
      default: soft                 # every other column: write it, count the cell
    cell:
      - parse-number: { column: amount_eur, decimal-separator: "." }
```

The run sets `F-1002` aside, as it was read:

```
$ lm run get 460efbba
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
460efbba  invoices   default   Completed  3   2    1        1             2026-10-24 22:15  2026-10-24 22:15
⚠ 1 rows quarantined — see: lm dlq list --run 460efbba-458f-461d-b6d3-45e439415abe
$ lm dlq get 260a6940-0d56-4478-a743-a5b8713660d8
ID:        260a6940-0d56-4478-a743-a5b8713660d8
Connector: invoices (a7487f8c-c3db-4afa-adc5-88e2ab8cb487)
Run:       460efbba-458f-461d-b6d3-45e439415abe
Reason:    MappingFailed
Attempts:  1
Message:   amount_eur: parse-number could not read the value
Detail:    {"field":"amount_eur","message":"parse-number could not read the value"}
Payload:   {"customer":"Globex","amount_eur":"n/a","invoice_no":"F-1002"}
Resolution: open
```

- Three modes: `soft` (the default) writes the row; `dlq` sets it aside; `fail` stops the run.
- `fields` names the `column:` of a hook. Full example: `docs/connectors/csv/72-on-error-dlq.yaml`.

## Broken text

### `clean` — BOM, stray whitespace, control characters

```yaml
cell:
  - clean: { columns: [name, note], strip: true, remove-control-chars: true, normalize-unicode: NFC }
```

| In | Out |
|---|---|
| `﻿  hello\t` (a BOM, two spaces, a tab) | `hello` |
| `Café` written as `e` + a separate accent | `Café` with one `é` (`NFC`) |

- A BOM at the start is always removed. The three switches are off by default.
- Use `NFC` when two exports spell the same accent two ways and your keys stop matching.

### `fix-mojibake` — accents came out as `BÃ©nÃ©dict`

A Windows export often garbles accents. This hook repairs them, only when it recognizes the
pattern:

```yaml
cell:
  - fix-mojibake: { columns: [name, city] }
```

| In | Out |
|---|---|
| `BÃ©nÃ©dict Martin` | `Bénédict Martin` |
| `CAFÃ‰ DU PORT`, `lâ€™atelier`, `10 â‚¬` | `CAFÉ DU PORT`, `l’atelier`, `10 €` |
| `Bénédict Martin` | unchanged: a clean value is never touched |
| `BÃ©nÃ©dict €` | **unchanged**, +1 `cell_errors`: repairing it would garble the `€` |

A value it declines lands as it arrived. Re-export it from the source with the right encoding.

### `decode-html-entities` — `&amp;` in text

| In | Out |
|---|---|
| `Eau &amp; Soleil` | `Eau & Soleil` |
| `caf&eacute;` | `café` |

### `replace-smart-quotes` — curly quotes from Word and Excel

| In | Out |
|---|---|
| `“Eau & Soleil” – depuis 1998…` | `"Eau & Soleil" - depuis 1998...` |
| `l’été` | `l'été` |
| `«bonjour»` | `"bonjour"` |

### `strip-invisible` — a code that "looks equal" but never matches

| In | Out |
|---|---|
| `AB​CD` (an invisible space inside) | `ABCD` |
| `soft­hyphen` | `softhyphen` |

### `remove-diacritics` — ASCII-only identifiers

| In | Out |
|---|---|
| `Montréal` | `Montreal` |

### `upper` / `lower` / `titlecase` — case

| Hook | In | Out |
|---|---|---|
| `upper` | `sarl` | `SARL` |
| `lower` | `Montreal` | `montreal` |
| `titlecase` | `MARTIN  DUPONT` (two spaces) | `Martin Dupont` |

They change text cells only. `titlecase` also collapses repeated spaces.

### `truncate` — hard cut

`- truncate: { column: name, length: 60 }` keeps the first 60 characters.

All these hooks take `columns: [a, b]`, or every column when `columns` is left out:

```yaml
cell:
  - decode-html-entities: { columns: [slogan] }
  - replace-smart-quotes: { columns: [slogan] }
  - strip-invisible: { columns: [code] }
  - remove-diacritics: { columns: [city] }
  - titlecase: { columns: [name] }
```

## Dates and amounts

### `parse-date` — dates are `31/12/2019`

```yaml
cell:
  - parse-date: { column: signup_date, formats: ["dd/MM/yyyy", "yyyy-MM-dd HH:mm:ss"] }
```

| In | Out |
|---|---|
| `31/12/2019` | `2019-12-31`: a **date** |
| `2004-10-18 23:42:39` | `2004-10-18 23:42:39+00`: a **timestamp** |
| `12/31/2019` | *(empty)*, +1 `cell_errors`: no format matched |

- Formats are tried in order.
- `dd` is the day, `MM` the month, `yyyy` the year. **Lowercase `mm` means minutes**: the classic
  trap.
- A format with a time (`HH:mm`) gives a timestamp. Without, a date.

#### Which time zone a time is in

An ERP writes `2026-08-02 23:30:00` without a time zone. Say which one it is:

```yaml
cell:
  - parse-date: { column: stamp, formats: ["yyyy-MM-dd HH:mm:ss"], zone: Europe/Paris }
```

| In | `zone` | Stored (shown in UTC) |
|---|---|---|
| `2026-08-02 23:30:00` | *(none)* | `2026-08-02 23:30:00+00`: read as UTC |
| `2026-08-02 23:30:00` | `Europe/Paris` | `2026-08-02 21:30:00+00`: Paris is UTC+2 in summer |
| `2026-01-15 23:30:00` | `Europe/Paris` | `2026-01-15 22:30:00+00`: UTC+1 in winter |
| `2026-08-02` | `Europe/Paris` | `2026-08-02`: a date has no time |

- An unknown zone is refused at `lm validate`.
- Fusing these dates with an ERP's in an entity: [Dates across sources](../entities-types.md).

### `parse-number` — numbers are `1 234,56 €` or `(123)`

```yaml
cell:
  - parse-number: { column: balance, decimal-separator: ",", negatives-in-parens: true }
```

| In | Out |
|---|---|
| `1 234,56 €` | `1234.56` |
| `(123)` | `-123` |
| `$1,234.50` with `decimal-separator: "."` | `1234.50` |
| `n/a` | *(empty)*, +1 `cell_errors` |

It removes currency signs and spaces. A new column holds decimals: `1024944` reads
`1024944.0000`.

#### An integer column: `integer: true`

An identifier or a count is a whole number. Say so:

```yaml
cell:
  - parse-number: { column: m_product_id, integer: true }
```

| In | Out |
|---|---|
| `1024944` | `1024944`: an **integer** |
| `7.0000` | `7` |
| `7.1` | *(empty)*, +1 `cell_errors`: not a whole number |

**A column is typed when the table is created.** Two consequences:

- If the first rows leave the column empty, it is created as text, and stays text.
- Adding `parse-date` or `parse-number` to a connector that already ran does not change its
  column. Create the table again: [the four steps](../entities-types.md#type-it-at-the-door-four-steps).

## Codes and labels

### `lookup` — translate codes with an inline table

```yaml
cell:
  - lookup: { column: is_active, table: { "Y": "true", "N": "false" }, on-missing: null }
```

| In | `on-missing: keep` *(default)* | `null` | `error` | `INACTIVE` (any value) |
|---|---|---|---|---|
| `Y` | `true` | `true` | `true` | `true` |
| `?` | `?` | *(empty)*, +1 `cell_errors` | **the run fails** | `INACTIVE` |

The translated values land as text.

### `default` — fill one blank column

```yaml
cell:
  - default: { column: legal_form, value: "NONE" }
```

| In | Out |
|---|---|
| `SARL` | `SARL`: never overwritten |
| *(empty)* | `NONE` |

## Emails and phones

### `parse-email` — trim, lowercase, validate

`- parse-email: { column: contact_email, lower: true }`

| In | Out |
|---|---|
| `  Contact@Example.COM ` | `contact@example.com` |
| `contact at example` | *(empty)*, +1 `cell_errors` |

### `parse-phone` — one format for every phone

`- parse-phone: { column: phone, default-region: FR }`

| In | Out |
|---|---|
| `06 12 34 56 78` | `+33612345678` |
| `+1 415 555 2671` | `+14155552671`: a full number ignores the region |
| `abc` | *(empty)*, +1 `cell_errors` |

Set `default-region` to the source's country. The default is `US`: French numbers would land wrong.

## Patterns

### `regex-replace` — normalise with a pattern

```yaml
cell:
  - regex-replace: { column: name, pattern: "\\s+", replacement: " " }
```

| In | Out |
|---|---|
| `DUPONT   SARL` | `DUPONT SARL` |

- Every match is replaced. Without `replacement`, the match is deleted.
- In `replacement`, `$1` is the first group. Write a literal `$` as `\\$`.

### `regex-extract` — pull a piece into its own column

```yaml
cell:
  - regex-extract:
      column: name
      pattern: "\\b(SARL|SAS|EURL|SCI)\\b"
      groups: { 1: legal_form }
```

| `name` | → `legal_form` |
|---|---|
| `DUPONT SARL` | `SARL` |
| `Standard` | *(empty)*: no match |

- Each group writes into a **new** column. `lm validate` refuses a column the row already holds.
  To rewrite a value in place, use `regex-replace`.
- A miss is not counted: an optional field misses on many rows. A column that is empty on every
  row means the pattern never matched.
- Every row must match? Add `required: true`: a miss then counts one `cell_errors`.
- Follow it with [`default`](#default-fill-one-blank-column) to give the misses a value.

## Several values in one cell

### `split` — parts into new columns

```yaml
cell:
  - split: { column: tags, by: ";", into: [tag1, tag2, tag3] }
```

| `tags` | `tag1` | `tag2` | `tag3` | `cell_errors` |
|---|---|---|---|---|
| `retail;online;b2b` | `retail` | `online` | `b2b` | — |
| `retail;online` | `retail` | `online` | *(empty)* | **+1**: fewer parts |
| `a;b;c;d` | `a` | `b` | `c` | **+1**: `d` has nowhere to go |

The row stays one row. `into-array: true` writes one array instead.

### `split-row` — one row per value of `a;b;c`

An order line lists its items in one cell:

```csv
order_no,items
ORD-1,TSH-BLEU-M|TSH-BLEU-M|CAP-NOIR
```

`split-row` makes one row per item. Two identical items would land as one row. Add `position`:
each row then carries its place, and both land.

```yaml
row:
  - split-row: { by: items, sep: "|", position: item_no }
```

| Without `position` | | With `position` | | |
|---|---|---|---|---|
| `order_no` | `items` | `order_no` | `items` | `item_no` |
| `ORD-1` | `TSH-BLEU-M` | `ORD-1` | `TSH-BLEU-M` | `1` |
| `ORD-1` | `CAP-NOIR` | `ORD-1` | `TSH-BLEU-M` | `2` |
| | | `ORD-1` | `CAP-NOIR` | `3` |

- The other columns are copied onto each row. `IN` and `OUT` still count one source row.
- `position` names a new column.
- A separator written inside a value, such as EDIFACT's `?'`: add `escape: "?"`.

## A value written once, for the rows below

### `fill-down` — a value written once, for the rows that follow

A bank statement writes the account number once, on the first row of its block:

```csv
--8<-- "docs/connectors/csv/data/bank-statement.csv"
```

`fill-down` copies it onto the rows below, until the next account:

```yaml
  mapping:
    cell:
      - fill-down: { columns: [account] }
```

```
$ lm connector run bank-statement
$ lm run get 3df03f1c
ID        CONNECTOR       ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
3df03f1c  bank-statement  default   Completed  5   5    0        0             2026-10-24 22:15  2026-10-24 22:15
```

```
lumnik=> select account, booked_on, label, amount from connector.t_bank_statement;
    account     | booked_on  |        label         | amount
----------------+------------+----------------------+---------
 FR76-3000-0001 | 2026-10-01 | Solde initial        | 1200.00
 FR76-3000-0001 | 2026-10-03 | Virement client ACME | 450.00
 FR76-3000-0001 | 2026-10-05 | Prélèvement URSSAF   | -320.00
 FR76-3000-0002 | 2026-10-01 | Solde initial        | 800.00
 FR76-3000-0002 | 2026-10-04 | Carte bancaire       | -42.50
(5 rows)
```

Every movement now carries its account. Full example: `docs/connectors/csv/70-fill-down.yaml`.

**Start again at each block: `reset-when`.** An order export writes an `H` row (the order), then
its `L` rows (the lines). The second order has no customer:

```csv
--8<-- "docs/connectors/csv/data/order-lines.csv"
```

Without care, `SO-2` would inherit `ACME`. `reset-when` forgets what is carried at each `H` row:

```yaml
      - fill-down: { columns: [order_no, customer], reset-when: { column: type, in: [H] } }
```

```
lumnik=> select type, order_no, customer, sku, qty from connector.t_order_lines;
 type | order_no | customer |   sku   | qty
------+----------+----------+---------+-----
 H    | SO-1     | ACME     |         |
 L    | SO-1     | ACME     | PMP-100 | 2
 L    | SO-1     | ACME     | VAN-200 | 1
 H    | SO-2     |          |         |
 L    | SO-2     |          | FLT-300 | 5
(5 rows)
```

- Each file starts with nothing carried.
- A `filter-where` runs before: a row it drops is never carried from. Filter the `H` rows out
  later, in a view, not here.
- `fill-down` follows the file's order. `lm validate` refuses it beside `sort-by`.

Full example: `docs/connectors/csv/71-fill-down-reset.yaml`.

## Identifiers

### `to-uuid` — an id column holds junk like `N/A` or `0`

Two knobs: `generate` decides an **empty** cell, `on-invalid` a cell holding junk.

```yaml
cell:
  - to-uuid:
      column: customer_id
      generate: true          # an EMPTY cell gets a new id
      on-invalid: keep        # a cell holding "N/A" is left alone for you to look at
```

| The cell holds | `generate` | `on-invalid` | Result | `cell_errors` |
|---|---|---|---|---|
| a valid UUID | any | any | unchanged | — |
| nothing | `false` *(default)* | any | stays empty | — |
| nothing | `true` | any | a new id | — |
| `N/A` | any | `null` *(default)* | emptied | **+1** |
| `N/A` | any | `generate` | a new id **in place of** `N/A` | **+1** |
| `N/A` | any | `keep` | `N/A`, untouched | — |

**A generated id changes at every run.** To keep one id per row, derive it from a column:

```yaml
cell:
  - to-uuid: { column: ext_id, generate-from: code }   # F0004 gets the same id every night
```

!!! warning "`on-invalid: generate` invents an identifier"
    The new id replaces a value your source sent, and joins to nothing elsewhere. Only a few rows
    dirty? Run once with `keep` and look at them first.

## Sensitive data

### `mask` — a column holds card/PII data

```yaml
cell:
  - mask: { column: card_number, keep-last: 4 }
```

| In | Out |
|---|---|
| `4556123498761234` | `************1234` |
| `contact@example.com` with `keep-first: 2, keep-last: 4` | `co*************.com` |
| `1234` | `1234`: **not masked**, too short |

!!! warning "Two things to know before trusting `mask`"
    - A value not longer than `keep-first + keep-last` lands unmasked.
    - **A dead letter keeps the raw value.** A row set aside before the cell tier is shown unmasked
      by `lm dlq get`.

## Provenance and order

### `enrich-context` — stamp provenance on every row

```yaml
dataset:
  - enrich-context: { source_system: siebel, ingested_at: "${now}" }
```

Every row gains `source_system = siebel` and the moment of ingestion. An existing value is
never overwritten.

### `sort-by` — order within the chunk

`- sort-by: { columns: [c_bpartner_id] }` sorts each block of 500 rows, as text, empty values
first. It never sorts the whole file.

## Order matters

!!! tip "Three placements to get right"
    - `validate-row` **before** `split-row`. `lm validate` refuses the other order.
    - `regex-extract` **before** `mask`: after `mask`, the value is stars.
    - `default` **after** the `regex-extract` whose misses it fills.

## Troubleshooting

| You see | Do this |
|---|---|
| Rows missing, and no dead letter | A `filter-where` or `skip-empty-rows` dropped them. Read `SKIPPED` in `lm run get`. |
| `DEAD-LETTERS` > 0, reason `MalformedSource` | The file itself is broken: wrong column count, unclosed quote. `lm dlq get <id>` shows the line. Fix the export. |
| A column is empty on some rows, the rows landed | A hook could not read those values. `lm run get` warns with `⚠ N cell(s) a hook did not deliver`. |
| A `regex-extract` column is empty on every row | The pattern never matched. Test it on a real value. |
| The mapping seems to do nothing | You loaded with `lm csv ingest`, which never transforms. |
| `lm validate`: `unknown cell hook: …` | A typo, or the hook is under the wrong tier. The refusal lists the tier's hooks. |
| A run fails and says `run lm endpoint reset-cursor <connector>/<endpoint>` | The file or the mapping changed while a `fill-down` run had stopped halfway. Run the command: the next run reads the file again from its start. |
| `aggregator is not supported on csv-file connector runs` | Group the rows in the source export instead. |
| `lm validate` refuses `spec.mapping.cell[N].kotlin` | [Kotlin hooks](kotlin-hooks.md#check-what-the-hub-holds) |

## See also

- [Transformers — reference](transformers-reference.md): every hook and parameter.
- [Kotlin hooks](kotlin-hooks.md): your own code, when no transformer fits.
- [CSV & files](csv.md): the connector these hooks run in.
- `docs/connectors/csv/` in the repository: the runnable manifests.
