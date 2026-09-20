# Security

> Self-hosted, read-only towards your sources, tenant isolation enforced by PostgreSQL itself.
> This page is the front door for the person who signs off: what holds, how to verify it in
> five minutes, and what is not built yet.

<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Self-hosted</strong>

The hub, the database, the model runtime: all on your infrastructure. There is no lumnik cloud.
</div>
<div class="lm-meta-item" markdown>
<strong>Read-only mirror</strong>

Ingestion reads your ERP and never writes back. The source stays the system of record.
</div>
<div class="lm-meta-item" markdown>
<strong>Isolated by RLS</strong>

Row-Level Security on every tenanted table. The request-path role cannot bypass it.
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Read-only role under LLM SQL</strong>

Generated queries run as `lumnik_readonly`: `SELECT` only, one statement, `LIMIT 500`.
</div>
</div>

## The trust boundaries

```mermaid
flowchart LR
  idp["your IdP<br/>OIDC token"] -.-> edge["TLS edge<br/>Caddy façade · your proxy · k8s Gateway"]
  edge -->|"HTTP"| jwt
  subgraph hub["hub · plain HTTP behind the edge"]
    direction TB
    jwt["token checked<br/>aud · tenant_id · roles · scopes"]
    app["lumnik_app<br/>RLS"]
    ro["lumnik_readonly<br/>RLS · SELECT only"]
    adm["lumnik_admin<br/>BYPASSRLS"]
    jwt -->|"API · views · ingestion"| app
    jwt -->|"LLM-generated SQL"| ro
  end
  app --> pg[("PostgreSQL<br/>tenant policy on<br/>every tenanted table")]
  ro --> pg
  adm -->|"migrations · DDL · purge"| pg
  hub -.- llm["Ollama<br/>local by default"]
```

- **One token, four claims.** `aud` must be `lumnik-backend`, `tenant_id` picks the tenant,
  the `groups` array carries `lm_*` roles and `scope:<tag>` grants, and an optional `user_id`
  names the hub row. A malformed claim is refused, never repaired. [Bring your own IdP](byo-idp.md)
- **Three database roles, one owner.** `lumnik_admin` owns every table and is the only role
  that bypasses RLS. `lumnik_app` owns nothing, so PostgreSQL's owner-bypass rule never applies
  to a request. [RLS & database roles](rls.md)
- **TLS stops at the edge.** Behind it, hub↔PostgreSQL, hub↔Ollama and façade↔hub ride your
  internal network in the clear. [Where TLS stops](rls.md#data-in-transit-where-tls-stops)
- **Scopes sit on top of tenants, as a guard.** A `scope:<tag>` role narrows chat, search, views
  and entity reads to one métier; the door refuses with `403 {"error":"scope not granted"}`.
  On generated SQL the boundary is the leak guard (tables outside the scope's card are refused,
  bare names cannot resolve), not a database policy: **PostgreSQL enforces the tenant, the
  guards enforce the métier.** [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in)

## Verify it yourself, in five minutes

Every claim above has a command. Run them on the self-host stack.

**Tenant isolation is the database's decision, not the application's.** Open a session as the
request-path role. No tenant set means zero rows, never an error and never every row:

```
$ docker exec -it lumnik-postgres-1 psql -U lumnik -d lumnik
lumnik=# SET ROLE lumnik_app;
lumnik=> SELECT count(*) FROM connector.t_customers;
 count
-------
     0
lumnik=> BEGIN;
lumnik=*> SET LOCAL app.current_tenant = '1';
lumnik=*> SELECT count(*) FROM connector.t_customers;
 count
-------
    12
lumnik=*> COMMIT;
lumnik=> BEGIN;
lumnik=*> SET LOCAL app.current_tenant = '2';
lumnik=*> SELECT count(*) FROM connector.t_customers;
 count
-------
     0
```

**The role under LLM-generated SQL cannot write, whatever the model produces:**

```
lumnik=> SET ROLE lumnik_readonly;
lumnik=> INSERT INTO connector.t_customers DEFAULT VALUES;
ERROR:  permission denied for table t_customers
lumnik=> BEGIN;
lumnik=*> SET LOCAL app.current_tenant = '2';
lumnik=*> SELECT count(*) FROM connector.t_customers;
 count
-------
     0
```

The same role is under RLS too: the tenant it reads is the one the request set, never another.

**A stored secret is ciphertext in the database** (pgcrypto, AES-256, under a master key):

```
lumnik=# SELECT name, left(encode(value_encrypted, 'hex'), 32) || '…' AS value_encrypted
         FROM platform.secret ORDER BY name LIMIT 2;
     name      |          value_encrypted
---------------+-----------------------------------
 ACME_API_KEY  | c30d040903025dc75fbe5b2c0cff7dd2…
 ACME_BASIC_PW | c30d0409030252f6ef632b4664286ad2…
```

**No token, no answer:**

```
$ curl -si http://localhost:8080/api/platform/health | head -1
HTTP/1.1 401 Unauthorized
```

**A user token does not open the provisioning surface** — SCIM has its own bearer tokens:

```
$ curl -s http://localhost:8080/scim/v2/Users -H "Authorization: Bearer $TOKEN"
{"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],"detail":"invalid SCIM token","status":"401"}
```

## Which build is this? {#which-edition-the-full-answer}

```
$ TOKEN=$(awk '/^ *access:/ {print $2; exit}' ~/.lm/config.yaml)
$ curl -s http://localhost:8080/api/platform/health -H "Authorization: Bearer $TOKEN" | jq '.modules | keys'
[
  "core",
  "identity",
  "llm",
  "rag"
]
```

- A lumnik Open build answers `core` and `identity` only. lumnik Pro adds `rag`
  (the doors) and `llm` (the model provider), and **both must be there**.
- The métier PWA reads this list and shows no chat input if either half is missing. Called
  directly, a `rag`-without-`llm` build answers the door and fails at the model call.
- The boot log prints the same list. **lumnik Pro** is the image the deploy scripts pull;
  **lumnik Open** is built from the public repository. Which one you run decides the rows
  marked *edition* below. [Editions](../editions.md#feature-by-feature)

## What an auditor asks

| Question | Answer, in short | Page |
|---|---|---|
| What stops tenant A reading tenant B? | PostgreSQL Row-Level Security on every tenanted table; the request role cannot bypass it. | [RLS](rls.md) |
| Can we plug in our own IdP? | Any OIDC issuer, generically. Only Keycloak is exercised by the shipped rehearsal. *Edition:* first login needs JIT provisioning, absent from lumnik Open. | [BYO IdP](byo-idp.md) |
| Is password guessing throttled? | Yes, the shipped realm has brute-force detection on. With your own IdP it is your IdP's policy. | [Users & scopes](identity-admin.md) |
| Does a departure cut access, and how fast? | Archiving the hub row refuses **the very next request**, valid token or not: SCIM `active: false` (*edition:* Pro) or the users API (both editions). Disabling the account in the IdP alone stops new logins; a live access token stays valid until it expires (1 hour on the bundled realm). With `LUMNIK_AUTH_VERIFY_WITH_IDP=true` the hub asks the IdP's UserInfo endpoint on every request and fails authentication when the IdP refuses. | [SCIM](scim.md) · [Offboarding](offboarding.md#the-clock) |
| Are stored credentials encrypted? | Yes, pgcrypto AES-256 under two keys held in `.env`: one for tenant secrets, one for connector and webhook credentials. No key rotation tooling. | [Secrets at rest](rls.md#secrets-at-rest-the-master-key) |
| Is our ingested data encrypted at rest? | **No.** Mirror tables and the RAG corpus are plaintext columns. Disk and backup encryption are your layer. | [Data at rest](rls.md#data-at-rest-what-is-encrypted-and-what-is-not) |
| Is traffic encrypted end to end? | TLS at the edge only. Everything behind it is plain HTTP on your internal network. | [Data in transit](rls.md#data-in-transit-where-tls-stops) |
| Can we honour access and erasure requests? | Access is real but manual. Erasure of ingested data is a rebuild: delete the connector, re-ingest. | [GDPR](gdpr.md#the-rights-in-practice) |
| Who did what, when? | User and secret writes, view and workflow applies (with the manifest's SHA-256), entity reads. Not SCIM writes, not questions asked, not the SQL run. | [The audit trail](#the-audit-trail) |
| Can one tenant exhaust our capacity? | The four LLM-backed endpoints are capped per tenant per minute. Single node only. | [Rate limits](../operations/rate-limits.md) |
| Can we recover from data loss? | Scripted, hot PostgreSQL dump at the cron interval you choose. No point-in-time recovery. | [Backup & restore](../operations/backup-restore.md) |
| How long are ingested rows kept? | Indefinitely. Nothing purges mirror tables or the corpus until the connector is deleted. | [GDPR](gdpr.md#where-personal-data-can-live) |
| Does the hub log personal data? | At the default level the login path logs no subject or issuer, and chat logs counts, never a question or a row. There is no repo-wide log-content policy, and `DEBUG` may echo data. | [Reading the logs](../recipes/logs.md) |
| Can the model write, or read another tenant? | No. Generated SQL runs as `lumnik_readonly` under RLS: one `SELECT`, `LIMIT 500`. The hub stores no conversation history. Nothing leaves your network unless you configure a cloud provider. | [Ask honesty](../transparency/ask-honesty.md) |
| How do we report a vulnerability? | Email `contact@lumnik.fr`, subject `[lumnik security]`, never a public issue. Acknowledgment within 72 hours, fix or mitigation within 14 days. Supported: the latest `v1.x`. | `SECURITY.md`, at the root of every edition |

## The audit trail

Recorded in `core.audit_event`, each row with the acting user, tenant-isolated like every other
table:

- **User writes**, outside SCIM: login, email, locked, default scope.
- **Secret writes**: create, rotate, delete. Never the value, ciphered or clear.
- **View and workflow applies**: actor, name, scope and the manifest's **SHA-256**. An auditor
  can prove which YAML was applied even though the store keeps only the latest.
- **Entity reads** through the entity API.

```
lumnik=# SELECT entity_type, operation, count(*) FROM core.audit_event GROUP BY 1, 2 ORDER BY 1, 2;
   entity_type    | operation | count
------------------+-----------+-------
 Entity           | READ      |    17
 SavedView        | APPLY     |     3
 Secret           | CREATE    |    23
 Secret           | DELETE    |     7
 Secret           | UPDATE    |     2
 User             | CREATE    |     1
 User             | UPDATE    |     2
 WorkflowManifest | APPLY     |     4
```

- **Not recorded**: questions asked, the SQL that ran, searches, connector runs (those have
  their own [run ledger](../connectors/index.md)), and every other entity.
- **Retention is time-bounded**: a nightly purge deletes rows older than
  `LUMNIK_AUDIT_RETENTION_DAYS` (default 365; `0` keeps forever). There is no per-row erasure.
- **No read surface.** An auditor queries the table directly, as above, with a role that can
  see it: `lumnik_admin`, or `lumnik_app` inside the tenant.
- **Append-only at the database.** The request-path role can only `INSERT` and `SELECT` the
  table; `UPDATE`, `DELETE` and `TRUNCATE` are revoked from it. Only `lumnik_admin`, which runs
  the retention purge, can delete.
- **Two gaps.** Archiving a user is recorded as an ordinary `UPDATE`, indistinguishable from
  any other edit. **SCIM-driven user writes, deprovisioning included, are not recorded at all.**
- **Refusals are logged, not audited.** Every refusal family leaves one `WARN` line; point
  your SIEM at them. [Reading the logs](../recipes/logs.md)
    - role gate: `Access denied: required=[…], actual=[…], method=…`
    - scope gate: `Access denied: scope not granted, granted=[…]`
    - SCIM: `SCIM auth refused: GET /scim/v2/… reason=missing` or `reason=unknown-or-revoked`,
      never the token

## Not built yet

Collect these for your risk register. None are softened.

**Keys & data at rest**

- No master-key rotation. Changing `LUMNIK_SECRET_MASTER_KEY` or `LUMNIK_CRYPTO_SECRET_KEY`
  makes every secret it protects undecryptable; losing it makes them unrecoverable.
  Back both up separately: the backup script does not cover `.env`.
- The keys live in `.env` on the host, in clear. Whoever holds a copy of `.env` **and** a
  database dump decrypts every stored secret, for as long as the key is not changed.
- Ingested data and the RAG corpus are plaintext in PostgreSQL. So are backups.

**Personal data**

- No DSAR export endpoint. No automated hard-delete of user accounts. No deletion propagation
  from sources to mirror tables. No API to erase a dead-letter payload. [GDPR](gdpr.md#not-built-yet-recorded-not-hidden)

**Database trust boundary**

- Whoever holds `lumnik_admin` or the superuser reads every tenant. Code using the admin
  datasource must filter by tenant itself; RLS will not catch a bug there.
- RLS trusts the tenant the request filter resolved from the JWT. That filter is part of the
  trusted computing base.
- Saved views and entity manifests are hub-wide definitions, not tenant rows: they are bound
  by scope grants. The data they render still goes through RLS.
- The métier boundary inside a tenant is a guard on generated SQL, not a database policy
  (above).

**Capacity & recovery**

- Rate limiting is in-memory: N replicas means up to N× the configured limit. The SCIM
  surface is not rate-limited; its bearer is 256 bits of randomness.
- A dump at your cron interval, no point-in-time recovery: the RPO is that interval.
- Changing the hub's public origin re-keys every JIT identity: pick it once, early.
  [Backup & restore](../operations/backup-restore.md#known-scar-issuer-change-orphans-jit-identities)

**Identity**

- The `lm_integrator` role gates the whole platform API but is absent from the roles catalog
  the admin console shows. An RBAC inventory taken from that catalog misses it.
  [Roles & permissions](role-management.md)
- The permission catalog is declarative: endpoints are still gated by role name.
- SCIM writes skip the audit trail (above).
- Disabling an account in the IdP does not revoke a live access token: archive the hub row
  ([Offboarding](offboarding.md#the-clock)). `LUMNIK_AUTH_VERIFY_WITH_IDP=true` makes the hub
  ask the IdP on every request and refuse a still-valid token once the account is disabled —
  rehearsed end to end in the `oidc-external` kind smoke test.

**Edition boundary**

- lumnik Open: no JIT provisioning, no SCIM. A token without a `user_id` claim is refused
  with `401 {"error":"no external identity resolution in this edition (user_id claim required)"}`,
  so BYO IdP against a real customer IdP needs a build that carries JIT.
- lumnik Open: only the `view-generate` rate-limit bucket exists; the three chat buckets
  live with the chat endpoints, which are absent.

**Supply chain**

- Dependabot watches GitHub Actions, the Go modules and the docs pin, with a weekly grouped
  pull request; alerts and security updates are on for Maven and the container base images.
  A Maven security fix arrives with no build evidence on the pull request: the first Java
  build is the nightly, after the merge.
- The hub image is published without a signature or an SBOM attestation.

**The model**

- No filter sits between ingested text and the model: a source row that carries instructions
  can steer an answer. What it cannot do is widen the SQL's rights: whatever the prompt, the
  query runs as `lumnik_readonly`, under RLS, behind the guards.
- The hub keeps no conversation history; a cloud provider, if you configure one, keeps its own.

## See also

- [RLS & database roles](rls.md) · [Roles & permissions](role-management.md) ·
  [Tenants, users & scopes](identity-admin.md)
- [Bring your own IdP](byo-idp.md) · [SCIM deprovisioning](scim.md) ·
  [Offboarding the integrator](offboarding.md) · [GDPR & personal data](gdpr.md)
- [Ask honesty](../transparency/ask-honesty.md): the guards between a question and a query.
