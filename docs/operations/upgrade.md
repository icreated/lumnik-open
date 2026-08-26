# Upgrading the hub

> Forward-only Flyway migrations run at boot in one shared version namespace across
> `core`/`connector`/`platform` — the pre-upgrade backup is the only rollback there is.

## The short version

1. **Take a backup first** — [backup-restore.md](backup-restore.md). Migrations are
   forward-only; the backup IS the rollback.
2. Point at the new image and restart:
   - selfhost: set `LUMNIK_VERSION` in `.env` (or pull latest), then
     `deploy/selfhost/up.sh` — compose recreates the hub container.
   - Helm: bump `hub.image.tag`, `helm upgrade`.
3. Flyway migrates at boot. Budget per path: the Helm startup probe allows ~150 s;
   the selfhost compose healthcheck allows ~210 s (90 s grace + 12×10 s) and only marks
   the container `unhealthy` — it never restarts it. Watch
   `docker compose -f docker-compose.selfhost.yml logs -f hub` until healthy.

## Rules the migrations follow

- One shared Flyway version namespace across `core`, `connector` and `platform`
  (they run in a single session) — a release never reuses a number.
- Forward-only: there are no down-migrations. Rolling back = restoring the
  pre-upgrade backup (and losing writes made since — hence step 1).
- Every migration is checksum-validated at boot (`validateOnMigrate(true)`,
  `PlatformFlywayRunner.java:41`) — editing an already-applied migration file, not just
  adding a new one, fails validation before any SQL runs; ship a new migration instead
  of touching a shipped one.

## If the boot fails mid-migration

Flyway stops at the failing version; the hub never reports ready and old data is
untouched by later migrations. Capture `logs hub`, restore the backup if you need
service back immediately, and report the failing version.

## Known scar

Changing the public origin (TLS façade hostname, port, issuer path) during an upgrade
orphans JIT identities — see the scar note in
[backup-restore.md](backup-restore.md#known-scar-issuer-change-orphans-jit-identities).
