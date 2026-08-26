# GDPR & personal data

> Where personal data can live in a lumnik installation, what erasure actually does today, and
> — named rather than hidden — the handful of things GDPR compliance would still need that are
> not built yet.

lumnik is self-hosted software, not a service: it runs on your infrastructure, and no data
ever reaches a lumnik cloud — there isn't one. You remain the controller (or processor) of
the data it touches; this page is not legal advice. It is an engineering map: where
personal data can live inside a lumnik installation, which controls exist — each claim
backed by shipped code — and what is **not**
built yet.

## The posture, in three sentences

- **Nothing leaves your infrastructure by default.** Chat and embeddings run on a local
  model runtime (Ollama); the questions your users ask and the data chunks that ground the
  answers travel to `localhost`, not to an API on the internet. Switching to a cloud LLM
  provider is possible, but it is an explicit configuration *you* make — and from that
  moment those chunks and questions are sent to that provider.
- **lumnik is a read-only mirror.** Ingestion reads your source systems and never writes
  back; the source remains the system of record. Ask-to-SQL executes under a dedicated
  read-only database role, behind [five honesty guards](../transparency/ask-honesty.md) that include a
  leak guard refusing any query outside the user's granted scope.
- **Isolation is enforced in the database itself.** Every tenant's rows are separated by
  PostgreSQL row-level security ([RLS & database roles](rls.md)), and chat, search and
  entity reads are additionally scope-bound per métier.

## Where personal data can live

| Store | What lands there | Lifecycle |
|---|---|---|
| `identity` schema | lumnik user accounts: login, display name, email | Archive / soft-delete lifecycle — see below |
| `connector.t_*` mirror tables | Whatever your sources contain (customers, orders…) | Rows are upserted by content hash; **a deletion at the source does not propagate** — see *Erasure* |
| `rag.chunk` corpus | Text chunks + embeddings derived from ingested rows | **Append-and-dedup at ingestion** (`row_hash`): a changed source row adds a new chunk and the old one stays; a row deleted at the source keeps its chunk. Erasure rides the connector: deleting it cascades to every chunk it produced (`fk_chunk_connector`, pinned by an integration test under enforced RLS). Only the *platform* corpus (`source: system`) is replaced wholesale, at boot and on `reindex` |
| Dead letters | The raw payload of rows that failed mapping (kept on purpose, for diagnosis) | Every read surface excludes soft-deleted rows; nothing sets that flag via API today — see *Not built* |
| Event outbox | `entity` name, a **key hash**, the operation — **no row content** | Purged daily once older than the retention window (`lumnik.events.retention-days`, default 30 days) and consumed by every registered consumer |
| Audit trail (`core.audit_event`) | Narrow, but real: `User` writes (non-SCIM), `Secret` writes (metadata only — never a value), view/workflow **applies** (with the manifest's SHA-256), and entity `READ`s — each row with the acting user. [How it is written, and the two gaps](#how-the-audit-trail-is-written) | Populated for those paths; empty for everything else; append-only, **bounded by time**: the nightly purge deletes rows older than `LUMNIK_AUDIT_RETENTION_DAYS` (default 365 days; `0` = keep forever, an explicit choice) — no per-row erasure, see *Erasure* |
| Webhook deliveries (egress) | The values of row columns a `kind: Process` message template references — sent **in clear** to the webhook target the tenant configured (n8n, Make, your endpoint) | Leaves lumnik entirely; retention is the receiving system's. The delivery *history* row lumnik keeps stores status and attempts |
| Keycloak | Its own user store (credentials, sessions) | Your realm, your policies |
| Backups | Whatever you back up | Yours entirely |

### How the audit trail is written {#how-the-audit-trail-is-written}

Two write paths plus reads, narrowly:

- **`User` writes** (`login`, `email`, `locked`, `defaultScope`) via `@Auditable` +
  `AuditEntityListener` (`@PostPersist`/`@PostUpdate`).
- **`Secret` writes** (`name`, `description`, `rotatedAt` — never the ciphertext or the
  plaintext value) via three explicit calls in `SecretResource` (create, rotate, delete):
  `Secret` carries no `@Auditable` because its writes go through native SQL to keep
  `pgp_sym_encrypt` in PostgreSQL, which bypasses Hibernate entirely — the listener could
  never have fired on it.
- **View / workflow applies** (`SavedView`, `WorkflowManifest`): actor, name, scope, and
  the manifest's SHA-256 — recorded explicitly for the same reason: their stores write raw
  JDBC.
- **`READ` events** from `GET /api/entities/{name}`, recorded explicitly in
  `EntityReadResource.read()`.

Each row carries the acting user (`TenantContextFilter.currentUser()`). The two gaps:

- **Gap 1** — archiving a user (`DELETE /api/tenants/{tid}/users/{id}`) *is* recorded, but
  only as an ordinary `UPDATE`: `archivedAt` isn't a snapshotted field, so it looks like
  any other edit.
- **Gap 2** — SCIM-driven `User` writes (create, update, deprovision) are not recorded at
  all — see *Not built* below.


## The rights, in practice

**Access & portability.** Entity reads (`GET /api/entities/{name}`) and read-only SQL give
structured access to everything lumnik holds about a person. There is **no one-click DSAR
export** — assembling an answer is a query your integrator writes, not a button.

**Rectification.** Fix the data in the source system; the mirror follows on the next run.
The RAG corpus gains a chunk for the corrected row on that same run — but ingestion is
append-and-dedup, so **the chunk carrying the old value stays** until the connector is
deleted and re-ingested (the erasure gesture below). When a rectification must also purge
the wrong value from the corpus, use that gesture. lumnik deliberately offers no way to
edit ingested rows — a mirror that can be edited stops being evidence of what the source
said.

**Erasure** splits into two very different cases:

- **A person in your data** (a customer row in a mirror table): erase them in the source
  system first — it is the system of record. Then, because deletions do not propagate,
  rebuild the mirror: **delete the connector and re-ingest**. `lm connector delete`
  purges its dynamic tables and registry rows, and the same delete **cascades to every
  RAG chunk that connector produced** (`fk_chunk_connector ON DELETE CASCADE` — proven
  by an integration test under enforced RLS), so the erased row's text and
  embeddings die with it. Removing rows from `connector.t_*` by hand is **not enough**:
  ingestion appends chunks with hash-dedup and nothing re-indexes ingested data
  (`reindex` rebuilds only the platform corpus), so a hand-deleted row's chunk would
  survive until the connector itself is deleted.
- **A lumnik user account**: `DELETE /api/tenants/{tid}/users/{id}` **archives** the user
  (`archived_at`); SCIM `DELETE` **soft-deletes** it (`deleted_at`) — two distinct states,
  see [SCIM deprovisioning](scim.md) for why they differ. Either way the next login
  attempt is refused with `403` and the account drops out of listings; a soft-deleted row
  is additionally treated as absent by payload-bearing surfaces like the dead-letter
  queue. What neither state is: a deletion — the row remains in the database until an
  operator removes it, see below. **Neither state *removes* anything from the audit trail
  either.** A non-SCIM archive *adds* to it: `core.audit_event` snapshots the `login`/`email`
  at the moment they were last recorded (see the table above) into an append-only log with
  no *per-row* erasure tooling — archiving, soft-deleting, or hand-deleting the `identity`
  row does not remove or redact the copies already written to `audit_event`. What bounds
  those copies is **time**: the nightly retention purge deletes audit rows older than
  `LUMNIK_AUDIT_RETENTION_DAYS` (default 365 days; `0` keeps them forever, as an explicit
  operator choice).

## Not built yet, recorded not hidden

The asymmetry-of-harm rule this product applies to its chat — abstain rather than answer
confidently wrong — applies to this page too. The following do **not** exist today:

- **No automated hard-delete or pseudonymisation of user accounts.** An archived or
  soft-deleted user persists in the `identity` schema until an operator deletes the row
  in the database.
  (An earlier revision of the SCIM page described a nightly purge and a
  `gdpr/erase` endpoint; neither was ever built — that page has been corrected.)
- **No DSAR export endpoint** for hub data. Access is real but manual (queries over the
  entity and SQL surfaces).
- **No deletion propagation** from source systems to mirror tables. Erasure of ingested
  data is the rebuild procedure described above.
- **No API to erase a dead-letter payload.** The read seam already honours the
  soft-delete flag everywhere; setting it is a database operation.
- **The audit trail covers `User` and `Secret` writes plus entity reads, not the whole
  system — and has two gaps of its own.** `core.audit_event` records `User` writes via
  `@Auditable` + `AuditEntityListener`, `Secret` writes via three explicit calls in
  `SecretResource` (native SQL there bypasses Hibernate, so `Secret` carries no `@Auditable`),
  and `GET /api/entities/{name}` reads — see the table above. No other entity is covered.
  **Archiving a user IS recorded**, but only as an ordinary `UPDATE` (`archivedAt` is not a
  snapshotted field), so it is indistinguishable from any other edit. **SCIM-driven `User`
  writes are not recorded at all**: `ScimAuthFilter` sets the tenant RLS reads
  (`CurrentTenantHolder`) but not the separate thread-local the listener's tenant guard checks
  (`TenantContextFilter.CURRENT_TENANT`), so every SCIM create/update/deactivate — including
  deprovisioning, the single most audit-relevant identity event there is — silently skips the
  listener. This is a known, left-as-is gap: closing it means reordering a security-sensitive
  auth filter that a dozen other call sites depend on, for a branch about wiring the trail,
  not about touching that filter. There is also still no read surface over the trail —
  an auditor queries `core.audit_event` directly, the same as before, and once a row lands
  there it cannot be erased or redacted (see *Erasure* above). Accountability for anything
  outside these paths is, today, whatever your database and reverse proxy log.

If one of these gaps blocks a real obligation you have, say so — they are unbuilt because
no deployment has needed them yet, not because they are hard.
