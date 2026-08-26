# REST connector — pagination strategy library

This directory contains runnable `lm apply` manifests — five examples of the pagination
strategies supported by the `rest-generic` connector (the engine supports more: 8 kinds +
11 presets, see [rest.md](../rest.md)).  Each YAML file is both a working
example and a corpus entry for the E2E suite — they must not rot.

---

## Quick start

```bash
# Start WireMock (stubs the demo API on port 8081)
docker compose up -d wiremock

# Register and run a connector
lm apply -f docs/connectors/rest/30-cursor-bearer.yaml
lm connector run rest-cursor
```

---

## Files at a glance

| File | Pagination | Auth | Target table |
|------|-----------|------|--------------|
| `10-offset-none.yaml` | offset + limit | none | `ext.rest_offset` |
| `20-page-basic.yaml` | page number + has_more | HTTP Basic | `ext.rest_page` |
| `30-cursor-bearer.yaml` | opaque cursor | Bearer token | `ext.rest_cursor` |
| `40-link-header-apikey.yaml` | Link rel=next header | API key (header) | `ext.rest_link_header` |
| `50-relay-basic.yaml` | GraphQL Relay edges/pageInfo | HTTP Basic | `ext.rest_relay` |

---

## Prerequisites

All manifests point `base_url` at `http://localhost:8081` — the WireMock compose service
that ships with this repo.  Start it before running any manifest:

```bash
docker compose up -d wiremock
```

The WireMock stubs live in `scripts/proof/rest/wiremock/mappings/`.
Each stub mirrors the scenario exercised by the corresponding IT
two pages, three rows total.

---

## Credentials (env vars)

Manifests that use auth reference environment variables the **platform process** holds.
Inject them once before starting the platform:

| Variable | Used by | Description |
|----------|---------|-------------|
| `REST_DEMO_TOKEN` | `30-cursor-bearer.yaml` | Bearer token sent in `Authorization: Bearer …` |
| `REST_DEMO_BASIC_PW` | `20-page-basic.yaml`, `50-relay-basic.yaml` | Password for `demo-user` (Basic auth) |
| `REST_DEMO_APIKEY` | `40-link-header-apikey.yaml` | Value sent in `X-API-Key` header |

In dev, set them in the Quarkus dev-mode env or export before running the proof script:

```bash
export REST_DEMO_TOKEN=demo-token
export REST_DEMO_BASIC_PW=demo-pass
export REST_DEMO_APIKEY=demo-key
```

WireMock does **not** enforce auth — it matches stubs on path only — so any non-empty value
works for the demo.  The manifests still send the header; the ITs verify it is correct.

---

## Manifest → engine mapping

`RestConnectorApplyHandler` stores fields as follows (verified against the source):

- `spec.base_url` → `connector.config.base_url`
- `spec.auth` (whole map) → `connector.config.auth`
- Per endpoint: `id` → endpoint name, `method` → `connector_endpoint.method`,
  `path` → `connector_endpoint.path`; every other key (`pagination`, `target`,
  `incremental`, …) → stored verbatim into `connector_endpoint.config`

The runtime reads `pagination`, `target`, and `incremental` from the endpoint config,
so they must be declared **per endpoint** (not at the `spec` level).

---

## Running the full proof

```bash
# Prereqs: platform on :8080 + wiremock on :8081 + env vars above
bash scripts/proof/rest/00-rest-proof.sh
```

The script applies each manifest, triggers a run, polls the `ext.rest_*` tables, and
reports pass/fail counts.  If WireMock is not reachable it skips cleanly (ITs are the gate).

---

## Notes

- All target tables live in the `ext.*` schema (hybrid tables, RLS-isolated per tenant).
- Unique-key deduplication: re-running a connector is idempotent — rows are upserted, not duplicated.
- The integration tests exercise the same scenarios in-process with an embedded
  WireMock server; they are the real quality gate.  This library is the user-docs vitrine.
- Manifests are the live E2E corpus consumed by the proof script; treat any edit as a code
  change and verify it does not break the suite.
