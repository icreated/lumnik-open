# Reading a run — what was ingested, skipped, quarantined

> A run cannot look clean while it lost rows. Every row read is written, skipped, or set aside
> with its reason: `IN = OUT + SKIPPED`.

## The counters

```
$ lm run get 8ffcd5ae
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
8ffcd5ae  orders     default   Completed  10  8    2        2             2026-10-19 01:47  2026-10-19 01:47
⚠ 2 rows quarantined — see: lm dlq list --run 8ffcd5ae-c387-45b9-8a57-091043af6c59
```

`10 = 8 + 2`, and both skipped rows are dead letters.

| Counter | Counts |
|---|---|
| `IN` | the rows read from the source |
| `OUT` | the source rows written. A row `split-row` cut in three still counts one |
| `SKIPPED` | the rows not written: filtered out on purpose, already present, or set aside |
| `DEAD-LETTERS` | the part of `SKIPPED` set aside with its reason, for you to look at |

| Not written | Reasons | A dead letter? |
|---|---|---|
| on purpose | a `filter-where` or `skip-empty-rows` dropped it; the row was already there | no |
| set aside | `MalformedSource` (the file is broken), `MappingFailed` (a hook could not read a value), `ValidationFailed` (`validate-row`), `WriteFailed` | yes |

## The lines under the table

`lm run get` adds a line for each of these, on stderr:

| Line | Means | Do this |
|---|---|---|
| `⚠ N rows quarantined — see: lm dlq list --run …` | rows set aside | [Diagnosing a failed run](../recipes/diagnose-a-run.md) |
| `⚠ N cell(s) a hook did not deliver as declared — the rows were still written` | a value a hook could not read was emptied, or left as it was | [cell_errors](../connectors/transformers-reference.md#when-a-hook-cannot-deliver-cell_errors-and-on-error) |
| `• N row(s) removed — the source no longer holds them` | a one-file `csv-file` connector followed its file | [Full refresh](../connectors/csv.md#one-file-is-the-table-full-refresh) |
| `• N file(s) unchanged since they were last read — not read again` | the file did not change: `IN 0` is normal | to read it again: `lm endpoint reset-cursor <connector>/<endpoint>` |
| `• N file(s) read but not archived — still in the source folder …` | the archive step failed; the rows are in | check the archive folder's rights |
| `• script hooks:… sha256 …` | the Kotlin script version the run used | [Kotlin hooks](../connectors/kotlin-hooks.md#which-version-a-run-used) |
| `✖ run failed: …`, `• run partial: …`, `• run cancelled: …` | why the run ended so | read the reason; it names the gesture |

A full refresh that would remove too much fails, and removes nothing:

```
$ lm run get a569be52
ID        CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
a569be52  orders     default   Failed  5   5    0        0             2026-10-19 01:47  2026-10-19 01:47
✖ run failed: 3 of 8 rows would be removed: the file holds 5 where the table held 8, more than refresh.max_removed (30%) allows — nothing was removed. If the file really lost them, raise refresh.max_removed (or set it to off) and run again.
```

## From a count to the row

`lm dlq list --run <id>` lists the dead letters; `lm dlq get <id>` shows one, with the row exactly
as it was read. Fix the source, run again, then close the entry. The walk, step by step:
[Diagnosing a failed run](../recipes/diagnose-a-run.md).

## In your monitoring

`/q/metrics` counts runs by status and dead letters by reason:
[Observability](../operations/observability.md).
