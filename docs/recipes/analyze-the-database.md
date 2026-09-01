# Analyze the database directly

The hub's data is yours — connecting pgAdmin, IntelliJ or plain `psql` to the self-host
PostgreSQL is a supported gesture, not a workaround. This page gives you the door, the
credentials, and the one habit that makes what you see match what the product sees.

## Three roles, three postures

The install creates three database roles. Pick by what you want your session to be
*able* to do:

| Role | Sees | Can write | Use it for |
|---|---|---|---|
| `lumnik_readonly` | one tenant at a time (RLS applies) | no — refused by PostgreSQL itself | day-to-day analysis: you see exactly what the product's chat sees, and cannot break anything |
| `lumnik_app` | one tenant at a time (RLS applies) | yes | reproducing the application's own posture |
| `lumnik_admin` | **every tenant** (bypasses RLS) | yes, including DDL | cross-tenant checks, schema exploration — handle in read-mostly discipline |

Passwords live in your `.env` (`LUMNIK_READONLY_DB_PASSWORD`, `LUMNIK_APP_DB_PASSWORD`,
`LUMNIK_ADMIN_DB_PASSWORD`) — generated per install, never shipped.

## Quick look, no tooling

The database publishes no port to your machine by default. From inside the container,
`psql` connects without a password:

```bash
CID=$(docker compose -f docker-compose.selfhost.yml ps -q postgres)
docker exec -it $CID psql -U lumnik_readonly -d lumnik
```

## Connect a GUI (pgAdmin, IntelliJ, DBeaver…)

Give the database a TCP door on your machine only, via a compose override — the
shipped file stays untouched:

```yaml
# analyse-ports.yml (next to docker-compose.selfhost.yml)
services:
  postgres:
    ports: ["127.0.0.1:5433:5432"]
```

```bash
docker compose -f docker-compose.selfhost.yml -f analyse-ports.yml up -d postgres
```

Then in your tool: host `localhost`, port `5433`, database `lumnik`, one of the three
roles above with its `.env` password. The `127.0.0.1:` prefix matters — the database
listens to your machine, not your network. To close the door, re-run `up.sh` (or the
same command without the override file).

## The one habit: name your tenant

Under `lumnik_readonly` or `lumnik_app`, row-level security filters every table by the
current tenant — and until you name one, you see **no rows at all** (deliberate:
fail-closed). Start each session with:

```sql
SET app.current_tenant = '1';   -- your tenant id; a fresh install seeds tenant 1
```

In IntelliJ, put that line in the data source's *startup script* so every connection
carries it. In pgAdmin, run it at the top of each Query Tool session.

Where to look first: `\dn` lists the schemas (`core`, `connector`, `identity`);
ingested data lands in `connector` as `t_`-prefixed tables, one per endpoint, each
carrying the `tenant_id` column the isolation keys on. The same query run without the
`SET`, then as `lumnik_admin`, is the fastest way to *see* the tenant isolation work.
