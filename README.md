# lumnik — defrost the enterprise

[![ci](https://github.com/icreated/lumnik-open/actions/workflows/ci.yml/badge.svg)](https://github.com/icreated/lumnik-open/actions/workflows/ci.yml)

Your ERP knows everything and tells you nothing. **lumnik** ingests the data trapped inside
legacy systems — Siebel, AS/400, homegrown SQL, CSV drops, SOAP and REST and GraphQL
backends — and un-freezes it into something people can actually use: fused entities, live
per-métier views, and honest APIs over a multi-tenant PostgreSQL hub.

**The name:** `lum` (light) + the Slavic agent suffix `-nik` — *the one who brings light into
the frozen ERP*. The CLI binary is `lm` (the lumen symbol).

Read-only entry: ingest first, risk nothing. lumnik never writes into your ERP.

## Where it stands

**beta.** lumnik no longer grows surfaces — the recent work is fixes, not new subsystems. What is
not here is not here on purpose, and every page says so in its own words. What we do not know yet:
nobody outside the team that wrote it has run lumnik in production.

[Where lumnik stands](/docs/where-lumnik-stands.md) gathers the whole boundary on one page — what
is deliberately absent, what is started and not finished, and the two things that have to be true
before that word goes away.

## What's in the box

- **Connectors, declarative-first** — JDBC, CSV (local + remote), REST (11 pagination
  presets + body-link), GraphQL sources, SOAP (Siebel QueryPage battle-tested), file drops.
  YAML manifests with a Kotlin escape hatch; per-row DLQ isolation; validate-then-apply.
- **The hub** — multi-tenant PostgreSQL 16 with Row-Level Security on every row, dynamic
  tables per source, fused `kind:Entity` views over many sources, a transactional outbox
  with webhook delivery, GDPR export/erase.
- **`lm`** — a k9s-style terminal UI and CLI driving connectors, runs, views and inbox,
  with an OIDC device-flow login.
- **The métier PWA** at `/app/` — Preact without a bundler, phone=cards / tablet=table /
  display=rail; server-side `kind:View` manifests scoped per métier.
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
industrialisation layers on top: **Ask** (natural-language chat over your data, per-métier
scoped, text-to-SQL behind five honesty guards), **Kafka / Event Hubs inbound streams**, and
the external-identity loop (external IdP, JIT provisioning, SCIM deprovisioning). A
Kubernetes delivery — Helm, sealed secrets, managed PostgreSQL — comes with it.

Three commercial tiers ride on two builds, and what a running hub can prove is which *build*
it is. [docs/editions.md](/docs/editions.md) draws the line feature by feature and shows the
gesture that answers *which edition am I running?*. The documentation is also published at
**<https://docs.lumnik.io>**.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
