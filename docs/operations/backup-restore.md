# Backup & Restore (self-host)

> Scripted, hot `pg_dump` for Postgres and a briefly-stopped copy for Keycloak — restore drops
> and recreates the database rather than patching it in place, and `.env` is not covered.

The self-host stack holds customer data in two stores:

| Store | Contents | Backup method |
|---|---|---|
| PostgreSQL (`selfhost_pg` volume) | ALL hub data: ingested tables, RAG chunks, saved views, identities, audit | hot `pg_dump -Fc` — no downtime |
| Keycloak dev-file H2 (`selfhost_kc` volume) | realm changes made at runtime: users created by hand, password hashes | cold copy with a ~15s Keycloak stop |

Why the brief Keycloak stop: the H2 file store is locked by the running server, and the
admin API cannot export password hashes — a "live" export would silently lose credentials.
New logins fail during the stop; already-issued tokens keep working.

## Take a backup

    deploy/selfhost/backup.sh              # keeps the newest 7
    deploy/selfhost/backup.sh --keep 30
    deploy/selfhost/backup.sh --no-keycloak

Output: `backups/<UTC-stamp>/{lumnik.dump, keycloak-data.tgz}` at the repo root
(`keycloak-data.tgz` is intentionally absent when you passed `--no-keycloak` —
its absence there is a choice, not a failed backup).
The Keycloak capture excludes `data/import` — it's the read-only realm-import bind
mount, reproducible from `infra/keycloak/` in git, and restoring over it would fail.

**Not covered — back these up separately, off-machine:**
- `.env` — contains `LUMNIK_SECRET_MASTER_KEY`; losing it makes secrets stored in the
  hub unrecoverable. Copy it somewhere safe once (it only changes when you rotate keys).
- Host-level config (reverse proxy, cron entries).

The dump grows with the [unpurged mirror](sizing.md#storage-the-sizing-question-this-page-does-not-answer) — `--keep N` is the only retention on backups themselves.

## Cron example

    # nightly at 03:10, keep 14 days
    10 3 * * *  cd /opt/lumnik && deploy/selfhost/backup.sh --keep 14 >> /var/log/lumnik-backup.log 2>&1

Ship `backups/` to another machine (rsync, restic, object storage) — a backup on the
same disk as the database is only half a backup.

## Restore

Same machine (roll back data):

    deploy/selfhost/restore.sh backups/<UTC-stamp>

**The restore drops and recreates the `lumnik` database**, then `pg_restore`s the dump
into the fresh copy — it is not an in-place patch. One consequence worth knowing before
you run it: anything created after the snapshot was taken is gone afterward, not just
reverted.

That is deliberate, not incidental. Restoring in place with `pg_restore --clean` was
tried first and rejected:

| | |
|---|---|
| **Why `--clean` fails** | `core.audit_event` is a declaratively partitioned table |
| **What it emits** | a per-partition `ALTER TABLE ONLY core.audit_event_default DROP CONSTRAINT audit_event_default_pkey` |
| **Why that's refused** | PostgreSQL rejects it — that primary key is inherited from the parent, not owned by the partition |
| **Result** | the restore aborts with that error before the hub restarts |

Drop-and-recreate sidesteps the inherited-PK limitation entirely. It also explains the
consequence above: an in-place `--clean` can only revert what's in the dump's TOC, so it
can never remove something that didn't exist yet — drop-and-recreate can, and does.

The Keycloak restore is an overwrite-merge: the snapshot's H2 store fully replaces the
live one, but stale top-level entries absent from the snapshot may survive — harmless
for this stack, where the store is exactly `h2/` + `transaction-logs/`. The write-back
streams a tar with uid/gid rewritten to the container's `keycloak` user (1000:0): a
plain `docker cp` preserves host ownership, which leaves the H2 store read-only for
Keycloak — it boots and serves reads, but every login/write fails with H2 error 90097
"The database is read only".

Fresh machine (disaster recovery):

1. Install docker, clone the repo, put your saved `.env` at the repo root.
2. `deploy/selfhost/up.sh` — init scripts create roles and an empty DB.
3. `deploy/selfhost/restore.sh backups/<UTC-stamp>`.

## Known scar — issuer change orphans JIT identities

JIT-provisioned users are keyed `(idp_issuer, idp_subject)` in the hub DB. If the
public origin (and therefore the Keycloak issuer URL) changed between backup and
restore, restored identities point at the OLD issuer and logins re-provision fresh
(orphaning history). Restore to the same public origin, or migrate
`identity.app_user.idp_issuer` deliberately.

## What this does NOT give you

- Point-in-time recovery (it's a nightly snapshot, not WAL archiving).
- Multi-node/k8s backup — the Helm path assumes your cluster's own volume snapshot
  tooling; only the PG dump portion translates directly.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `backup.sh` exits: `--keep must be a positive integer >= 1` | `--keep 0` or a non-numeric value | Pass a positive integer — `0` would prune the snapshot the run just created, so it's rejected before that can happen. |
| `backup.sh` exits: `pg_dump produced an empty file` | The dump captured nothing (DB unreachable, wrong container state) | Confirm the stack is up (`docker compose -f docker-compose.selfhost.yml ps`) and retry — the script fails loudly rather than shipping an empty backup. |
| Logins fail for ~15 seconds, then work again, on no apparent trigger | A `backup.sh` run (cron or manual) just briefly stopped Keycloak to copy its store | Expected — already-issued tokens keep working throughout; schedule the cron for a low-traffic window if this bothers you. |
| `restore.sh` appears to hang after you run it | It's an interactive confirmation (`Press Enter to continue, Ctrl-C to abort`) before it overwrites the database | Expected — it never overwrites unattended. Press Enter to proceed. |
| After a restore, Keycloak boots and serves reads but every login/write fails with H2 error `90097` "The database is read only" | The H2 store was restored by hand (e.g. `docker cp`) instead of via `restore.sh` — a plain copy preserves host ownership, leaving the store unwritable by the container's `keycloak` user | Always restore through `restore.sh` — it rewrites ownership to uid 1000 / gid 0 as it streams the tar in. |
| Users who logged in before a restore show up as brand-new accounts afterward | The public origin/issuer changed between backup and restore — see the Known scar above | Restore to the same public origin, or migrate `identity.app_user.idp_issuer` deliberately. |
