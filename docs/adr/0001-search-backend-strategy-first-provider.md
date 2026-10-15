# ADR 0001: Search backend strategy — first provider (D1)

## Status

Accepted (D1 only) — 2026-09-28.

D3 (cluster target) and D4 (cluster client) are **not** decided by this ADR. They remain open and are deferred to when #672 (the scale-out cluster adapter) is scheduled — see [Deferred](#deferred).

## Context

Optional Pro search across ingested data (#649) needs a backend behind the provider-neutral `IndexedSearchPort` (#668). #670 framed three decisions: which provider ships first (D1), the cluster target if a cluster is involved (D3), and the cluster client (D4). The constraint that frames all three: Pro must run **without** Elasticsearch/OpenSearch by default — a cluster must never be the price of entry, and Search stays an explicit packaging choice (#702).

Two candidate "first provider" tickets already existed before this ADR: #705 (a PostgreSQL-native provider) and #672 (a cluster adapter for OpenSearch/Elasticsearch). Each names the other as its fallback slot depending on D1.

## Decision

**D1: the first Search provider is PostgreSQL-native (#705).**

The cluster adapter (#672) becomes the scale-out provider, built afterward — it starts after #674 (the provider contract suite), instead of taking the first slot.

## Rationale

- PostgreSQL-native reuses `pg_trgm`, `unaccent`, `fuzzystrmatch` and `tsvector` — all already present in the `pgvector/pgvector:pg16` image used by DevServices, selfhost and helm. Zero new infrastructure to ship the first provider.
- Isolation comes from RLS by construction, the same guarantee every other tenant table already gets, queried on the `default` datasource.
- It de-risks the rest of the chain: #671 (projection lifecycle), #673 and #674 (contract suite) can all be built and proven against the existing pgvector DevServices before any new container lane exists.
- The #669 matching vocabulary (`exact` / `text` / `fuzzy` / `prefix`) maps cleanly onto PostgreSQL primitives without needing a cluster's analyzer pipeline.
- The #704 relevance-corpus harness informs this decision (it can measure PostgreSQL-native's baseline quality directly) but does not block it, per #670's own framing.

## Consequences

- #705 is unblocked and proceeds as scoped, against this ADR as an accepted constraint.
- #672 is the scale-out provider: it starts after #674, not right after #671. Its own body already reflects this ordering.
- The known limit stated on #705 — projection writes and GIN maintenance share the primary database's resources — needs a measured sizing envelope (#704 + a `loadtest/` scenario). That measurement, and the decision of when an installation should graduate to #672, was split out of #672 into #750 (2026-09-30), not restated here.

## Deferred

D3 (cluster target — OpenSearch vs Elasticsearch) and D4 (cluster client — one shared adapter on the common JSON REST subset vs vendor-specific clients) are **not** decided by this ADR. #670's own non-goal states: "No production client dependency or backend module in this ticket" — and no cluster module is being designed now.

These two decisions will be settled when #672 is actually scheduled, the same way #672 already settles its own open decision (D14, index topology) "in spec" at build time. #670 stays open, scoped down to D3/D4, until then.

## References

- #649 (search: optional Pro search across ingested data without RAG)
- #668 (provider-neutral `IndexedSearchPort`)
- #669 (`SearchIndex` manifest and matching vocabulary)
- #670 (backend strategy — this ADR resolves its D1 only)
- #671 (rebuildable asynchronous projection lifecycle)
- #672 (opt-in cluster adapter — now the scale-out provider)
- #704 (relevance corpus + metric harness)
- #705 (PostgreSQL-native `IndexedSearchPort` provider)
