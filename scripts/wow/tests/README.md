# WOW test suites (`lm`-driven)

Replays the WOW arc through the **real `lm` CLI** — the same commands an integrator
types — and asserts the result. Everything that has an `lm` command goes through `lm`;
the analytic acts use `curl` because **there is no `lm` command for analytic ask yet**
(`RagAsk` is unwired — the `:ask` TUI page exists, but no headless CLI verb).

## Prerequisites

- Platform on `:8080` — `mvn quarkus:dev -pl lumnik-hub -am`
  (the platform's own env must hold `WOW_SRC_PASSWORD` so `password_env` resolves at run time).
- The Adempiere source on `:5432` — its URL is `WOW_SRC_URL` in `.env`, never in a
  tracked file — hub Postgres on `:5433`, Ollama up.
- `scripts/wow/.env` filled in (gitignored). Keys: `WOW_SERVER WOW_TENANT_ID WOW_SCOPE
  WOW_SRC_URL WOW_SRC_USER WOW_SRC_PASSWORD WOW_HUB_HOST WOW_HUB_PORT WOW_HUB_DB
  WOW_RO_USER WOW_RO_PASSWORD`.
- `lm` built at `lm/lm` (or set `LM_BIN=/path/to/lm`).

## Suites

| Script | What it does | Writes? |
|--------|--------------|---------|
| `00-smoke.sh`   | read-only health: `connector list`, `migration status`, `source jdbc test`, `source schema` | no |
| `10-wow-e2e.sh` | full arc: `apply` ×2 → `connector run` ×2 → poll hub → `rediscover`/`schema` → acts | **yes** |
| `20-acts.sh`    | acts only (data already ingested): 3 analytic asks + deterministic SQL ground-truth | no |

Run one:

```bash
./scripts/wow/tests/00-smoke.sh
./scripts/wow/tests/10-wow-e2e.sh   # the big one
./scripts/wow/tests/20-acts.sh
```

Exit code is non-zero if any assertion failed (CI-friendly).

## The `lm` commands these suites use

```
lm connector list
lm migration status
lm source jdbc test --url <jdbc-url> --user <user>      # password on stdin
lm source schema     --scope <scope>
lm source rediscover --scope <scope>
lm apply -f <manifest.yaml>                             # declarative connector+endpoints
lm connector run <connector-name>                       # async; name → id resolved server-side
```

Acts (no `lm` verb yet) hit the REST endpoint directly:

```
POST /api/platform/rag/chat   {"question": "...", "mode": "analytic", "scope": "<scope>"}
header: X-Tenant-Id: <tenant>
```

## Known gap

`lm ask`/analytic CLI is the obvious next `lm` verb: wrap the `mode=analytic` call so
the acts become `lm ask --scope atelier "how many sales orders?"` and these suites
drop their last `curl`. Tracked alongside the `:ask` TUI page work.

## Notes

- Acts assert **loosely** (HTTP 200 + ≥1 row): the LLM's SQL phrasing varies per run,
  so each act prints the generated SQL + answer *and* the deterministic readonly-SQL
  ground-truth next to it — the WOW is verified against truth, not just "looks plausible".
- `ro_sql` sets `app.current_tenant` so it reads correctly whether or not RLS is on the
  `t_*` tables (RLS is currently ON for `t_c_*`).
