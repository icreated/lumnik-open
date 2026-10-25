# Cheat sheet

The minimum that operates lumnik, on one page. Unknown word: [glossary](glossary.md).

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
| 2 | `lm validate -f FILE` | dry-run a **`kind: Connector`** manifest — errors field by field. The other kinds have no dry-run: their apply checks first and refuses rather than half-writes |
| 3 | `lm apply -f FILE` | apply a connector manifest (upsert) |
| 4 | `lm connector run NAME` | run it now |
| 5 | `lm run list --connector NAME` | the run ledger — records in/out/skip at a glance |
| 6 | `lm dlq list --run ID` | exactly which rows fell out, and why |
| 7 | `lm entity apply -f FILE` | fuse sources into one canonical entity (`lm entity delete NAME` removes it, and frees a connector it blocked) |
| 8 | `lm workflow apply -f FILE` | declare a lifecycle — lists the status values your data holds and the manifest does not declare |
| 9 | `lm ask --scope S "question"` | ask the data — prints the answer, the rows *and the SQL that ran* (`-o json` for the whole result). **lumnik Pro** |
| 10 | `lm secret set NAME` | store a secret — prompted, not echoed (`NAME -` reads stdin instead, for a multi-line key or a script); values are never returned |

The full surface (sources, endpoints, mappings, tags, observations…) is in the
[CLI & TUI reference](cli/lm-reference.md).

Command 5 is the screen you will read most. On every row, `IN = OUT + SKIPPED`:

```
$ lm run list
ID        CONNECTOR     ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
30b078c6  customers     default   Completed  12  12   0        0             2026-09-05 05:38  2026-09-05 05:38
f0466804  orders        default   Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
3ccd21eb  products      default   Failed     0   0    0        0             2026-09-05 05:38  2026-09-05 05:38
852771fa  crm-contacts  default   Completed  5   5    0        0             2026-09-05 05:39  2026-09-05 05:39
```

- **`Failed`** is the run itself (source unreachable, bad credentials): `lm run get 3ccd21eb` prints the reason.
- **`DEAD-LETTERS`** are rows set aside with their reason: `lm dlq list`.
- **Stop a running run:** `lm run cancel ID`. It ends after the current batch of rows.

Every `-f` also takes `-f -` (stdin), and every listing takes `-o json`:
[Integration as code](integration-as-code.md). Command 9 answers `Error: API error 404 (no details from the server)` on the open edition —
see [which edition you installed](editions.md#which-edition-am-i-running).

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
| The PWA | `http://localhost:8080/app/` |
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
| `Error: access refused (403): scope not granted` | your user holds scope roles, and this resource is in none of them | [Scopes](rag/scopes.md) |
| `Je ne peux pas répondre à partir des données disponibles…` from `lm ask` | a [guard](transparency/ask-honesty.md) refused rather than guess; the message says what to do next | [The five guards](transparency/ask-honesty.md) |
| a run with `SKIPPED > 0` | rows counted and not written. Duplicates and policy-excluded rows are skipped without reaching the DLQ; `DEAD-LETTERS` is the part that did | [Ingestion honesty](transparency/ingestion-honesty.md) |
