# Changelog — lumnik (open edition)

The open edition is a projection of the lumnik monorepo, republished per release. Version numbers
are aligned with it from v1.4.0 on: open-edition `vX.Y.Z` is the projection of monorepo `vX.Y.Z`.
Releases 1.1–1.3 existed upstream and were never published here — absent, not withdrawn.

This file lists only what ships in this repository. Closed modules (the RAG store, the langchain4j
binding, the Kafka connector, SCIM) are not part of it, and changes to them do not appear here.

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
