# `lm` — CLI & TUI reference

`lm` is the integrator's single binary: a kubectl-style CLI plus a k9s-style TUI. This page maps
the whole surface; every command also self-documents via `lm <cmd> --help`.

Global flags on every command: `--context` / `--server` / `--token` (override the current
context), `-o table|json|yaml`, `-v`. `--token` is sent as-is: the context's stored session and
its refresh are skipped for that command, so a token from elsewhere works even when the stored
one has expired. (Three exceptions redefine `-o` as an output *file*
path: `lm source rest from-openapi`, `lm view get`, `lm view generate`.)

## Context & auth

| Command | What it does |
|---|---|
| `lm config set-context NAME --server URL --oidc-issuer URL` | create/update a context (`--oidc-client-id` defaults to `lm-cli`, the bundled realm client) |
| `lm config use-context NAME` | switch context |
| `lm config view` | print `~/.lm/config.yaml` |
| `lm login` | OIDC **device flow**: prints a URL + code, stores tokens in the context |
| `lm version` | binary version |

First run? The [deploy page](../deploy.md) walks through standing up a hub and pointing `lm` at it.

### Environment & TLS

Two env vars override fields of the **resolved context** — CI-friendly, no `lm login`
needed: `LUMNIK_URL` replaces the server, `LUMNIK_TOKEN` the Bearer token. A context must
still exist and be current (`lm config set-context ci --server … && lm config use-context
ci`, once); the env vars swap its values per run, they don't replace it.

Two TLS fields are hand-edited in `~/.lm/config.yaml` (not `set-context` flags):
`ca-file: /path/to/root.crt` trusts an internal CA (the TLS façade's LAN certificate),
`insecure-skip-verify: true` disables verification entirely. When both are set, `ca-file`
wins — trusting a CA is the safer intent.

The `tenant:` / `user:` context fields exist too, but they only work against dev-bypass
hubs (sent as `X-Tenant-ID` / `X-User-ID` headers); a real hub ignores them.

## Manifests (all kinds)

| Command | What it does |
|---|---|
| `lm validate -f FILE` | validate without applying — field-by-field errors, exit code 1 on error |
| `lm apply -f FILE` | apply a `kind: Connector` manifest — upserts by (tenant, `metadata.name`), so re-applying an edited file updates it. Not to be confused with `lm connector apply`, which takes those flat stored fields instead |
| `lm entity apply -f FILE` | apply a `kind: Entity` fusion manifest |
| `lm entity delete NAME` | remove an entity — drops its view, deletes the manifest, rediscovers its scopes (`lm_admin`; the inverse of `entity apply`). This is what frees a connector blocked by the 409 below |
| `lm app apply -f FILE` | declare a [`kind: App`](../apps/pwa.md) métier app — title, scope, icon and theme; prints the app and, when it takes the default flag, the app that lost it |
| `lm process apply -f FILE` / `lm process list` | apply/list `kind: Process` notify rules |
| `lm process enable/disable NAME` | stop a process firing, or start it again — the manifest stays stored and re-appliable |
| `lm process rm NAME` | delete a process for good (no prompt); `404` if the tenant has no process by that name |
| `lm workflow apply -f FILE` | apply a [`kind: Workflow`](../apps/workflows.md) lifecycle — prints the data-truth report |
| `lm describe connector-type KIND` | field reference for a connector kind |

## Connectors, endpoints, sources

| Command | What it does |
|---|---|
| `lm connector list/get/apply/delete` | manage connector instances. **`connector apply -f` is not `lm apply -f`**: it takes the FLAT stored fields (`tenantId`, `name`, `typeId`, `schedule`, `enabled`, `config`) — the shape `lm connector get` writes — and is how you edit one field without rewriting a manifest; `lm apply -f` takes a `kind: Connector` manifest. Handing a manifest to `connector apply` is refused with the command that opens. **`delete` erases the connector's data**: its mirror tables (`connector.t_*` and registered REST `ext.*`) are dropped, registry rows removed, and every RAG chunk it produced dies with it — this is the [erasure gesture](../security/gdpr.md#the-rights-in-practice). A one-shot's `ext.*` table goes the same way. A table a fused entity depends on blocks the delete with a `409` naming the entity — `lm entity delete NAME` frees it |
| `lm connector run NAME` | trigger an immediate run |
| `lm connector pause/resume NAME` | stop/restart scheduling |
| `lm connector scope ls/set/add/rm/sync` | manage the connector's métier scopes; `sync` re-stamps its already-indexed chunks |
| `lm endpoint enable/disable/reset-cursor/purge C/E` | per-endpoint scheduler + cursor; `purge` deletes the endpoint's `ext.*` rows (`--force` skips the prompt) |
| `lm source rest test/probe/from-openapi` | `test C` pre-flights every ENABLED endpoint — the request a run would send, nothing written — and exits non-zero if one does not answer with readable data; `probe C --endpoint PATH` samples that one path on connector `C` — its connection and its stored credential — and drafts the endpoint fragment to paste under `spec.endpoints`; `from-openapi URL --name N` generates one (`--scope` repeatable, `-o FILE` writes to a file, `--apply` upserts straight to the hub). `source rest run` is a stub — use `lm connector run` |
| `lm source jdbc test/discover/add` | open a JDBC source directly; the password is prompted at a keyboard (never echoed) or read from stdin — `--password-stdin` declares the pipe, and an undeclared one warns. There is no `--password` |
| `lm source jdbc list-tables NAME` | list the tables registered under a source the hub already holds (no credential) |
| `lm source schema --scope TAG` / `lm source rediscover --scope TAG` | show / rebuild the scope's SchemaCard (`--scope` is required) |
| `lm csv ingest FILE\|sftp://…\|s3://…` | one-shot CSV ingest (dormant connector, `ext.*` table; askable at once in the scope you pass, no run recorded) — full SFTP/S3 flag surface: see [CSV & files](../connectors/csv.md) |

```
$ lm connector list
NAME          TYPE      TENANT  SCHED  WINDOW  STATUS     LAST RUN
customers     csv-file  1                      Completed  2026-09-05 05:38
orders        csv-file  1                      Completed  2026-09-05 05:38
products      csv-file  1                      Failed     2026-09-05 05:38
$ lm csv ingest stock.csv --scope retail --name stock-count
Ingested stock.csv into ext.stock_count
  • rows written:    3
  • promoted cols:   4
      - sku (text)
      - label (text)
      - qty (bigint)
      - warehouse (text)

  • askable:         scope 'retail' — try: lm ask --scope retail "how many rows are in ext.stock_count?"
A dormant connector is registered — see `lm connector list`.
```

## Data, runs & answers

| Command | What it does |
|---|---|
| `lm run list/get ID` | connector runs, **one row per endpoint** (records in/out/skip, per-run transparency) (`list`: newest first, `--connector NAME`, `--limit N`) |
| `lm run cancel ID` | request cancellation of a `Running` run — cooperative, not immediate (id resolves from an 8-character abbreviation, same as `get`); refused honestly rather than silently, see [Stopping a run](tui.md#watch-an-ingestion) for both refusals and the retry advice |
| `lm dlq list/get ID` | dead-letter entries (reason + offending row) (`--run` takes the run's id or its 8-char abbreviation; `--connector` takes the connector's id or name; `list --limit N`) |
| `lm dlq replayed/discard ID` | mark an entry handled (`replayed --new-run-id RUN` links it to the run that replayed it) |
| `lm conflicts list` | fused-entity rows whose sources still disagree (`--limit N`) |
| `lm observations list` | the workflow tape: watched documents, stillest first (`--limit N`) |
| `lm observations stats` | the tape aggregated per workflow: watched, dormant, median detection delay (seconds; `-` when nothing is dormant) |
| `lm integration add NAME --url U --events T [--secret-stdin]` | register a webhook target — the secret arrives on stdin, never a flag; forwards events from registration onward |
| `lm integration list` / `describe ID` | registered targets: ID, name, URL, events, enabled |
| `lm integration test ID [--event T]` | fire a catalog event at the target and report the delivery (`decision.*` types are dynamic — verify those with a real decision + `lm outputs list`) |
| `lm integration rm ID` | remove a target — delivery history is kept |
| `lm notify add --events T --to EMAIL [--template N]` / `list` / `rm ID` | email rules: events matching the pattern go to the recipient (delivery requires the deploy's SMTP knobs — see the self-host guide) |
| `lm outputs list` | what the substrate sent out and whether it arrived, failures first (`--limit N`) |
| `lm data tables` / `lm data row HASH` | dynamic tables; the raw row behind a citation |
| `lm mapping list/get/apply` | mapping templates + per-tenant overrides (`get --raw` = the raw template YAML; `apply -f FILE`) |
| `lm secret set/list/describe/rm` | encrypted-at-rest secrets; values never returned. `set NAME` prompts without echo; `set NAME -` reads stdin until EOF (a multi-line key, or a script); `set NAME VALUE` still works but warns — that value is in your shell history. Also `set --description "…"`, `rm --force`. **`rm` does no reference check**: a connector still naming the secret fails against its source at the next run |
The two tables you will read most:

```
$ lm run list --connector orders
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
f0466804  orders     default   Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
$ lm dlq list --run f0466804-0824-4eb4-8aa2-54e263dccafe
ID        CONNECTOR  RUN       REASON           ATTEMPTS  MESSAGE
66384fc7  487ed9d1   f0466804  MalformedSource  1         /tmp/exports/orders.csv:9 — Index for header 'status' is 2…
c58de40c  487ed9d1   f0466804  MalformedSource  1         /tmp/exports/orders.csv:8 — Record has 6 values but only 5…
```
!!! tip "The ID columns are abbreviated — and the abbreviation works"

    `lm run list` and `lm dlq list` shorten ids to their first 8 characters so the table fits a
    terminal. What they show is what `get`, `discard` and `replayed` accept: paste the 8 characters
    back and the id is resolved for you. An ambiguous prefix is refused rather than guessed, and
    `-o yaml` prints every id in full. The `--run` and `--connector` filters resolve the same way:
    `--run` accepts the 8-character abbreviation, and `--connector` accepts the connector's name.

!!! tip "A full page says so — and `--connector` asks the hub, not the page"

    Six commands answer in **pages**: `lm run list`, `lm dlq list`, `lm conflicts list`,
    `lm observations list`, `lm outputs list` and `lm inbox`. Each takes `--limit N`, and each
    door has its own ceiling — the run and dead-letter ledgers and the inbox stop at 200,
    deliveries at 500, conflicts and the workflow tape at 1000.

    When a page comes back **full**, the command prints
    `note: result hit the limit of N — there may be more` on **stderr**; the table itself stays on
    stdout, so a pipe, `-o json` and `-o yaml` are unaffected. It says *may* because a page that
    fits exactly looks the same from outside. Raise `--limit` to see further back.

    `--connector` on `lm run list` is part of the question the hub is asked, not a sieve over the
    page that came back. That is what lets you find a connector that has not run for a while on a
    busy hub: its runs are looked up among **its own**, so a quiet connector never comes back as
    an empty table just because newer runs filled the page.

## Ask your data

| Command | What it does |
|---|---|
| `lm ask [--scope S] "question"` | analytic text-to-SQL over the fused hub — prints the answer, a `data as of` line (the scope's last completed run) **and the SQL that ran** ([the five guards](../transparency/ask-honesty.md) apply); a [scope-bound](../rag/scopes.md#binding-users-to-scopes-opt-in) user must name a granted scope or gets `403 scope not granted` — without `--scope`, asks your default (`lm scope set TAG`; same 403 if not granted) |
| `lm chat` | interactive REPL chat — in-REPL commands: `:corpus NAME` (or `all`), `:scope TAG` (or `default`), `:topK N`, `:clear`, `:help`, `:exit` |
| `lm scopes` / `lm scope` / `lm scope set TAG` | list scopes (a scope-bound user sees only their granted métiers); show/set your default (`404 no user record on this hub…` means your login points at a hub user id that does not exist — [Tenants, users & scopes](../security/identity-admin.md)) |
| `lm rag search/status/reindex` | semantic search (`--top-k N`, default 5); corpus status; re-index of the **platform corpus** (manifests/API/hooks — ingested-row chunks are written at ingestion and erased with their connector) |

## The apps ladder

| Command | What it does |
|---|---|
| `lm view -f FILE [--filter …]` | render a [`kind: View`](../apps/views.md) list — filters: `col=v` · `col~text` · `col>v` (`>= < <=`, number/ISO date); repeatable (AND). The value is a **bare literal**: `--filter 'city=Lyon'`, and a quoted one (`city='Lyon'`) is refused rather than matched against the quotes |
| `lm view KEY -f FILE` | one record's detail (with the `_conflicts` ledger) |
| `lm view generate SCOPE "DESCRIPTION" [-o FILE]` | ask the AI to write a `kind: View` manifest over a scope; review, then render with `lm view -f`. Refusals (abstention / failed generation) surface verbatim as errors |
| `lm view list [--scope S]` | list the saved views visible to the caller (scope-bound reads apply) |
| `lm view get NAME [-o FILE]` | fetch one saved view's manifest YAML — to stdout, or to a file |
| `lm view apply -f FILE` | validate and upsert a `kind: View` manifest into the saved-view store |
| `lm app apply -f FILE` | declare a [`kind: App`](../apps/pwa.md) métier app — title, scope, icon and theme; prints the app and, when it takes the default flag, the app that lost it |
| `lm workflow apply -f FILE` | declare a [`kind: Workflow`](../apps/workflows.md) lifecycle; prints the data-truth report — values in the column the manifest never declared, and declared states never seen. Never blocks |
| `lm workflow list` | the tenant's declared lifecycles (name, table, column, scope) |
| `lm workflow get NAME` | fetch one workflow's manifest YAML to stdout (redirect with `>` to save; the global `-o` here means output *format*, it does not write a file) |
| `lm inbox [--limit N] [--all]` | recent `decision.*` events — [what needs you](../apps/inbox.md) |
| `lm inbox ack ID` | dispose of a decision (idempotent — the ack is an event) |

## Admin & platform

| Command | What it does |
|---|---|
| `lm module list` | installed business modules |
| `lm migration status/apply` | Flyway status per schema (`apply --schema core\|identity\|…` — required) |
| `lm hook list/get/apply/delete` · `reload NAME` | Kotlin platform hooks (`hook generate` is a v1.x stub) |

## The TUI

Bare `lm` (or `lm ui`) launches the k9s-style TUI. `:` opens the command palette.

Full tour by task, plus the complete key reference, on [The TUI](tui.md).
