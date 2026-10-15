# `lm` — CLI & TUI reference

`lm` is the integrator's single binary: a kubectl-style CLI plus a k9s-style TUI. This page maps
the whole surface; every command also self-documents via `lm <cmd> --help`.

Global flags on every command: `--context` / `--server` / `--token` (override the current
context), `-o table|json|yaml`, `-v`. `--token` is sent as-is: the context's stored session and
its refresh are skipped for that command, so a token from elsewhere works even when the stored
one has expired. (Three exceptions redefine `-o` as an output *file*
path: `lm source rest from-openapi`, `lm view get`, `lm view generate`.)

A command that only groups others (`lm connector`, `lm entity`, `lm module`, `lm completion`…) prints its subcommands when typed
alone. A word that is not one of them — `lm connector lst` — is an error with a non-zero exit, so a script never
mistakes a typo for a success:

```
$ lm connector lst; echo "exit=$?"
Error: unknown command "lst" for "lm connector"
exit=1
```

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
| `lm entity get NAME [--filter COLUMN=VALUE]… [--limit N] [--offset N]` | read one page of an entity's canonical rows (`GET /api/entities/{name}`, `lm_user`, scope-bound) with `-o table\|json\|yaml`. `--filter` is an exact match on a canonical column, repeatable; `--limit` is 1..1000 (default 100) and a full page is announced on stderr — you page with `--offset`. Refused before the hub is asked: a filter named `limit`/`offset`, a column named twice, a bad range. A filter naming no column is refused too when the page has rows to prove it (the hub ignores it and returns every row; an empty page cannot tell) |
| `lm entity export NAME -o FILE [--force]` | download the entity's canonical CSV snapshot through the [one-shot door](../apps/exports.md#managed-export-vs-one-shot-download) — no `kind: Export` needed. The file appears only once the whole body has arrived, an existing file is refused without `--force`, and `-o -` streams to stdout |
| `lm app apply -f FILE` | declare a [`kind: App`](../apps/pwa.md) métier app — title, scope, icon and theme; prints the app and, when it takes the default flag, the app that lost it |
| `lm app list` | the declared apps — name, title, scope, icon, theme, default and tabs; `-o json` / `-o yaml` give every field the hub sends ([see it](../apps/pwa.md#see-what-is-declared)) |
| `lm process apply -f FILE` / `lm process list` | apply/list `kind: Process` notify rules |
| `lm process enable/disable NAME` | stop a process firing, or start it again — the manifest stays stored and re-appliable |
| `lm process rm NAME` | delete a process for good (no prompt); `404` if the tenant has no process by that name |
| `lm workflow apply -f FILE` | apply a [`kind: Workflow`](../apps/workflows.md) lifecycle — prints the data-truth report |
| `lm proposal draft DOSSIER` | write a draft [`kind: Workflow`](../apps/workflows.md#drafting-a-manifest-from-a-dossier) from a proposal dossier (`-` for stdin) — flags `--name --scope --table --column --key --after -o`; offline, never applies |
| `lm describe connector-type KIND` | field reference for a connector kind |

## Connectors, endpoints, sources

| Command | What it does |
|---|---|
| `lm connector list/get/apply/delete` | manage connector instances. **`connector apply -f` is not `lm apply -f`**: it takes the FLAT stored fields (`tenantId`, `name`, `typeId`, `schedule`, `enabled`, `config`) — the shape `lm connector get` writes — and is how you edit one field without rewriting a manifest; `lm apply -f` takes a `kind: Connector` manifest. Handing a manifest to `connector apply` is refused with the command that opens. **`delete` erases the connector's data**: its mirror tables (`connector.t_*` and registered REST `ext.*`) are dropped, registry rows removed, and every RAG chunk it produced dies with it — this is the [erasure gesture](../security/gdpr.md#the-rights-in-practice). A one-shot's `ext.*` table goes the same way. A table a fused entity depends on blocks the delete with a `409` naming the entity — `lm entity delete NAME` frees it |
| `lm connector run NAME` | trigger an immediate run |
| `lm connector pause/resume NAME` | stop/restart scheduling |
| `lm connector scope ls/set/add/rm/sync` | manage the connector's métier scopes; `sync` re-stamps its already-indexed chunks |
| `lm endpoint enable/disable/reset-cursor/purge C/E` | per-endpoint scheduler + cursor; `purge` deletes the endpoint's `ext.*` or `connector.<hybrid>` rows (`--force` skips the prompt); Search must be rebuilt afterwards for a `connector`-schema hybrid target — see [Rows deleted directly in the database](../search/search-indexes.md#rows-deleted-directly-in-the-database) |
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
| `lm conflicts list` | fused-entity rows with a source conflict or a lookup ambiguity (`--limit N`) |
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
| `lm ask [--scope S] "question"` | analytic text-to-SQL over the fused hub — prints the answer, a `data as of` line (the scope's last completed run) **and the SQL that ran** ([the five guards](../transparency/ask-honesty.md) apply). When the answer filtered on a value a source disagrees about, a `disputed — a source disagrees on a value this answer filtered on:` block follows the `data as of` line, before the SQL: one line per record (`key  column = 'value'  counted\|not counted  src: value · src: value`), then `… and more — lm conflicts list` past ten entries ([when it appears](../transparency/ask-honesty.md#disputed-rows-a-disclosure-not-a-veto)); a [scope-bound](../rag/scopes.md#binding-users-to-scopes-opt-in) user must name a granted scope or gets `403 scope not granted` — without `--scope`, asks your default (`lm scope set TAG`; same 403 if not granted) |
| `lm chat` | interactive REPL chat — in-REPL commands: `:corpus NAME` (or `all`), `:scope TAG` (or `default`), `:topK N` (1 to 50), `:clear`, `:help`, `:exit` |
| `lm scopes` / `lm scope` / `lm scope set TAG` | list scopes (a scope-bound user sees only their granted métiers); show/set your default (`404 no user record on this hub…` means your login points at a hub user id that does not exist — [Tenants, users & scopes](../security/identity-admin.md)) |
| `lm rag search/status/reindex` | semantic search (`--top-k N`, 1 to 50, default 5); corpus status; re-index of the **platform corpus** (manifests/API/hooks — ingested-row chunks are written at ingestion and erased with their connector) |

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
| `lm app list` | the declared apps — name, title, scope, icon, theme, default and tabs; `-o json` / `-o yaml` give every field the hub sends ([see it](../apps/pwa.md#see-what-is-declared)) |
| `lm workflow apply -f FILE` | declare a [`kind: Workflow`](../apps/workflows.md) lifecycle; prints the data-truth report — values in the column the manifest never declared, and declared states never seen. Never blocks |
| `lm proposal draft DOSSIER` | the start of a `kind: Workflow` from a proposal dossier: the dossier's points listed in the order that needs you first, the flags filling `spec.on.*` and `spec.dormancy.after`, every field you leave out left out of the file with a comment. Contacts no hub and never applies; the states graph is yours to write. `-o FILE` refuses an existing file |
| `lm workflow list` | the tenant's declared lifecycles (name, table, column, scope) |
| `lm workflow get NAME` | fetch one workflow's manifest YAML to stdout (redirect with `>` to save; the global `-o` here means output *format*, it does not write a file) |
| `lm export apply -f FILE` | declare a [`kind: Export`](../apps/exports.md) — entity, columns, format, destination, optional schedule; prints the stored name, entity and scope (`-o json` for scripts). Inline credentials are refused by the hub, naming the field only |
| `lm export list` / `lm export get NAME` | the exports your scopes can see; one export's stored manifest YAML (a URL's `user:password@` is printed as `***@`) |
| `lm export run NAME [--wait]` | start a managed run. Without `--wait` it answers once the hub has **accepted** the run (`Running`, or `Queued` with the run queue on) — not delivered. `--wait` follows the ledger to `Completed` (prints the receipt) or `Failed` (exit 1 with the hub's `failureCode` and reason); `--timeout` (default 30m) and an interrupt stop the waiting, never the run |
| `lm export retry NAME RUN_ID [--wait]` | a new attempt of a `Failed` run — same artifact name, regenerated from byte 0 |
| `lm export runs NAME [--limit N]` / `lm export status NAME [RUN_ID]` | the export's run ledger, newest first; one run in full — its latest when no id is given. Short ids from `runs` are accepted |
| `lm inbox [--limit N] [--all]` | recent `decision.*` events — [what needs you](../apps/inbox.md) |
| `lm inbox ack ID` | dispose of a decision (idempotent — the ack is an event) |

## Admin & platform

| Command | What it does |
|---|---|
| `lm module list` | installed business modules |
| `lm migration status/apply` | Flyway status per schema (`apply --schema core\|identity\|…` — required) |
| `lm hook list/get/apply/delete` · `reload NAME` | Kotlin platform hooks (`hook generate` is a v1.x stub) |
| `lm search apply -f FILE` | declare a [`kind: SearchIndex`](../search/search-indexes.md) — validate, then create or update; `-f -` reads stdin. Prints the index, the entity it indexes and its validity (`-o json\|yaml` prints the hub's body). Integrator-only; refusals (`400` validation, `403 scope not granted`, `409` entity changed mid-apply or already indexed by another SearchIndex — one per Entity) reach you verbatim. Applying with no Search provider installed stores the declaration and starts no backend work |
| `lm search rm NAME` | delete a declared index — the hub records the purge of its already-projected documents in the same transaction, so the purge outlives the declaration (see [Deleting](../search/search-indexes.md#deleting-and-the-purge-it-leaves-behind)). `404` if the name is unknown, `403 scope not granted` if you do not hold every scope of its entity. The purge is asynchronous: the index can keep answering for a while after `rm` returns |
| `lm search query "TEXT" [--entity E] [--facet field=value]… [--limit N] [--cursor C]` | search a declared [`kind: SearchIndex`](../search/search-indexes.md) **by keyword** — the [full guide](../search/querying.md) with real screens — deterministic, no model (for a *question* — counts, aggregates — use `lm ask`; to *find records*, use this). One argument (quoted when it has spaces — the quotes are for your shell), no operators and no phrase search; `--facet` narrows on a declared facet field and repeats. Prints one row per hit (key, score, the matched fields first with the matched words in `*stars*`), the facets of the whole match, `as of` and, when there is a next page, the exact command that fetches it. `-o json\|yaml` prints the hub's body (diagnoses go to stderr, so stdout stays clean). `lm_user`, bounded by the entity's scopes: an unknown or out-of-scope `--entity` is the same `403 scope not granted`. **Three situations are told apart from "no results":** `search is not installed` (no provider is built into the hub — `up.sh --search`/`--search-cluster`), `search is degraded` (with its reason and when to retry), and, on zero hits, *no SearchIndex is declared over the entity* / *the index is invalid* (with its reason) / *declared but not built yet* or *being built* (the last two need the integrator role, which owns the status door). A cursor is not a snapshot: after a rebuild the hub refuses it |
| `lm search status [INDEX]` | every scope-visible [`kind: SearchIndex`](../search/search-indexes.md) (name, status, lag, document count, last rebuild), or one index's full detail (live generation, active run, last error, invalid reason) |
| `lm search rebuild INDEX [--wait]` | trigger a full rebuild of an index — without `--wait` it answers once the hub has **claimed** the run (prints the run id), not once it has finished; `--wait` follows the run to its terminal outcome (`ready`/`failed`/`cancelled`), same `--timeout` (default 30m) and interrupt-never-cancels posture as `lm export run --wait`; the way to make Search forget rows deleted directly in the database — see [Rows deleted directly in the database](../search/search-indexes.md#rows-deleted-directly-in-the-database) |
| `lm search cancel INDEX` | request cooperative cancellation of an index's active rebuild — idempotent while the run is still active, refused with `rebuild already past its cancellable point` once it has reached publish |

## The TUI

Bare `lm` (or `lm ui`) launches the k9s-style TUI. `:` opens the command palette.

Full tour by task, plus the complete key reference, on [The TUI](tui.md).
