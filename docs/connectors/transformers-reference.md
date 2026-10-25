# Transformers — reference

> Every hook, its parameters and their defaults, on one page. The guide, by business problem:
> [Transformers](transformers.md).

## How `lm validate` checks a mapping

- A misspelt hook or parameter is refused, with the entry's path and the accepted names:
  `spec.mapping.cell[0].upper.colums` → `upper hook: unknown parameter 'colums' — accepted: columns`.
- A parameter of the wrong shape is refused the same way: a list where one name is expected,
  `"yes"` where `true` or `false` is expected, an empty list.
- A column that does not exist is refused **when the file's columns are known before it is
  read**: `parser.lines: true`, or a headerless file with `columns:` declared. With a header read
  from the file, the run names the missing column instead.
- A misspelt key under `mapping` is refused, naming the four it accepts:
  `spec.mapping.cel` → `mapping: unknown key 'cel' — accepted: dataset, row, cell, on-error`.

## Cell hooks

| Hook | Parameters (default) | What it does |
|---|---|---|
| `clean` | `columns`; `strip` (false); `remove-control-chars` (false); `normalize-unicode` (off; `NFC`/`NFD`/`NFKC`/`NFKD`) | always strips a leading BOM; then optional trim, control-character removal, Unicode normalization |
| `fix-mojibake` | `columns` | `BÃ©nÃ©dict` → `Bénédict`; leaves a value it cannot repair safely as it is, +1 `cell_errors` |
| `decode-html-entities` | `columns` | `&amp;` → `&` |
| `replace-smart-quotes` | `columns` | curly quotes and guillemets → `"`, dashes → `-`, ellipsis → `...`, non-breaking space → space |
| `strip-invisible` | `columns` | removes invisible characters: zero-width space, soft hyphen, BOM |
| `remove-diacritics` | `columns` | `Café` → `Cafe` |
| `upper` / `lower` | `columns` | case; text cells only |
| `titlecase` | `columns` | `DUPONT SARL` → `Dupont Sarl`; also collapses repeated spaces |
| `fill-down` | `columns`; `reset-when: { column, in }` (none) | an empty cell takes the last value above it, one file at a time; `reset-when` forgets it first |
| `truncate` | `column`; `length` (unlimited) | keeps the first `length` characters |
| `regex-replace` | `column`; `pattern`; `replacement` (`""`) | replaces every match; `$1` in `replacement` is a group |
| `regex-extract` | `column`; `pattern`; `groups` (group number → new column); `required` (false) | writes capture groups into new columns; a miss writes `null`, counted only with `required: true` |
| `default` | `column`; `value` | fills an empty cell; never overwrites |
| `lookup` | `column`; `table` (map); `on-missing` (`keep`; or `null` / `error` / any value) | translates a code with the map |
| `parse-date` | `column`; `formats` (list of date patterns); `zone` (UTC) | first matching format wins; a pattern with a time gives a timestamp, otherwise a date |
| `parse-number` | `column`; `decimal-separator` (`auto`; or `","` / `"."`); `negatives-in-parens` (false); `integer` (false) | `1 234,56 €` → `1234.56`; with `integer`, a whole number |
| `parse-email` | `column`; `lower` (false) | trims, optionally lowercases, checks the shape |
| `parse-phone` | `column`; `default-region` (`US`) | `06 12 34 56 78` → `+33612345678` with `FR` |
| `mask` | `column`; `keep-first` (0); `keep-last` (0); `mask-char` (`*`) | masks the middle of the value |
| `split` | `column`; `by` (`,`); `into` (list) or `into-array` (false) | parts into new columns, or an array |
| `to-uuid` | `column`; `generate` (false); `generate-from` (column); `on-invalid` (`null`; or `keep` / `generate`) | makes the column a UUID — [every case](transformers.md#to-uuid-an-id-column-holds-junk-like-na-or-0) |
| `kotlin` | `script` (`hooks:<name>.kts` or `classpath:<path>`); `context` (map) | your own code — [Kotlin hooks](kotlin-hooks.md) |

- `columns` left out applies the hook to every column of the row.
- A single-column hook takes `column:`, never `columns:`.

## Row hooks

| Hook | Parameters (default) | What it does |
|---|---|---|
| `map-columns` | `rename` (map old → new); `drop` (list) | renames, then drops — `drop` uses the new names |
| `coalesce` | `column`; `from` (list) | an empty target takes the first non-empty column of `from` |
| `defaults` | a map `column: value` | fills each listed column when empty |
| `validate-row` | `required` (list) | a row with an empty required column goes to the dead letters (`ValidationFailed`); place it before `split-row` |
| `split-row` | `by` (column) and `sep` (`,`); `escape` (one character); `position` (new column) — or `explode` (a column holding an array, from `split: { into-array: true }`) | one row per part, the other columns copied; `position` numbers the parts |

## Dataset hooks

| Hook | Parameters (default) | What it does |
|---|---|---|
| `require-headers` | `columns` (all must exist); `any-of` (one must) | fails the run when the file has the wrong header |
| `skip-empty-rows` | — | drops blank rows; counted in `SKIPPED` |
| `filter-where` | `column`; `equals`; `in` (list); `regex` (whole value) | keeps the rows matching every criterion; the others count in `SKIPPED` |
| `sort-by` | `columns` (list) | sorts each block of 500 rows, never the whole file |
| `enrich-context` | a map `column: value`; `${now}` = the moment of ingestion | adds a constant column; never overwrites |

## When a hook cannot deliver: `cell_errors` and `on-error`

`cell_errors` counts the cells a hook could not deliver — one per cell:

| The cell is | Hooks |
|---|---|
| **emptied** | `parse-date`, `parse-number`, `parse-email`, `parse-phone`, `lookup` with `on-missing: null`, `to-uuid` with `on-invalid: null`, `regex-extract` with `required: true` |
| **left as it arrived** | `fix-mojibake` declined; `split` found more or fewer parts than `into` |
| **replaced** by a value the source never sent | `to-uuid` with `on-invalid: generate` |
| **overwritten** | `map-columns` renamed onto a column the row already had |
| **declared unreadable** | `kotlin`, by the script |

`lm run get` prints the alert when the count is above zero:

```
⚠ 1 cell(s) a hook did not deliver as declared — the rows were still written
```

`lm run get <id> -o json` (or `-o yaml`) carries the count as `cellErrors`. What happens to the row, per column:

| `on-error` mode | The row |
|---|---|
| `soft` (default) | is written, the cell emptied; `cell_errors` counts it |
| `dlq` | goes to the dead letters as it was read (`MappingFailed`) — [example](transformers.md#an-empty-amount-is-a-wrong-row-on-error-dlq) |
| `fail` | stops the run |

- `fields` names the `column:` of a single-column hook. Every other hook follows `default`.
- `dlq` is refused beside `split-row`: it would set aside parts, not source rows.
