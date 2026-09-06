# Cheat sheet

The minimum that operates lumnik, on one page. Everything here links to its full
story; when a term puzzles you, the [glossary](glossary.md) has it in one breath.

## Stand it up

```bash
./deploy/selfhost/up.sh --build   # first run writes .env with fresh secrets — BACK IT UP
curl -s http://localhost:8080/q/health   # UP, or DOWN with the failing check named
./deploy/selfhost/logs.sh hub     # follow the hub (see: Reading the logs)
```

Full walkthroughs: [Deploy](deploy.md) · [Reading the logs](recipes/logs.md).

## The ten commands

| # | Command | What it does |
|---|---|---|
| 1 | `lm login` | authenticate once; `lm` remembers the context |
| 2 | `lm validate -f FILE` | dry-run a **`kind: Connector`** manifest without applying — errors field by field. The other four kinds have no dry-run: their checks happen at apply, which refuses rather than half-writes |
| 3 | `lm apply -f FILE` | apply a connector manifest (upsert) |
| 4 | `lm connector run NAME` | run it now |
| 5 | `lm run list --connector NAME` | the run ledger — records in/out/skip at a glance |
| 6 | `lm dlq list --run ID` | exactly which rows fell out, and why |
| 7 | `lm entity apply -f FILE` | fuse sources into one canonical entity (`lm entity delete NAME` removes it, and frees a connector it blocked) |
| 8 | `lm workflow apply -f FILE` | declare a lifecycle — prints the data-truth report |
| 9 | `lm ask --scope S "question"` | ask the data — prints the answer *and the SQL that ran* |
| 10 | `lm secret set NAME -` | store a secret from stdin; values are never returned |

The full surface (sources, endpoints, mappings, tags, observations…) is in the
[CLI & TUI reference](cli/lm-reference.md).

Command 5 is the screen you will read most. One row per run, and the arithmetic
`IN = OUT + SKIPPED` holds on every row — nothing is lost without being counted:

```
$ lm run list
ID        CONNECTOR     STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
30b078c6  customers     Completed  12  12   0        0             2026-09-05 05:38  2026-09-05 05:38
f0466804  orders        Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
3ccd21eb  products      Failed     0   0    0        0             2026-09-05 05:38  2026-09-05 05:38
852771fa  crm-contacts  Completed  5   5    0        0             2026-09-05 05:39  2026-09-05 05:39
```

`Failed` is the run itself (source unreachable, bad credentials) — `lm run get 3ccd21eb`
prints the reason. `DEAD-LETTERS` are rows set aside with their reason — `lm dlq list`.
Command 9 is the one that is not in every build: `lm ask` posts to
`POST /api/platform/rag/chat`, which the **open edition does not carry** — see
[which edition you installed](editions.md#which-edition-am-i-running). The other nine are native
to the hub.

## The TUI in six keys

`lm` with no arguments opens the TUI:

| Key | Does |
|---|---|
| `:` | command palette — `:sources` `:runs` `:dlq` `:ask` `:inbox` `:outputs` … |
| `r` | refresh |
| `d` | describe the selected row |
| `y` | yank the row to the clipboard |
| `?` | help |
| `q` | quit |

`:runs`, `:dlq` and `:inbox` live-update — you can watch a run while it happens.

## The five addresses

| What | Where |
|---|---|
| Hub API | `http://localhost:8080` — health at [`/q/health`](operations/observability.md) |
| Try the API | `http://localhost:8080/q/swagger-ui/` — spec at `/q/openapi`, [Postman recipe](recipes/test-the-api.md) |
| Keycloak | `http://localhost:8180/realms/lumnik` |
| The métier PWA | `http://localhost:8080/app/` |
| This documentation | [docs.lumnik.io](https://docs.lumnik.io/) |

## The three files

| File | Why it matters |
|---|---|
| `.env` | every secret of the stack; losing `LUMNIK_SECRET_MASTER_KEY` makes stored secrets unrecoverable — [back it up](operations/backup-restore.md) |
| `docker-compose.selfhost.yml` | the stack definition `up.sh` drives |
| `~/.lm/config.yaml` | `lm`'s contexts (server, token, TLS trust) |

## When it refuses

| You see | It means | Where to look |
|---|---|---|
| `403 {"error":"scope not granted"}` | your user carries scope roles, and this resource isn't in them — the boundary, working | [Scopes](rag/scopes.md) |
| "I can't answer that…" from the chat | a [guard](transparency/ask-honesty.md) preferred refusal over a guess; the message says what to do next | [The five guards](transparency/ask-honesty.md) |
| a run with `records skipped > 0` | nothing was lost silently — but a skip is **not** a quarantine: duplicates and policy-excluded rows are counted here and never reach the DLQ. `DEAD-LETTERS` on the same run is the subset that did | [Ingestion honesty](transparency/ingestion-honesty.md) |
