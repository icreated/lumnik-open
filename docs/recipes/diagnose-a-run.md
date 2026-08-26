# Diagnosing a failed run

> A run went wrong — read the ledger, not the logs, first: `lm run get` and `lm dlq` tell you
> exactly which rows fell out and why before you go looking for a stack trace.

A run went wrong: it reports a failure, or fewer rows arrived than you expected.
The good news is that a run can never *hide* what happened — its arithmetic always
closes, and every quarantined row keeps its reason and its payload. The diagnosis is
a short walk:

```mermaid
flowchart TB
  s["a run went wrong"] --> g["lm run get RUN_ID"]
  g -->|"the run itself failed —<br/>no ledger to read"| logs["the hub logs"]
  g -->|"dead-letters > 0"| dlq["lm dlq list --run RUN_ID"]
  g -->|"arithmetic clean, but<br/>expected rows absent"| cursor["the cursor"]
  dlq --> why["lm dlq get ID —<br/>the reason and the exact row"]
  why --> fix["fix by reason<br/>(table below)"]
  fix --> rerun["lm connector run NAME —<br/>then mark the entries handled"]
```

## 1. Read the run

```bash
lm run list --connector my-erp    # newest first — find the run
lm run get <RUN_ID>               # IN, OUT, SKIPPED, DEAD-LETTERS
```

Three shapes of trouble, three directions:

- **The run itself failed** (connection refused, credentials, the source gone) —
  there is no row-level story to read, but `lm run get` already prints the reason on stderr
  (`✖ run failed: …`, or `• run cancelled: …` if you stopped it yourself — the label follows
  the actual status). If that message isn't enough, go to [the hub logs](logs.md).
- **Dead-letters > 0** — rows were quarantined, each with a reason. Continue below.
- **The arithmetic is clean but rows you expected are absent** — an incremental
  connector only fetches *past its cursor*. `lm endpoint reset-cursor
  <connector>/<endpoint>` makes the next run a full refresh.
- **A value inside a row is blank, but the row itself is there** — a
  [transformer](../connectors/transformers.md) cell hook (`parse-date`, `parse-number`,
  `parse-email`, `parse-phone`, `lookup`) may have failed to read it and nulled the cell
  rather than dropping the row. Check `cell_errors`: `lm run get <RUN_ID>` prints a
  stderr warning only when the count is above zero, `-o json` carries the key
  `cellErrors`, and the TUI describe panel shows a `cell_errors:` line.

## 2. Read the quarantine

```bash
lm dlq list --run <RUN_ID>    # every quarantined row: reason, attempts, message
lm dlq get <ID>               # the full detail — including the offending payload
```

| Reason | What it means | Where to look |
|---|---|---|
| `MalformedSource` | the source row itself is broken — encoding, truncated line | the file or export; a [transformer](../connectors/transformers.md) can often repair it at the door |
| `MappingFailed` | the mapping (or a [hook](../connectors/transformers.md)) threw on this row | `lm mapping get` — the template and its overrides |
| `ValidationFailed` | the row broke a declared expectation | the manifest's declaration vs. the data's reality |
| `WriteFailed` | the database write failed | the target table — types, drift; the hub logs have the SQL error |

Benign skips (`Duplicate`, `PolicyExcluded`) are **not** here — a skip is not a loss,
and it is not counted as one. The distinction is the point:
[ingestion honesty](../transparency/ingestion-honesty.md).

## 3. Fix, re-run, close the loop

Re-running after a failure is safe by design: a jdbc/CSV run resumes on its cursor — no
duplicates, no gaps ([watermark resume](../connectors/jdbc.md#watermark-resume-composite-cursor));
a REST run re-reads and upserts idempotently.

```bash
lm connector run my-erp                    # run again after the fix
lm dlq replayed <ID> --new-run-id <NEW_RUN_ID>    # link the entry to the run that replayed it
lm dlq discard <ID>                        # or: this row is not wanted — say so
```

Marking entries handled is not bookkeeping for its own sake: the DLQ is the list of
rows the hub still accounts for as *not* ingested. An empty DLQ is a claim —
keep it true.

In the TUI: `:runs` and `:dlq` live-update (`^R` marks replayed, `^D` discards) —
you can watch the re-run absorb the fix as it happens.
