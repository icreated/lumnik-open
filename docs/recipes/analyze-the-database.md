# Analyze the database directly

Connect `psql` or a GUI (pgAdmin, IntelliJ, DBeaver) to the self-host PostgreSQL.
To browse ingested rows without SQL: `lm data tables`, then `:tables` in `lm ui` ([CLI reference](../cli/lm-reference.md)).

## Three roles, three postures

The install creates three database roles:

| Role | Sees | Can write | Use it for |
|---|---|---|---|
| `lumnik_readonly` | schema `connector` only (your ingested data), one tenant at a time | no — refused by PostgreSQL itself | day-to-day analysis of ingested data |
| `lumnik_app` | one tenant at a time (RLS applies) | yes | reproducing the application's own posture |
| `lumnik_admin` | **every schema, every tenant** (bypasses RLS) | yes, including DDL | cross-tenant checks, the `core` and `identity` schemas — read only, unless you know why |

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
listens to your machine, not your network. To close the door, run the same command without
the override file:

```bash
docker compose -f docker-compose.selfhost.yml up -d postgres
```

Opening or closing the door recreates the database container: the hub loses its database for a
few seconds.

## The one habit: name your tenant

Under `lumnik_readonly` or `lumnik_app`, row-level security filters every table by the
current tenant — and until you name one, you see **no rows at all**. Start each session with:

```sql
SET app.current_tenant = '1';   -- your tenant id; a fresh install seeds tenant 1
```

In IntelliJ, put that line in the data source's *startup script* so every connection
carries it. In pgAdmin, run it at the top of each Query Tool session.

Ingested data lands in schema `connector` as `t_`-prefixed tables, one per endpoint, each
with the `tenant_id` column the isolation keys on. As `lumnik_readonly`:

```
lumnik=> select count(*) from connector.t_orders;
 count
-------
     0
(1 row)

lumnik=> SET app.current_tenant = '1';
SET
lumnik=> select count(*) from connector.t_orders;
 count
-------
     9
(1 row)

lumnik=> select count(*) from core.audit_event;
ERROR:  permission denied for table audit_event
```

`\dn` lists the schemas: `connector`, `core`, `identity`, `platform`, `rag`, `public`.
Outside `connector`, connect as `lumnik_admin`; it sees every tenant without a `SET`.
