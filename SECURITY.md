# Security policy

lumnik ingests from production systems. It does so **read-only** — the hub never writes
back into your ERP — and every row it stores is fenced by PostgreSQL Row-Level Security.
A flaw in either of those is worth telling us about privately.

## Reporting a vulnerability

**Please do not open a public issue for a security flaw.**

Two private channels, either is fine:

- **GitHub private vulnerability reporting** — the *Report a vulnerability* button under
  this repository's *Security* tab. It opens a private thread with the maintainers.
- **Email** — <contact@lumnik.fr>, subject starting with `SECURITY`.

Useful in a report: the version or commit, what you did, what happened, and what you
expected. A proof of concept helps; it is not required to be welcome.

We aim to acknowledge within **5 business days**. lumnik is maintained by a small team —
we would rather tell you honestly where a fix stands than promise a date we cannot hold.

## Scope

This repository is the **open edition**. Report anything here that affects it, including
the self-host stack under `deploy/selfhost/` and the `lm` client.

Flaws in **lumnik Pro** — the Ask extensions, Kafka ingestion, the external-identity loop —
belong on the same channels. Say which build you were running; the health endpoint tells
you (`GET /api/platform/health`, the `modules` map — see
[docs/editions.md](/docs/editions.md)).

## What we ask in return

Time to fix before you publish, and no testing against systems that are not yours. If a
report leads to a fix, we will credit you in the release notes unless you prefer otherwise.

## Supported versions

The latest release carries fixes. lumnik does not backport to older tags today; if that
becomes a problem for you, say so — it is the kind of thing a real user's need decides.
