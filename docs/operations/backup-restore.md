# Backup & Restore (self-host)

> A hot `pg_dump` for PostgreSQL and a briefly-stopped copy for Keycloak. A restore drops and
> recreates the database. `.env` is not covered.

The self-host stack holds customer data in two stores:

| Store | Contents | Backup method |
|---|---|---|
| PostgreSQL (`selfhost_pg` volume) | ALL hub data: ingested tables, RAG chunks, saved views, identities, audit | hot `pg_dump -Fc` — no downtime |
| Keycloak dev-file H2 (`selfhost_kc` volume) | realm changes made at runtime: users created by hand, password hashes | cold copy — Keycloak stops ~15 s |

During the Keycloak stop, new logins fail; issued tokens keep working.

## Take a backup

```bash
deploy/selfhost/backup.sh              # keeps the newest 7
deploy/selfhost/backup.sh --keep 30
deploy/selfhost/backup.sh --no-keycloak
```

Each step prints a `==>` line; the last ones are `==> done: backups/<UTC-stamp>` and a reminder
to back up `.env`. The snapshot lands at the repo root:

| File | Present when |
|---|---|
| `backups/<UTC-stamp>/lumnik.dump` | always |
| `backups/<UTC-stamp>/keycloak-data.tgz` | unless you passed `--no-keycloak` |

**Not covered — back these up separately, off-machine:**

- `.env` — it holds `LUMNIK_SECRET_MASTER_KEY`; without it, secrets stored in the hub are
  unrecoverable. Copy it once; it changes only when you rotate keys.
- Host-level config (reverse proxy, cron entries).

The dump grows with the [unpurged mirror](sizing.md#storage-the-sizing-question-this-page-does-not-answer);
`--keep N` is the only retention on backups themselves.

## Cron example

```bash
# nightly at 03:10, keep 14 days
10 3 * * *  cd /opt/lumnik && deploy/selfhost/backup.sh --keep 14 >> /var/log/lumnik-backup.log 2>&1
```

Ship `backups/` to another machine (rsync, restic, object storage): a backup on the database's
own disk is only half a backup.

## Restore

Same machine (roll back data):

```bash
deploy/selfhost/restore.sh backups/<UTC-stamp>
```

The script asks first and waits for Enter:

```
!! This OVERWRITES the hub database (and Keycloak store, if present in the snapshot)
   with backups/<UTC-stamp>. Press Enter to continue, Ctrl-C to abort.
```

- **The hub is stopped** for the whole restore, and Keycloak while its store is swapped.
- **The `lumnik` database is dropped and recreated**, then the dump is restored into it: anything
  created after the snapshot is gone.
- It ends with `==> done.` and the hub started again.

Fresh machine (disaster recovery):

1. Install Docker, clone the repo, put your saved `.env` at the repo root.
2. `deploy/selfhost/up.sh` — creates the roles and an empty database.
3. `deploy/selfhost/restore.sh backups/<UTC-stamp>`.

## Known scar — issuer change orphans JIT identities

Hub users are keyed by their identity provider's issuer URL. If the public origin (and therefore
the Keycloak issuer) changed between backup and restore, users who log in afterwards get fresh
accounts, detached from their history. Restore to the same public origin, or migrate
`identity.app_user.idp_issuer` deliberately.

## What this does NOT give you

- Point-in-time recovery: it is a nightly snapshot, not WAL archiving.
- Multi-node/k8s backup: on the Helm path, use your cluster's volume snapshot tooling; only the
  `pg_dump` part translates directly.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `backup.sh` exits: `!! --keep must be a positive integer >= 1 (got: 0)` | `--keep 0` or a non-numeric value | Pass a positive integer. |
| `backup.sh` exits: `!! pg_dump produced an empty file` | the dump captured nothing (database unreachable, stack down) | Check the stack (`docker compose -f docker-compose.selfhost.yml ps`) and retry. |
| Logins fail for ~15 seconds, then work again, at a fixed hour | a `backup.sh` run (cron or manual) stopped Keycloak to copy its store | Expected; issued tokens keep working. Move the cron to a low-traffic window if needed. |
| `restore.sh` appears to hang | it waits for Enter (`Press Enter to continue, Ctrl-C to abort`) | Press Enter to proceed. |
| After a restore, Keycloak serves reads but every login fails with H2 error `90097` "The database is read only" | the store was restored by hand (e.g. `docker cp`), not by `restore.sh` | Restore again with `restore.sh`. |
| Users who logged in before a restore show up as brand-new accounts | the public origin/issuer changed between backup and restore | See the [known scar](#known-scar-issuer-change-orphans-jit-identities). |
