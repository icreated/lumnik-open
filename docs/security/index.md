# Security — a front door for the reader who decides

The five pages under this section were written for the integrator wiring lumnik into a
client's stack. This page is for the other reader who has to sign off on that decision:
a security officer working from an audit grid, who treats an unanswered question as a
"no". It does three things — the posture in short, a routing table from what an auditor
asks to where lumnik answers it, and every open gap gathered onto one page instead of
five.

## The posture, short

Full statement, in three sentences: [GDPR & personal data → the posture, in three
sentences](gdpr.md#the-posture-in-three-sentences). In outline:

- **Self-hosted, always.** lumnik runs on your infrastructure; there is no lumnik cloud
  for data to reach. Chat and embeddings run on a local model runtime (Ollama) by
  default — a cloud LLM provider is an explicit configuration choice you make, never a
  default. [GDPR & personal data](gdpr.md#the-posture-in-three-sentences)
- **A read-only mirror.** Ingestion reads your source systems and never writes back; the
  source stays the system of record. [GDPR & personal data](gdpr.md#the-posture-in-three-sentences)
- **Isolation enforced in the database itself**, not in application code you have to
  trust by inspection: every tenant's rows are separated by PostgreSQL Row-Level
  Security, with a métier-scope boundary layered on top for chat, search and entity
  reads. [RLS & database roles](rls.md)

## What an auditor asks

Many of the answers below change with **which edition is installed** — every one that touches
the chat surface, the RAG corpus (`rag.chunk`), JIT provisioning or SCIM. Do not read an
unmarked row as edition-independent; check the dependency, not the wording. The first row says
how to find out which build you have.

| An auditor asks… | Answered here | In short |
|---|---|---|
| **Which edition is this?** — what does the installed build actually contain? | This page | Ask the hub: `GET /api/platform/health` (as `lm_integrator`) returns the `modules` map — open edition answers `core` + `identity` only; a chat-carrying build adds `rag` + `llm`, and **both halves must be there**. [The full answer](#which-edition-the-full-answer). |
| **Tenant isolation** — what stops tenant A reading tenant B's rows? | [RLS & database roles → The three database roles](rls.md#the-three-database-roles) | PostgreSQL Row-Level Security on every tenanted table; the request-path role cannot bypass it. |
| **Identity federation** — can we plug in our own IdP? | [Bring your own IdP → The model](byo-idp.md#the-model) | Generically, yes — the hub validates any OIDC issuer, no Keycloak-specific code. **In practice: only Keycloak is exercised by the shipped rehearsal today** (`make k8s-up-oidc-external`); Okta and Entra ID work on paper but have not been run against a live hub — see [Okta / Entra ID notes](byo-idp.md#okta-entra-id-notes). Your IdP authenticates, lumnik JIT-provisions users into a pre-seeded tenant. **JIT provisioning is not in the open edition** — see the note below. |
| **Password guessing** — is brute force throttled at the login? | This page | **Yes, on by default**: both shipped realms set `bruteForceProtected: true` — Keycloak temporarily locks an account after repeated failures (Keycloak defaults: lockout after ~30 failures, waits growing to 15 minutes, never permanent). Tune or inspect it in the console: realm **lumnik → Realm settings → Security defenses → Brute force detection**. With [your own IdP](byo-idp.md), this layer is your IdP's policy, not lumnik's. |
| **Deprovisioning** — does a departure in our IdP actually cut access? | [SCIM deprovisioning → Why](scim.md#why) | SCIM push closes the loop: the request right after `active:false` is denied, even with a still-valid token. **SCIM is not in the open edition** — see the note below. |
| **Secrets at rest** — are stored credentials encrypted? | [RLS & database roles → Secrets at rest](rls.md#secrets-at-rest-the-master-key) | Yes — pgcrypto, AES-256, one master key. **No rotation tooling**: losing the key makes the secrets it protects unrecoverable. |
| **Data at rest** — is our ingested data encrypted in the database? | [RLS & database roles → Data at rest](rls.md#data-at-rest-what-is-encrypted-and-what-is-not) | **No.** Only secrets and connector credentials are ciphered; your mirrored rows and the RAG corpus are plaintext PostgreSQL columns. Volume/disk and backup encryption are your layer. |
| **Data in transit** — is traffic encrypted end-to-end? | [RLS & database roles → Data in transit](rls.md#data-in-transit-where-tls-stops) | The hub itself speaks plain HTTP; TLS terminates at whichever edge you put in front (the optional Caddy façade, your own reverse proxy, or the k8s Gateway). **Behind that edge — hub↔PostgreSQL, hub↔Ollama, façade↔hub — everything rides your internal network in the clear.** Securing that network is the operator's layer. |
| **Personal data & erasure** — can we honour access and erasure requests? | [GDPR & personal data → The rights, in practice](gdpr.md#the-rights-in-practice) | Access and rectification are real, if manual. **No one-click DSAR export**; erasure of ingested data is a documented rebuild procedure, not an automated one. |
| **Audit trail** — who did what, when? | [GDPR & personal data → Where personal data can live](gdpr.md#where-personal-data-can-live) | **Narrow, but real — with two gaps.** Recorded with the acting user: `User` writes (non-SCIM), `Secret` writes, view/workflow **applies** (with the manifest's SHA-256), and entity reads. Not recorded: SCIM-driven `User` writes — deprovisioning included. [The full answer](#audit-trail-the-full-answer). |
| **Inbound rate limits** — can one tenant or a runaway client exhaust our capacity? | [Inbound rate limits → What is limited](../operations/rate-limits.md#what-is-limited) | The LLM-backed endpoints are capped per tenant, per 60s window; CRUD, views and ingestion are unlimited. **Single-node only** — the counters live in the hub process. |
| **Backup & restore** — can we recover from data loss? | [Backup & restore (self-host)](../operations/backup-restore.md) | Yes, scripted, hot for PostgreSQL. **No point-in-time recovery** — nightly snapshots only. |
| **Vulnerability disclosure** — how do we report an issue, and is there a patch SLA? | `SECURITY.md`, at the root of every edition | Email **contact@lumnik.fr**, subject `[lumnik security]`, never a public issue — 72-hour acknowledgment, 14-day fix-or-mitigation answer. The same file ships in every edition. Dependency updates have their own watch posture: [the full answer](#dependency-watch-the-full-answer). |
| **Source-mirror retention** — how long are ingested rows kept? | This page | **No retention policy — kept indefinitely.** Unlike the event outbox (`lumnik.events.retention-days`, 30 days by default, purged nightly — [GDPR & personal data](gdpr.md#where-personal-data-can-live)), nothing schedules a purge of `connector.t_*` mirror tables or `rag.chunk`: rows persist until the connector is deleted (`DynamicTableRegistry.purgeForConnector`) or removed by hand. |
| **PII in application logs** — does the hub log personal data? | Not comprehensively documented | Partially answered: the login path deliberately keeps IdP subject/issuer out of WARN-level logs (`TenantContextFilter.java:208`, `ExternalIdentityCreator.java:49,64`), and chat/search log only counts and tenant/source, never message or row content (`RetrievalService.java:175`). **There is no repo-wide log-content policy or audit** — [Reading the logs](../recipes/logs.md#turning-up-the-volume) itself warns that DEBUG "may echo data you would rather not keep." Open question. |


### Which edition — the full answer {#which-edition-the-full-answer}

`GET /api/platform/health` lists the **manifest-bearing** modules on the classpath — the four
that declare a `META-INF/module-manifest.yaml`: `core`, `identity`, `rag` and `llm`. An
**open-edition** install answers `core` and `identity` only; a build carrying the chat
extensions also answers `rag` (the doors) and `llm` (the model provider).

**Both halves have to be there**, and the two layers say it differently:

- the métier PWA reads this same list and treats either half missing as *chat not
  installed* — no input is shown at all;
- called directly, a `rag`-without-`llm` build answers the door and then fails at the model
  call (`AskToSqlAgent` resolves its `ChatPort` only at the generation step), rather than
  answering `404` as an open-edition build does.

The hub logs the same list at boot (`Platform loaded N module(s): …`). What this row
decides: identity federation, deprovisioning, the audit trail's SCIM gap, the rate-limit
buckets, everything naming `rag.chunk`, the disclosure row, and the whole
[Edition boundary](#the-gaps-gathered-once) block in the gaps.

### Audit trail — the full answer {#audit-trail-the-full-answer}

Recorded in `core.audit_event`, each row with the acting user:

- **`User` writes** (non-SCIM): login, email, locked, default scope.
- **`Secret` writes**: create, rotate, delete — never the ciphertext or plaintext value.
- **View / workflow applies**: actor, name, scope, and the manifest's **SHA-256** — an
  auditor can prove which YAML was applied even though the store keeps only the latest.
- **Entity reads** via `GET /api/entities/{name}`.

The two named gaps: archiving a user IS recorded but only as an indistinguishable
`UPDATE`; **SCIM-driven `User` writes — deprovisioning included — are not recorded at
all** (a tenant-context mismatch between the SCIM auth filter and the audit listener).
Every other entity still no-ops, and there is no read surface — an auditor queries
`core.audit_event` directly. See [the gap below](#the-gaps-gathered-once).

### Dependency watch — the full answer {#dependency-watch-the-full-answer}

**Since 2026-08-22**, `.github/dependabot.yml` opens one grouped pull request a week
(Monday) for GitHub Actions, the Go modules under `lm/` (`otk-unit.yml` runs the Go suite
on the pull request) and the docs' `pip` pin (`docs-publish.yml` rebuilds with `--strict`
and runs the render check).

**How much the pull request verifies is uneven for the Actions half**, and the file says
so: only three workflows run on `pull_request`, so only `actions/checkout`,
`actions/setup-go` and `actions/setup-python` are exercised by the PR proposing their
bump. `setup-java` and `upload-artifact` can be reached by dispatching `otk-battery.yml`
on the branch; the four `docker/*` actions (`hub-image.yml`, push only) and `goreleaser`
(`lm-release.yml`, tags only) cannot be reached before a merge at all. They stay watched
anyway — a SHA pin that nothing bumps rots in silence — but watched is not verified.

**Maven — 160 direct dependencies — and the Dockerfile base images are deliberately left
unwatched**, each named with its reason inside that file: no pull-request job compiles or
tests Java (`nightly-verify.yml` runs on cron, after a merge), and `hub-image.yml` builds
on push to `main` rather than on `pull_request`. A bot opening pull requests that nothing
can check would only teach us to merge a green that checked nothing.
`scripts/check-dependabot-coverage.sh` fails the build if the file aims at a directory
holding no manifest, or if a manifest enters the tree neither watched nor excused.

**Vulnerabilities travel a separate lane**: Dependabot *alerts* and *automated security
updates* are repository settings rather than entries in that file, and both were switched
on 2026-08-22 — so Maven and the base images do get a pull request when an advisory names
one of their dependencies. One caveat survives, and it is the same CI gap: a Maven
security pull request carries no build evidence at merge time, because nothing on the
pull-request path compiles Java.

## The gaps, gathered once

Scattered today across five security pages, plus rate limits, backup/restore, and the
dependency cadence in the row above. None of
these are softened here — collect them for a risk register directly from this list.

**Secrets & keys**

- No master-key rotation tooling: there is no re-encryption path, so changing
  `LUMNIK_SECRET_MASTER_KEY` (or the connector/webhook key, `LUMNIK_CRYPTO_SECRET_KEY`)
  leaves every secret it protects undecryptable, and losing the key makes them
  unrecoverable. [RLS & database roles → Secrets at rest](rls.md#secrets-at-rest-the-master-key)
- The three database-role passwords (`lumnik_app`, `lumnik_admin`, `lumnik_readonly`)
  are generated per install on both deploy paths; self-host `up.sh` also aligns the
  live roles to `.env` on every run, which is the rotation gesture.
  [RLS & database roles → Credentials](rls.md#credentials)
- Your own ingested data (`connector.t_*`) and the RAG corpus (`rag.chunk`) are **not
  encrypted at the application layer** — plaintext PostgreSQL columns.
  [RLS & database roles → Data at rest](rls.md#data-at-rest-what-is-encrypted-and-what-is-not)

**Personal data & erasure**

- No DSAR export endpoint for hub data — access is real but manual, a query an
  integrator writes. [GDPR & personal data](gdpr.md#not-built-yet-recorded-not-hidden)
- No automated hard-delete or pseudonymisation of user accounts — an archived or
  soft-deleted user persists in the `identity` schema until an operator deletes the row.
  [GDPR & personal data](gdpr.md#not-built-yet-recorded-not-hidden)
- No deletion propagation from source systems to mirror tables — a deletion at the
  source does not remove the row from `connector.t_*`; erasure is the documented
  rebuild procedure. [GDPR & personal data](gdpr.md#not-built-yet-recorded-not-hidden)
- No API to erase a dead-letter payload — the read surface honours the soft-delete flag
  everywhere, but setting it is a database operation.
  [GDPR & personal data](gdpr.md#not-built-yet-recorded-not-hidden)
- Neither user-archive (`DELETE /api/tenants/{tid}/users/{id}`) nor SCIM's `DELETE` is a
  GDPR erasure — both leave the row in the database until an operator removes it.
  [SCIM deprovisioning → The `/scim/v2/Users` contract](scim.md#the-scimv2users-contract)

**Database trust boundary**

- `lumnik_admin` is `BYPASSRLS` by design — every code path using it must filter by
  tenant explicitly; a bug there is a cross-tenant bug RLS will not catch.
  [RLS & database roles → What RLS does NOT protect against](rls.md#what-rls-does-not-protect-against)
- Whoever holds the `lumnik_admin` credentials, or the database superuser, reads every
  tenant's rows regardless of RLS.
  [RLS & database roles → What RLS does NOT protect against](rls.md#what-rls-does-not-protect-against)
- **A wrong GUC.** RLS trusts `app.current_tenant`; the tenant resolution upstream (JWT
  claim → `TenantContextFilter`) is part of the trusted computing base — a bug there is
  invisible to RLS, which only enforces the policy, not how the GUC got set.
  [RLS & database roles → What RLS does NOT protect against](rls.md#what-rls-does-not-protect-against)
- **Nothing behind the TLS edge is encrypted.** The hub speaks plain HTTP internally;
  hub↔PostgreSQL, hub↔Ollama, and façade↔hub inside the compose/cluster network all ride
  the clear. TLS terminates only at the edge you put in front (the Caddy façade, your own
  reverse proxy, or the k8s Gateway) — securing the internal network itself is the
  operator's job, same as disk encryption above.
  [RLS & database roles → Data in transit](rls.md#data-in-transit-where-tls-stops)

**Capacity & backup**

- Inbound rate limiting is single-node, in-memory: scale the hub to N replicas and the
  effective limit becomes up to N× the configured rpm — a known, deliberate trade, not
  an oversight. [Inbound rate limits → Single-node caveat](../operations/rate-limits.md#single-node-caveat)
- No point-in-time recovery — the self-host backup is a nightly snapshot, not WAL
  archiving; the Helm path leans entirely on your cluster's own volume-snapshot
  tooling for anything beyond the PostgreSQL dump.
  [Backup & restore → What this does NOT give you](../operations/backup-restore.md#what-this-does-not-give-you)
- `.env` (holding `LUMNIK_SECRET_MASTER_KEY`) is **not** covered by the backup script —
  copy it somewhere safe separately, or a restored install cannot decrypt its own
  secrets. [Backup & restore → Take a backup](../operations/backup-restore.md#take-a-backup)

**Supply chain**

- **A Maven security fix is merged on the nightly's word.** Dependabot alerts and
  automated security updates were switched on 2026-08-22, so an advisory against a Java
  dependency or a container base image does open a pull request. But nothing on the
  pull-request path compiles or tests Java, so that pull request arrives with no build
  evidence: `nightly-verify.yml` is the first job to run it, on cron, after the merge.
  The three watched ecosystems — GitHub Actions, Go, the docs pin — do not have this gap;
  their bumps are verified by the pull request that proposes them. See the *Vulnerability
  disclosure* row above.

**Edition boundary**

- **BYO IdP for an actual external issuer is effectively unavailable on the open
  edition** — not merely un-automated. JIT provisioning and the whole SCIM surface are
  absent entirely: they live under `io.lumnik.hub.pro.*`, which the `-DopenEdition`
  build profile excludes wholesale (`lumnik-hub/pom.xml`, alongside the Kafka connector).
- **Why:** a real customer IdP normally cannot emit the optional `user_id` claim (it
  would have to know lumnik's own database primary key), so with no
  `JitUserProvisioner` on the classpath the open edition has no other way to resolve a
  first login.
- **Result:** `TenantContextFilter` fails closed — `401 {"error":"no external identity
  resolution in this edition (user_id claim required)"}`.
  Plan the account lifecycle, and the edition, accordingly.
  [Bring your own IdP](byo-idp.md#the-model) · [SCIM deprovisioning](scim.md)
- **Three of the four rate-limited endpoints don't exist on the open edition either.**
  `platform-chat`, `user-chat` and `platform-search` are declared on resources that live
  in `lumnik-rag` (`RagChatResource`, `UserChatResource`, `RagSearchResource`), which is a
  `runtime`-scope dependency only inside the `closed-extensions` profile
  (`lumnik-hub/pom.xml:369-379`) — on an open-edition build those endpoints, and their
  limits, are absent from the classpath entirely. Only `view-generate` (native to
  `lumnik-hub`) is enforced there. [Inbound rate limits → Edition
  boundary](../operations/rate-limits.md#what-is-limited)

**Identity & audit**

- **The audit trail covers `User` and `Secret` writes plus entity reads, and stops there — with
  two gaps of its own.** `@Auditable` is applied to exactly one entity; `Secret` is covered by
  three explicit call sites instead, because native SQL there bypasses Hibernate entirely:

    | Piece | Role | Status |
    |---|---|---|
    | `@Auditable` (`lumnik-common`) | annotation an entity opts in with | applied to `User` only (`login`, `email`, `locked`, `defaultScope` — `User.java:23`). `Secret` deliberately carries none — see below. |
    | `AuditEntityListener` | `@PostPersist`/`@PostUpdate` only (`AuditEntityListener.java:34,39`) — records `CREATE`/`UPDATE` with the acting user (`TenantContextFilter.currentUser()`); skips when the tenant guard reads `null` (`AuditEntityListener.java:48-49`) | fires on every Hibernate write to `User`, including an archive (`UserResource.deactivate()` → `archive()` → a plain field mutation, flushed as an `UPDATE` — `archivedAt` isn't a snapshotted field, so it's indistinguishable from any other edit); **does not fire on SCIM-driven `User` writes** — see below |
    | `SecretResource.recordAudit()` | three explicit calls — `create()`, `update()` (rotate), `delete()` — same shape as `EntityReadResource`'s read call: a separate admin-datasource connection, fail-soft, logged only (`SecretResource.java`) | fires on every `platform.secret` write; `Secret` (`name`, `description`, `rotatedAt`, never the ciphertext or the plaintext value — pinned by a dedicated test) carries no `@Auditable` because production never writes it through Hibernate — an annotation implying otherwise was itself a false claim, now removed |
    | `EntityReadResource.read()` | one explicit call after the query succeeds, recording `READ` with the entity name and the acting user (`EntityReadResource.java:82,95-111`) | fires on every `GET /api/entities/{name}`; a refused (403/404) read is never recorded |
    | `core.audit_outbox` / `core.audit_event` | drained every 2s by `AuditDispatcher` into the partitioned, append-only event table | receives rows from the three paths above — still empty for every other entity and operation. **Not uniformly transactional with the write it records — see below.** |

    **Non-atomicity — the `SecretResource` paths can record a write that never happened.**
    `AuditEntityListener` inserts the outbox row on the *same* JDBC connection as the business
    write, inside a savepoint (`AuditEntityListener.java:118-149`): the audit row and the
    business write commit or roll back together, so calling this leg of the trail "transactional"
    is accurate. `SecretResource.recordAudit()` does not share that property: it writes through
    the separate `admin` datasource, which runs autocommit
    (`quarkus.datasource."admin".jdbc.transactions=disabled`,
    `application.properties:43`) — deliberately, so a broken audit insert can never poison the
    secret write (see the method's javadoc; that separation is what a business-write-losing
    reentrancy bug earlier in this branch was fixed by introducing). The tradeoff is the mirror
    image: the audit insert commits immediately, before the surrounding `@Transactional` method
    returns and commits the secret write itself. If that later commit fails, `core.audit_event`
    is left permanently holding a CREATE/UPDATE/DELETE for a `Secret` write that never took
    effect — and the trail has no erasure path to correct it (see
    [GDPR → The rights, in practice](gdpr.md#the-rights-in-practice)). `EntityReadResource`'s read path
    uses the same separate-connection shape but carries none of this risk: `recordRead()` only
    runs after the read has already succeeded, so there is no later business commit for the audit
    row to disagree with. Separately, `SecretResource.update()` now checks the affected-row count
    before recording anything, so the narrower case — a concurrent delete landing between the
    existence check and the `UPDATE` — no longer produces a phantom audit row on its own; the
    cross-datasource commit-ordering gap above is the one that remains.

    **Gap — SCIM-driven `User` writes are unaudited.** `ScimAuthFilter` runs before
    `TenantContextFilter` and sets `CurrentTenantHolder` (what RLS reads) but not
    `TenantContextFilter.CURRENT_TENANT` (what `AuditEntityListener`'s tenant guard reads) —
    `TenantContextFilter` also unconditionally clears that thread-local at the top of its own
    `filter()`, before it even checks whether the request was pre-authenticated, so setting it
    from `ScimAuthFilter` (which runs first, at `AUTHENTICATION` priority) would be wiped a
    moment later anyway. Closing this cleanly means reordering a security-sensitive filter that
    a dozen other call sites read (`HookExecutor`, `ConnectorEngine`, `JdbcSourceResource`,
    `BffResource`, `ListResource`, `DlqAdminResource`, `EventOutboxService`, `ScimTokenResource`,
    among others) — out of scope for a branch about wiring the existing trail. Every SCIM
    create, update and deactivate — including deprovisioning, the single most audit-relevant
    identity event there is — silently skips the listener today.

    Verified: of every `@Auditable` use in the codebase, exactly one sits on a production JPA
    `@Entity` — `User.java:23`. (`Secret` no longer carries it; the remaining mentions are the
    annotation's own declaration, javadoc prose, and `@Auditable` fixtures inside this branch's
    own tests, none of which are production entities.) An auditor asking "who changed this, and
    when" gets an answer for non-SCIM user-account changes, secrets and entity reads — SCIM-driven
    identity changes and every other entity are still a table with no rows, and there is still no
    read surface over the trail: querying it is a direct SQL query against `core.audit_event`, not
    an API.

- **The permission catalog is declarative metadata, not yet enforced.**
  `@RequiresPermission` annotations still reference realm roles as magic strings;
  the per-permission catalog in `roles.yaml` documents the mapping but does not (yet)
  drive the interceptor. [Roles & permissions →
  Conceptual model](role-management.md#conceptual-model)
- **Access denials are logged, not audited.** `PermissionInterceptor` writes every
  denial to the application log at WARN level — SIEM can subscribe to that, but there
  is no structured audit-table record of who was denied what, when.
  [Roles & permissions → Audit logging](role-management.md#audit-logging)
- **The role catalog is incomplete against what is actually enforced.** `lm_integrator`
  gates `/api/platform/*` in its entirety (`PlatformApiAuthFilter`) plus the view and
  workflow apply endpoints — but it is declared in neither shipped `META-INF/roles.yaml`
  (`lumnik-hub`, `lumnik-rag`), so it cannot appear in the roles catalog an operator
  reviews. An RBAC inventory taken from that catalog will be missing a role that grants
  real access. [Roles & permissions](role-management.md#release-security-checklist)
- **Changing the public origin orphans JIT identities.** Users are keyed
  `(idp_issuer, idp_subject)`; a hostname or scheme change between backup and restore
  (or any issuer-URL change) re-provisions every user as a new identity and can collide
  on the login unique constraint.
  [Backup & restore → Known scar](../operations/backup-restore.md#known-scar-issuer-change-orphans-jit-identities)

## See also

- [RLS & database roles](rls.md) · [Roles & permissions](role-management.md) ·
  [Bring your own IdP](byo-idp.md) · [SCIM deprovisioning](scim.md) ·
  [GDPR & personal data](gdpr.md)
