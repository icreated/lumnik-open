# lumnik — read your legacy systems without touching them

[![ci](https://github.com/icreated/lumnik-open/actions/workflows/ci.yml/badge.svg)](https://github.com/icreated/lumnik-open/actions/workflows/ci.yml)

A Siebel over SOAP, a CSV export, an AS/400 file: lumnik reads them with a read-only account,
fuses them into one record, and answers questions in plain language, with the SQL it ran.

```console
# A Siebel CRM over SOAP and a CSV export, read. Every row counted.
$ lm run list
ID        CONNECTOR         ENDPOINT   STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
26e45aa1  cust-csv          default    Completed  8   8    0        0             2026-11-09 14:18  2026-11-09 14:18
945e9b13  siebel-customers  customers  Completed  2   2    0        0             2026-11-09 14:18  2026-11-09 14:18

# One customer from two systems. When they disagree, both values stay.
$ lm view C-200 -f clients-view.yaml
Clients / C-200

  Code client   C-200
  Nom           Marie
  Ville         Lyon
  Segment       SILVER

  (!) Ambiguïtés et divergences
  Nom   cust_siebel «Marie» · t_cust_csv «MARIE A.»

# Ask in your own words: the answer shows its SQL. (Pro)
$ lm ask --scope clients "how many customers are in Lyon?"
There are 4 customers in Lyon.

  COUNT
  4
  1 row returned

  SQL: SELECT COUNT(*) FROM "connector"."v_customer" WHERE "city" = 'Lyon'
LIMIT 500

# No figure the data does not hold. (Pro)
$ lm ask --scope clients "how many customers are in the PLATINUM segment?"
I can't answer that from the data available for this scope — the question doesn't map to the declared schema.

# Order statuses written six ways: the one nobody declared is named.
$ lm workflow apply -f commande-workflow.yaml
Applied workflow "commande-lifecycle" → connector.t_commandes_csv.statut, scope demo
  in the data, not declared: EN LITIGE (2 rows)

# Orders that stop moving become decisions.
$ lm inbox
ID  WHEN      PROCESS                      MESSAGE                                                                                                                              ENTITY_KEY
11  just now  workflow:commande-lifecycle  CMD-007: observed unmoved in "EN LITIGE" since 2026-11-09 (first observed 2026-11-09) — declared threshold 1m in commande-lifecycle  CMD-007
10  just now  workflow:commande-lifecycle  CMD-006: observed unmoved in "EN_COURS" since 2026-11-09 (first observed 2026-11-09) — declared threshold 1m in commande-lifecycle   CMD-006
9   just now  workflow:commande-lifecycle  CMD-005: observed unmoved in "EN LITIGE" since 2026-11-09 (first observed 2026-11-09) — declared threshold 1m in commande-lifecycle  CMD-005
8   just now  workflow:commande-lifecycle  CMD-004: observed unmoved in "BROUILLON" since 2026-11-09 (first observed 2026-11-09) — declared threshold 1m in commande-lifecycle  CMD-004
7   just now  workflow:commande-lifecycle  CMD-001: observed unmoved in "EN_COURS" since 2026-11-09 (first observed 2026-11-09) — declared threshold 1m in commande-lifecycle   CMD-001
```

- **Reads, never writes.** A `SELECT`-only account, or the files and APIs as they come: JDBC,
  CSV, REST, SOAP, GraphQL, fixed-width, EBCDIC.
- **Every row accounted for.** `IN = OUT + SKIPPED`. A refused row is kept with its raw values
  and its cause.
- **Declared, not coded.** Sources, records, rules and lifecycles are YAML files, validated
  before they apply.

Every line above is the output of the [WOW walkthrough](/docs/wow.md), about 30 minutes on the
self-host stack: see [Quickstart](#quickstart).

**The name:** `lum` (light) + the Slavic agent suffix `-nik` — *the one who brings light into
the frozen ERP*. The CLI binary is `lm` (the lumen symbol).

## Where it stands

**beta.** lumnik no longer grows surfaces — the recent work is fixes, not new subsystems. What is
not here is not here on purpose, and every page says so in its own words. What we do not know yet:
nobody outside the team that wrote it has run lumnik in production.

[Where lumnik stands](/docs/where-lumnik-stands.md) gathers the whole boundary on one page — what
is deliberately absent, what is started and not finished, and the two things that have to be true
before that word goes away.

## What's in the box

- **Connectors, declarative-first** — JDBC, CSV (local + remote), REST (pagination
  presets + body-link), GraphQL sources, SOAP (Siebel QueryPage battle-tested), file drops.
  YAML manifests with a Kotlin escape hatch; per-row DLQ isolation; validate-then-apply.
- **The hub** — multi-tenant PostgreSQL 16 with Row-Level Security on every row, dynamic
  tables per source, fused `kind:Entity` views over many sources, a transactional outbox
  with webhook delivery, GDPR export/erase.
- **`lm`** — a k9s-style terminal UI and CLI driving connectors, runs, views and inbox,
  with an OIDC device-flow login.
- **The business-app PWA** at `/app/` — Preact without a bundler, phone=cards / tablet=table /
  display=rail; server-side `kind:View` manifests bound to a scope.
- **Self-host in one command** — `deploy/selfhost/up.sh`: hub + PostgreSQL + Keycloak on a
  VM, optional TLS façade with automatic Let's Encrypt.

## Quickstart

```bash
cp .env.example .env      # fill every value (the compose fails fast on empty secrets)
docker compose -f docker-compose.selfhost.yml up --build
```

Then walk the WOW: [docs/wow.md](/docs/wow.md) ingests a fake Siebel + a CSV drop, fuses
them into one `customer` entity, and serves it — every YAML in that page is transcluded
from the tested files in this repo, so the docs cannot drift from reality.

Full documentation: `make docs-setup && make docs-serve` (mkdocs-material).

## Build from source

```bash
# Java 21 + Maven for the hub
mvn verify

# Go for the lm CLI/TUI
cd lm && make build && make test
```

## This repository is a build artifact

lumnik is developed in a private monorepo; this repository is a projection of it, published
whole at each release after five proofs run green. **Pull requests opened here cannot be
merged** — the next publication refuses to run over a commit it did not author. Issues are
read, and a fix lands upstream and arrives in the next release. See
[CONTRIBUTING.md](CONTRIBUTING.md), and [SECURITY.md](SECURITY.md) for anything that should
not be public.

## lumnik Pro

The open edition is the complete defrost substrate. **lumnik Pro** adds the reasoning and
industrialisation layers on top: **Ask** (natural-language chat over your data, bound to
a scope, text-to-SQL behind five honesty guards), **Kafka / Event Hubs inbound streams**, and
the external-identity loop (external IdP, JIT provisioning, SCIM deprovisioning). A
Kubernetes delivery — Helm, sealed secrets, managed PostgreSQL — comes with it.

Three commercial tiers ride on two builds, and what a running hub can prove is which *build*
it is. [docs/editions.md](/docs/editions.md) draws the line feature by feature and shows the
gesture that answers *which edition am I running?*. The documentation is also published at
**<https://docs.lumnik.io>**.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
