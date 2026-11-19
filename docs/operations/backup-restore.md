# Backup & Restore (self-host)

> A hot `pg_dump` of the hub's database and of Keycloak's. A restore drops and recreates them.
> `.env` is not covered.

The self-host stack holds its data in two databases of one PostgreSQL (`selfhost_pg` volume). Both
are dumped hot, with no downtime:

| Database | Contents |
|---|---|
| `lumnik` | ALL hub data: ingested tables, RAG chunks, saved views, identities, audit |
| `keycloak` | the realms: users, password hashes, roles, signing keys |

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
| `backups/<UTC-stamp>/keycloak.dump` | unless you passed `--no-keycloak` |

`backups/` is readable by its owner only (files `600`, folders `700`), whatever your umask, and
each run tightens the snapshots an older version left open. One file holds every tenant's data,
the other Keycloak's password hashes and signing keys. Keep it that way when you copy them off
the machine. If another account ships `backups/` (an rsync user, a group), run the backup as
that account: it can no longer read files that `sudo ./deploy/selfhost/backup.sh` wrote.

**Not covered — back these up separately, off-machine:**

- `.env` — it holds the two keys, `LUMNIK_SECRET_MASTER_KEY` and `LUMNIK_CRYPTO_SECRET_KEY`.
  Without them, secrets stored in the hub are unrecoverable
  ([the master key](../security/rls.md#secrets-at-rest-the-master-key)). Copy it again each time
  you change a password in it.
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
!! This OVERWRITES the hub database (and Keycloak's, if present in the snapshot)
   with backups/<UTC-stamp>. Press Enter to continue, Ctrl-C to abort.
```

- **The hub is stopped** for the whole restore, and Keycloak while its database is replaced.
- **The `lumnik` and `keycloak` databases are dropped and recreated**, then each dump is restored
  into its own: anything created after the snapshot is gone, users included.
- It ends with `==> done.` and the hub started again.

Fresh machine (disaster recovery):

1. Install Docker, clone the repo, put your saved `.env` at the repo root.
2. `deploy/selfhost/up.sh` — creates the roles and empty databases.
3. `deploy/selfhost/restore.sh backups/<UTC-stamp>`.

Check it worked:

```bash
lm migration status     # every schema at the snapshot's version, PENDING 0
lm run list             # your last runs, as they were at the snapshot
```

Restore onto the **same public address**: another one turns every user into a new account
([why, and the fix](../security/byo-idp.md#keep-the-same-public-address)).

## What this does NOT give you

- Point-in-time recovery: it is a nightly snapshot, not WAL archiving.
- Kubernetes (cloud edition): use your cluster's volume snapshots; only the `pg_dump` part carries
  over. Dump both databases, `lumnik` and `keycloak`: the chart's Keycloak keeps its realm there.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `backup.sh` exits: `!! --keep must be a positive integer >= 1 (got: 0)` | `--keep 0` or a non-numeric value | Pass a positive integer. |
| `backup.sh` exits: `!! pg_dump produced an empty file` | the dump captured nothing (database unreachable, stack down) | Check the stack (`docker compose -f docker-compose.selfhost.yml ps`) and retry. |
| `restore.sh` appears to hang | it waits for Enter (`Press Enter to continue, Ctrl-C to abort`) | Press Enter to proceed. |
| `restore.sh` exits: `!! backups/<UTC-stamp> holds Keycloak's store from before it moved to PostgreSQL (keycloak-data.tgz).` | the snapshot was taken while Keycloak kept its own file, before [Keycloak moved to PostgreSQL](upgrade.md#keycloak-runs-in-production-mode-on-postgresql); nothing was changed | Check out the release that took it, restore with its scripts, then come back and run `up.sh`: it moves the realm. |
| Users who logged in before a restore show up as brand-new accounts | the public origin/issuer changed between backup and restore | [Keep the same public address](../security/byo-idp.md#keep-the-same-public-address). |
