# `lm` — CLI & TUI reference

`lm` is the integrator's single binary: a kubectl-style CLI plus a k9s-style TUI. This page maps
the whole surface; every command also self-documents via `lm <cmd> --help`.

Global flags on every command: `--context` / `--server` / `--token` (override the current
context), `-o table|json|yaml`, `-v`. (Three exceptions redefine `-o` as an output *file*
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
| `lm apply -f FILE` | apply a connector manifest |
| `lm entity apply -f FILE` | apply a `kind: Entity` fusion manifest |
| `lm process apply -f FILE` / `lm process list` | apply/list `kind: Process` notify rules |
| `lm workflow apply -f FILE` | apply a [`kind: Workflow`](../apps/workflows.md) lifecycle — prints the data-truth report |
| `lm describe connector-type KIND` | field reference for a connector kind |

## Connectors, endpoints, sources

| Command | What it does |
|---|---|
| `lm connector list/get/apply/delete` | manage connector instances (`apply -f` = same upsert as `lm apply`). **`delete` erases the connector's data**: its mirror tables (`connector.t_*` and registered REST `ext.*`) are dropped, registry rows removed, and every RAG chunk it produced dies with it — this is the [erasure gesture](../security/gdpr.md#the-rights-in-practice). Exception: a one-shot's `ext.*` table is not registered — purge its endpoint first ([quickstart](../quickstart/installer.md#from-demo-to-production)) |
| `lm connector run NAME` | trigger an immediate run |
| `lm connector pause/resume NAME` | stop/restart scheduling |
| `lm connector tag ls/set/add/rm` · `retag` | manage tags (scopes); `retag` propagates to existing chunks |
| `lm endpoint enable/disable/reset-cursor/purge C/E` | per-endpoint scheduler + cursor; `purge` deletes the endpoint's `ext.*` rows (`--force` skips the prompt) |
| `lm source rest test/probe/from-openapi` | probe a REST source, generate a manifest from OpenAPI (`from-openapi URL --name N` required; `--tag` repeatable, `-o FILE` writes to a file, `--apply` upserts straight to the hub; `source rest run` is a stub — use `lm connector run`) |
| `lm source jdbc …` | test/discover a JDBC source, list tables |
| `lm source schema --scope TAG` / `lm source rediscover --scope TAG` | show / rebuild the scope's SchemaCard (`--scope` is required) |
| `lm csv ingest FILE\|sftp://…\|s3://…` | one-shot CSV ingest (dormant connector, `ext.*` table; no RAG corpus) — full SFTP/S3 flag surface: see [CSV & files](../connectors/csv.md) |

## Data, runs & answers

| Command | What it does |
|---|---|
| `lm run list/get ID` | connector runs (records in/out/skip, per-run transparency) (`list`: `--connector NAME`, `--limit N`) |
| `lm dlq list/get ID` | dead-letter entries (reason + offending row) (`--connector` / `--run` filter every subcommand; `list --limit N`) |
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
| `lm secret set/list/describe/rm` | encrypted-at-rest secrets; values never returned (`set NAME -` reads stdin; `set --description "…"`; `rm --force` skips the prompt). **`rm` does no reference check**: a connector still naming the secret fails against its source at the next run |
!!! tip "The ID columns are abbreviated — and the abbreviation works"

    `lm run list` and `lm dlq list` shorten ids to their first 8 characters so the table fits a
    terminal. What they show is what `get`, `discard` and `replayed` accept: paste the 8 characters
    back and the id is resolved for you. An ambiguous prefix is refused rather than guessed, and
    `-o yaml` prints every id in full.

## Ask your data

| Command | What it does |
|---|---|
| `lm ask [--scope S] "question"` | analytic text-to-SQL over the fused hub — prints the answer, a `data as of` line (the scope's last completed run) **and the SQL that ran** ([the five guards](../transparency/ask-honesty.md) apply); a [scope-bound](../rag/scopes.md#binding-users-to-scopes-opt-in) user must name a granted scope or gets `403 scope not granted` |
| `lm chat` | interactive REPL chat — in-REPL commands: `:corpus NAME` (or `all`), `:topK N`, `:clear`, `:help`, `:exit` |
| `lm scopes` / `lm scope` / `lm scope set TAG` | list scopes (a scope-bound user sees only their granted métiers); show/set your default |
| `lm rag search/status/reindex` | semantic search (`--top-k N`, default 5); corpus status; re-index of the **platform corpus** (manifests/API/hooks — ingested-row chunks are written at ingestion and erased with their connector) |

## The apps ladder

| Command | What it does |
|---|---|
| `lm view -f FILE [--filter …]` | render a [`kind: View`](../apps/views.md) list — filters: `col=v` · `col~text` · `col>v` (`>= < <=`, number/ISO date); repeatable (AND) |
| `lm view KEY -f FILE` | one record's detail (with the `_conflicts` ledger) |
| `lm view generate SCOPE "DESCRIPTION" [-o FILE]` | ask the AI to write a `kind: View` manifest over a scope; review, then render with `lm view -f`. Refusals (abstention / failed generation) surface verbatim as errors |
| `lm view list [--scope S]` | list the saved views visible to the caller (scope-bound reads apply) |
| `lm view get NAME [-o FILE]` | fetch one saved view's manifest YAML — to stdout, or to a file |
| `lm view apply -f FILE` | validate and upsert a `kind: View` manifest into the saved-view store |
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
