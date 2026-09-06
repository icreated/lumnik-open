# Ask honesty — the five guards

!!! info "Which builds can run this"
    The pipeline on this page is **native to the hub**: `AskToSqlAgent` and all five guards
    live in `lumnik-hub/…/ask/` and ship in every edition, the open one included. What an
    **open-edition** build lacks is the way *in* and the model *behind* — both chat doors are
    in `lumnik-rag` (`/api/platform/rag/chat`, behind `lm ask` and TUI `:ask`; `/api/rag/chat`,
    behind the PWA chat), and every `ChatPort`/`EmbeddingPort` implementation is in
    `lumnik-llm-langchain`. Neither module is in that tree, so the guards are there and nothing
    can reach them. Check with `GET /api/platform/health`: a build with the chat extensions
    lists both a `rag` module (the doors) and an `llm` one (the model provider); an open-edition
    one lists only `core` and `identity`.

When you ask your data an **analytic** question, lumnik translates it to SQL and runs it
read-only over the hub. Every door that asks in analytic mode rides the same pipeline —
`lm ask`, TUI `:ask`, the PWA chat, or `POST /api/rag/chat` with `mode: analytic` — so the
guards below apply to all of them. (`mode: semantic` is retrieval-only: it cites ingested
chunks and never generates SQL, so these guards don't come into play there.) Before and after that run, a pipeline of **deterministic guards** enforces
one principle — the *asymmetry of harm*:

> A refusal costs you a retry. A confident wrong answer — especially a confident **"there are
> none"** when there are some — costs you a business decision. lumnik always prefers the refusal.

Every refusal below is deterministic (no LLM judges the veto), and each message tells you what to
do next. The messages are quoted from the guard sources (the same strings the test suite asserts);
the `<bracketed>` parts and `…` are editorial — at runtime they carry your actual value, column,
table, and the scope's real lists.

## The pipeline, in order

!!! note "Seven stages, five guards — the arithmetic, before the client counts"
    The diagram numbers seven stages; the name says five guards. Abstention is **one guard
    standing at two doors** (floor and exit), and SQL safety is **its own layer beneath the
    guards**, not one of them. Count stages on the diagram, guards in the name.

```mermaid
flowchart TB
  q(["your question"]) --> g1
  g1["1 · abstention floor"] --> gen["SQL generation"]
  gen --> g2["2 · abstention exit"]
  g2 --> g3["3 · SQL safety"]
  g3 --> g4["4 · leak guard"]
  g4 --> g5["5 · undocumented-column guard"]
  g5 --> g6["6 · domain guard"]
  g6 --> exec["read-only execution"]
  exec --> g7["7 · zero-row honesty"]
  g7 --> ans(["the answer — always with the SQL that ran"])
  g1 -.-> ref(["honest refusal,<br/>with what to do next"])
  g2 -.-> ref
  g3 -.-> ref
  g4 -.-> ref
  g5 -.-> ref
  g6 -.-> ref
```

Stages 1–6 each eject to a refusal when they trigger. Stage 7 never refuses — an
empty result is delivered, reframed honestly instead of becoming a confident *"there
are none"*.

### 1. Abstention floor — nothing declared yet

**Trigger:** the scope has no schema at all (no tables, or tables without columns).

> I have no data declared for this scope yet, so I can't answer that. Ingest a source for this scope first.

**Do next:** apply a connector with `metadata.scopes: [<your-scope>]` and run it.

### 2. Abstention exit — the model's honest "I can't"

**Trigger:** the SQL-generation model concludes the question doesn't map to the declared schema
and emits its sentinel (`NO_ANSWER`) instead of guessing a query.

> I can't answer that from the data available for this scope — the question doesn't map to the declared schema.

**Do next:** rephrase using the columns the scope actually declares (`lm view` shows them), or
ingest/declare the missing data.

!!! note "Sometimes the abstention fires before a more specific guard"
    If you ask about a value that is outside a declared domain (e.g. a `segment` that doesn't
    exist), the model may abstain *before* the domain guard gets a chance to name the exact
    mismatch. You get an honest refusal either way, but a generic one — re-ask with a declared
    value to see the specific hint.

### 3. SQL safety — read-only, bounded

**Trigger:** the generated SQL is anything but one clean read: not a single `SELECT`/`WITH`
statement, multiple statements, or a banned token (DML/DDL keywords, `pg_sleep`, `pg_catalog`,
`information_schema`, `dblink`, …).

> I couldn't translate that into a safe query.

Accepted queries are also capped with `LIMIT 500` (an existing larger `LIMIT` is lowered).
Execution runs on a read-only connection with an **empty `search_path`** — every table must be
schema-qualified, so an unqualified (potentially ambiguous) name cannot resolve.

### 4. Leak guard — the métier boundary

**Trigger:** the query reads a schema-qualified table that is not declared in this scope's card.
Row-Level Security already isolates *tenants*; this guard is the *scope* boundary within a tenant.
(Choosing the scope itself can also be access-controlled per user — see
[binding users to scopes](../rag/scopes.md#binding-users-to-scopes-opt-in): a scope-bound user
gets `403 scope not granted` before any SQL is even generated.)

> I won't run this: '<schema.table>' is not a table in this scope (declared: …). The query may not read data outside this métier's declared scope.

**Do next:** switch to the scope that owns the table (`lm scopes`), or add this scope to the
connector's `metadata.scopes` and re-run it.

### 5. Undocumented-column guard

**Trigger:** the query filters a column that is declared in **no** table of the scope — an
invented column, or an internal one (`tenant_id`, `_row_hash`) the chat was never meant to
reason over.

> I won't run this: '<column>' is not a declared column of this scope (known: …). If it should exist, add it to the entity manifest.

(The "known" list is capped at 12 names to keep the message readable.)

### 6. Domain guard — the declared-values contract

**Trigger:** the query filters a column on a literal that is **not** in that column's declared
value-domain (the entity manifest's `values:` list). This is the direct counter to the cardinal
sin: a query that returns 0 rows *for the wrong reason*, then gets phrased as a confident
"there are none".

> I won't run this: '<value>' is not a declared value of <column> (known: …). If it should exist, add it to the entity manifest.

**Do next:** if the value is legitimate, add it to the entity manifest's declared values and
re-apply; if it was a typo, re-ask with a declared value.

### 7. Zero-row honesty — never a confident negative

**Trigger:** the query ran fine and returned 0 rows.

> The query returned no rows. That can mean there are genuinely none, or that the query did not capture your question — verify the SQL before reporting that there are none.

The SQL that ran is always shown with the answer (CLI prints it; TUI lets you yank it with `y`) —
verification is one glance away.

## Fail-open by design

The value/column/table guards inspect only **clean, unambiguous** SQL shapes (bare identifiers
compared to string literals). A function-wrapped column (`lower(col) = …`), a qualified alias, or
an ambiguous name across tables is deliberately *not* refused — better to miss one check than to
block a legitimate query on a parsing glitch. The guards are a floor of honesty, not a cage.

## When a wrong number lands anyway

The guards lower the odds; they do not abolish them. The circuit, honestly:

- **Detect** — the SQL travels with every answer: reading it *is* the verification
  gesture, and the reason a wrong number is catchable at all.
- **Report** — there is **no product channel today**: no button, no endpoint (the
  [security contact](https://github.com/icreated/lumnik/blob/main/SECURITY.md) is for
  vulnerabilities only). The circuit is your integrator.
- **Fix** — a value or column the guards should have known: add it to the entity
  manifest, then `POST /api/platform/rag/rediscover`
  ([schema admin](../rag/http-api.md#schema-admin)).
- **Re-verify** — the faithfulness judge exists ([Models → Judge](../rag/models.md)) but
  only as an offline eval harness in the test suite; there is **no operator procedure**
  "after a model update, run X" yet. Until there is, re-run the questions that went wrong
  — the SQL in each answer makes them replayable.

## See also

- [Ingestion honesty](ingestion-honesty.md) — the same principle on the way *in* (DLQ, quarantine,
  run transparency).
- [Scopes](../rag/scopes.md) — what a scope is and what the leak guard guarantees.
- [View generation contract](../apps/views.md#generation-contract) — the same abstention ethos when
  the AI *writes* a `kind: View`: unanswerable ask → refusal with the reason, never invented columns.
