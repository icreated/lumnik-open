# Upgrading the hub

> Migrations run forward-only at boot. The pre-upgrade backup is the only rollback.

## The short version

1. **Take a backup** — [backup-restore.md](backup-restore.md). The backup is the rollback.
2. Note the current schema versions:

    ```
    $ lm migration status
    SCHEMA     VERSION  PENDING  LAST APPLIED
    core       83       0        2026-10-25 07:04
    identity   18       0        2026-10-24 21:55
    rag        6        0        2026-10-24 21:55
    search_pg  3        0        2026-10-25 00:13
    ```

    `search_pg` appears only on a hub built with Search.

3. Point at the new image and restart:
    - **selfhost:** set `LUMNIK_VERSION` in `.env` (or leave it unset to track `main`), then
      `deploy/selfhost/up.sh`. It keeps the overlays the stack was started with (TLS proxy, Search).
      A stack with Search is rebuilt from source, not pulled: `git pull` first, as in
      [Running from source](#running-from-source).

    - **Helm (cloud edition):** bump `hub.image.tag`, then `helm upgrade`.
4. The hub migrates its database at boot, then reports healthy:

    | Path | Boot budget | On timeout |
    |---|---|---|
    | selfhost compose | ~210 s (90 s grace + 12 × 10 s) | the container shows `unhealthy`; it is not restarted |
    | Helm (cloud edition) | ~150 s (startup probe) | Kubernetes restarts the pod |

    Follow the boot with `./deploy/selfhost/logs.sh hub`.
5. Run `lm migration status` again: a higher `VERSION` and `PENDING 0` on every schema means the
   migrations are done.
6. Apply each [`resolution:` entity](../entities-resolution.md) again with the same manifest, then
   run `lm entity resolve NAME`.

## Turning on the run queue

`LUMNIK_RUN_QUEUE_ENABLED=true` (Helm: `runQueue.enabled`) makes manual connector runs and
on-demand export runs wait in status `Queued` ([connectors](../connectors/index.md),
[exports](../apps/exports.md#runs-can-queue)).

| Setting | Default | Effect |
|---|---|---|
| `LUMNIK_RUN_QUEUE_ENABLED` | `false` | queue manual and on-demand runs |
| `LUMNIK_RUN_QUEUE_MAX_AGE` | `1h` | a run still queued after this ends: `Cancelled` (connector), `Failed` (export) |
| `LUMNIK_RUN_QUEUE_POLL_INTERVAL` | `5s` | how often a waiting run is picked up |
| `LUMNIK_CONNECTOR_MAX_CONCURRENT_SCHEDULED_RUNS` | `4` | how many endpoint runs one hub drives at once. Each takes up to two connections of the `admin` pool: raise `quarkus.datasource."admin".jdbc.max-size` with it |

The setting is global to the deployment: upgrade **every** hub replica to the same release first,
then turn it on. Turning it off again:

- runs already queued still start, or end once they pass `LUMNIK_RUN_QUEUE_MAX_AGE`;
- new manual and on-demand runs start at once, except where a run is still queued: there a new
  connector run is born `Cancelled` and a new export run answers `409`, until the queued one drains;
- nothing is left stuck.

## Release notes that need a gesture

Some releases change what existing data means. Each section below says what you see after
upgrading and what to do.

### A REST header reference must resolve { #a-rest-header-reference-must-resolve }

A REST connector header declared with `value_env` now reads like the auth fields: the `lm secret`
entry first, then the hub's environment ([REST § request headers](../connectors/rest.md#request-headers)).

- **A name that resolves nowhere fails the run**, `lm source rest test` and `lm source rest probe`:
  `Header X-Client-Id unavailable (env=CLIENT_ID)`. Before, the header was dropped and the request
  went out without it. Store the value (`lm secret set CLIENT_ID`), or remove the header from the
  manifest if the source does not need it.
- **An `lm secret` entry now outranks an environment variable of the same name** for a header. If
  both exist with different values, the secret's value is sent: remove the one you do not want.

### Subscriptions are tenant-wide

Webhooks and email rules are refused to an admin bound to any `scope:*` role — see
[Outputs](../outputs.md). Ones a bound admin created earlier keep firing. A tenant-wide admin lists
them and removes what nobody owns:

```
$ lm notify list
ID    EVENTS            RECIPIENT        TEMPLATE            ENABLED
1000  decision.*        adv@example.com  decision-alert      true
1001  data.row.changed  ops@example.com  event-notification  true
$ lm integration list
ID    NAME            URL                        EVENTS                      ENABLED
1000  ops-automation  https://hooks.example.com  decision.*,connector.run.*  true
$ lm notify rm 1001
removed: 1001
$ lm notify list
ID    EVENTS      RECIPIENT        TEMPLATE        ENABLED
1000  decision.*  adv@example.com  decision-alert  true
```

A webhook goes the same way: `lm integration rm <ID>`.

### One local csv file is the table's state { #one-local-csv-file-is-now-the-tables-state }

A `csv-file` connector whose local `path` names one file (no `*` or `?`) removes the rows the file
no longer holds — see [CSV § full refresh](../connectors/csv.md#one-file-is-the-table-full-refresh).
Before the upgrade, the table kept every older version of a changed row. The first run that reads
the file after upgrading (a file is read again only once it changes) counts those older versions
as removed. Later runs count only the rows the file lost. On that first run:

- up to 30 % of the table: they are removed;
- beyond 30 %: the run fails, removes nothing and names the counts;
- to keep them, point the connector at a folder (`*.csv`); to let them go, set
  `refresh.max_removed: off` for that run, then remove the line.

### A jdbc table holds one row per key { #a-jdbc-table-now-holds-one-row-per-key }

A `jdbc-generic` table holds one row per `pk_columns` value
([JDBC § one row per key](../connectors/jdbc.md#one-row-per-key-a-changed-row-replaces-its-version)).
The first run after upgrading keeps the newest row per key and removes the older versions, even
when it reads nothing new:

```
$ lm connector run demo-invoices
run scheduled: 13629016-e970-4a1a-92b7-aed86627f3f8
$ lm run get 13629016
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
13629016  demo-invoices  invoices  Completed  0   0    0        0             2026-10-18 13:49  2026-10-18 13:49
• 2 row(s) removed — the source no longer holds them
```

If one earlier run read the same key twice with two different values, the run fails naming
`pk_columns` and removes nothing. Declare the source table's whole primary key, apply, run again.

## Running from source

```bash
git pull
make -C lm install                 # installs lm into ~/.local/bin (keep it on your PATH)
./deploy/selfhost/up.sh --build    # rebuilds and restarts the hub
```

Your data is kept; migrations run at the hub's next boot. On a TLS-proxy install, run its
command again afterwards.

## Roll back

There is no down-migration. Rolling back is restoring the backup taken in step 1; writes made
since are lost.

1. Set `LUMNIK_VERSION` in `.env` back to the previous release.
2. `deploy/selfhost/up.sh`: the hub comes back on the previous image.
3. `deploy/selfhost/restore.sh backups/<UTC-stamp>`: the database as it was before the upgrade.

## If the boot fails mid-migration

The migration stops at the failing version; later migrations do not touch your data, and the hub
does not become healthy.

1. Save the log: `docker compose -f docker-compose.selfhost.yml logs hub > hub-upgrade.log`.
2. Need service back now? [Roll back](#roll-back).
3. Report the failing version: the last `Migrating schema … to version …` line in the log.

## Changing the public address

Do not change the hub's public address during an upgrade: every user would log in as a new one.
[Keep the same public address](../security/byo-idp.md#keep-the-same-public-address).
