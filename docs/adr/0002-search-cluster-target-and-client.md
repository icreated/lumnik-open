# ADR 0002: Search cluster target and client (D3/D4)

## Status

Accepted — 2026-09-30. Completes ADR 0001, which deferred D3 and D4 to the scheduling of #672.

## Context

The scale-out Search provider (#672) needs a cluster target (D3) and a client (D4). Pro must keep running without any cluster; the cluster stays an explicit packaging choice (#702).

## Decision

**D3 — OpenSearch is the shipped target** (2.x and 3.x; Apache-2.0, redistributable container). **Elasticsearch 8.x/9.x is a test-matrix line**, supported for customers who already operate a cluster; we do not bundle it.

**D4 — one adapter** on the JSON REST subset common to both families (index/mappings, `_bulk`, `_aliases`, `_count`, `_delete_by_query`, `_search` with `bool.filter` + `multi_match`/fuzziness + highlight + `terms` aggregations), over the JDK `HttpClient` and Jackson. The distribution and version are probed at boot (`GET /`) and checked against the supported matrix; an unsupported one is reported `degraded`, never a boot crash.

## Rationale

- Elasticsearch's licences (AGPLv3 / SSPL / ELv2 since 2024) are avoided in anything we redistribute; connecting to a customer's cluster is a separate matter.
- The Elasticsearch Java client refuses non-Elastic servers and `opensearch-java` targets OpenSearch only: vendor clients would force two adapters and a heavy opt-in classpath. The common subset makes Elasticsearch a matrix line rather than a second module.
- Authorization is a query input (#668), so no document-level-security plugin is required.

## Consequences

- Module `lumnik-search-opensearch`; a required feature that leaves the common subset (vectors/kNN, #675) reopens D4.
- The supported-version matrix is exercised by the container-backed IT lane of the nightly Search job.
- Index topology (D14) and everything else is in `docs/superpowers/specs/2026-09-30-search-opensearch-provider-design.md`.

## Rejected

Elasticsearch as the shipped target; two vendor-client adapters.

## References

#649, #668, #670, #672, #702, ADR 0001.
