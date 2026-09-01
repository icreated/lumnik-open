# Changelog — lumnik (open edition)

The open edition is a projection of the lumnik monorepo, republished per release. Version numbers
are aligned with it from v1.4.0 on: open-edition `vX.Y.Z` is the projection of monorepo `vX.Y.Z`.
Releases 1.1–1.3 existed upstream and were never published here — absent, not withdrawn.

This file lists only what ships in this repository. Closed modules (the RAG store, the langchain4j
binding, the Kafka connector, SCIM) are not part of it, and changes to them do not appear here.

## v1.5.2 — 2026-09-01

One installer fix, found during a real first install of this edition.

### Fixed
- **Re-running `deploy/selfhost/up.sh` on a fresh install broke the stack**: the script
  guessed the Postgres bootstrap superuser from the age of `.env` and could pin the
  legacy name onto a new volume, after which the password-alignment step failed with
  `FATAL: role "adempiere" does not exist`. It now records the name at first install
  and, for older installs, asks the running database which name the volume was
  initialized with. If you hit this: set `POSTGRES_USER=lumnik` in `.env` (or remove
  the line) and re-run `up.sh`.

## v1.5.1 — 2026-09-01

One fix, found by this edition's own release proofs before v1.5.0 could ship here —
which is why the open edition goes straight from v1.4.1 to v1.5.1.

### Fixed
- **Scope-bound reads (view render, view detail, workflow data-truth, ask execution)
  answered 500** on the Quarkus 3.33 platform: the connection pool now refuses a JDBC
  read-only toggle inside a transaction, and the tenant-scoping path used one. The toggle
  is removed; read-only-ness is enforced where it always was — by the `lumnik_readonly`
  database role (`default_transaction_read_only=on`, SELECT-only grants). An integration
  test now exercises the exact production posture.

## v1.5.0 — 2026-09-01

The platform catches up. Nothing to migrate: no schema change, no API change, the fast-jar
layout and every operator gesture are untouched.

### Platform
- **Quarkus 3.17 → 3.33 LTS** (upstream-patched until March 2027; Java 21 unchanged), carrying
  Hibernate ORM 7, Testcontainers 2 and the aligned extension ecosystem.

### Security
- **Ed25519 SFTP host keys now ride Bouncy Castle.** The unmaintained `net.i2p.crypto:eddsa`
  (Ed25519 signature-malleability advisory, never patched upstream) is removed. Host-key
  verification behaves exactly as before — and an integration test now asserts the negotiated
  key really is ed25519, so a silent fallback to RSA cannot pass unnoticed.

### Fixed
- `GET …/deliveries?limit=0` returned a 500 instead of an answer; the limit now clamps.
- A per-tenant hook-script edit could accumulate compiled scripts in memory indefinitely;
  script caches are bounded.
- The hub module build ran every Quarkus build step twice; builds are roughly 2× faster.

## v1.4.1 — 2026-08-30

Fixes only. Nothing to migrate, no schema change, no API removed.

Most of what follows came out of a single exercise: folding families of near-identical tests into
parameterized tables, then reading the axes the table exposed. Twelve defects surfaced that way, and
nine were reproduced by running the new rows before anything was fixed.

### Ask-to-SQL

- **The 200-row bound was not always applied.** The guard matched patterns against the model's raw
  output, so a query ending in a `--` comment received its `LIMIT 200` *inside* that comment and ran
  unbounded. Row-level security and the read-only datasource still held, so no data crossed a tenant
  boundary — but the bound this project promises did not hold. Both guard layers now read lexer
  tokens rather than text.
- **Honest questions were refused.** Reading raw text, the same guard blocked a customer in a town
  called Grant, a note containing the word "update", and a reference `A;B`. A `;` inside a string
  literal is a character again, and a quoted `"insert"` is a column name.
- **`NOT IN` was refused under a column that exists in no schema.** `WHERE segment NOT IN (…)` built
  its filter on the operator instead of the column, so the refusal named `"NOT"` — a daily shape of
  model output, refused for a reason the reader could not act on.
- **An accent split an identifier in two.** `WHERE crédit = 'X'` was refused as `"dit"`: the lexer
  read ASCII only, so an accent ended one identifier and the letters after it began another.
  Identifiers are Unicode now; numbers stay ASCII, because a foreign digit is not a page size.

### Ingestion

- **`split` with `into-array` produced a value that could not be stored.** The documented option
  puts a list in the row; it reached the `jsonb` column as `character varying` and Postgres refused
  it. A nested map failed earlier still, on hstore. Either way the chunk failed, the cursor did not
  advance, and the retry replayed the same failure. Both shapes now land as real `jsonb`, queryable
  with `@>` and indexable with a GIN index — when the chunk that creates the column carries a value.
  When it is null-only there, the column is typed `text` and stays that way; column types are never
  widened.
- **Four cell hooks crashed on a malformed manifest.** `truncate`, `mask`, `regex-replace` and
  `regex-extract` threw raw runtime errors on a bad parameter instead of refusing the manifest the
  way every other validator does.
- **`kind: Entity` silently ignored keys it does not honour.** A misspelled key was accepted and
  dropped. It is now named.
- **`default_rate_limit` was validated by nobody.** The value was read by the limiter and checked by
  no validator, so a nonsensical setting reached the engine intact.
- **A chunk template rendered `_id` as empty** into every chunk it produced.

### Métier scope and identity

- **The scope boundary did not reach three app-ladder doors.** Entity apply, process apply and the
  process manifest store answered a scope-bound caller as if the caller were unscoped. They now give
  the same uniform refusal as every other scoped door.
- **The tenant in the URL is now compared to the caller's own.** Ten identity endpoints took the
  tenant from the path without checking it against the caller's claim. Row-level security already
  blocked the rows — a cross-tenant request found nothing and returned `404` — so nothing was
  readable that should not have been. What changes is that the refusal now says what it means:
  `403`, not a `404` that misdescribes why.

### Tests

These are not behaviour changes, but this repository ships its tests and they are meant to be
read. Six inventory
tests were added: each enumerates its axis from the production source and fails in both directions,
so a new subject cannot arrive unguarded and a stale exemption cannot outlive its reason. A registered
after-each callback now rolls back any transaction a test leaves open, so one failing test can no
longer make an unrelated one fail after it.

## v1.4.0 — 2026-08-28

### Breaking

- **The business value-lists API is removed.** Migration `core/V42` drops `core.list_definition` and
  `core.list_value`; the `/api/tenants/{tid}/lists…` endpoints and the `list.value.*` events are gone
  from the catalog. Nothing in the platform ever read those tables — no ingestion, mapping,
  ask-to-SQL, view or workflow path consulted a value, and no enum carried the `@ListMirror`
  annotation its bootstrapper scanned for. **Export your rows before upgrading if you wrote any.**
  What that API reached for now exists in `kind:Workflow`: declared states, aliases for dirty legacy
  values, transitions.

- **A batch the writer refuses wholesale now fails the chunk** instead of reporting success. Before,
  a `WriteOutcome.AllFailed` left its rows outside the conservation identity
  (`records_in = records_out + records_skipped`), sent nothing to the dead-letter queue, and let the
  cursor advance past rows nothing had written. It now behaves like a read or prepare failure: no
  counters, no cursor movement. **Read this one if you build connectors on `BulkItemWriter` or
  `CopyItemWriter`** — they are the two writers that can return `AllFailed`.

### Fixed

- A window that yields no usable row but isolates malformed ones now counts them on both sides of the
  conservation identity and dead-letters them, instead of dropping them silently.
- Two authentication guards on `/api/platform/rag/*` now actually execute. They lived as nested test
  classes, which the test runner discovers by file name and therefore never ran; one of them also
  expected `403` where an anonymous request correctly gets `401`. A repository check now refuses that
  shape so a third cannot appear.
- A stream could leak on the CSV reader's fallback path. Latent — an upstream guard makes that branch
  unreachable today — but the trap is removed.

### Quality

- Blocker and critical static-analysis findings **25 → 2**; cognitive-complexity criticals **7 → 0**;
  reliability rating **E → A**. Reliability, security, security-review and maintainability all **A**;
  0 bugs, 0 vulnerabilities, 0 security hotspots to review.
- 2069 tests, 0 failures.

### Verified for this release

`mvn verify` on the extracted tree (freshly created, so there is nothing to clean) · `lm` builds
and its unit suite · the repository's guard
scripts · a from-scratch boot of the open edition · secret scanning. A red proof aborts the
publication before anything is pushed.

## v1.0.0 — 2026-08-26

First publication of the open edition.
