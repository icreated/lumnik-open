# `lm` — CLI & TUI reference

`lm` is the integrator's single binary: a kubectl-style CLI plus a k9s-style TUI. This page maps
the whole surface; every command also documents itself with `lm <cmd> --help`.

Every command takes the global flags:

```
$ lm --help
…
Flags:
      --context string   Override the current context from ~/.lm/config.yaml
  -h, --help             help for lm
  -o, --output string    Output format: table|json|yaml (default "table")
      --server string    Override the server URL
      --token string     Override the auth token (sent as-is; the context's refresh is not run)
  -v, --verbose          Verbose output (debug logs)
```

- `--token` skips the context's stored session and its refresh, so a token from elsewhere works even
  when the stored one has expired.
- Five commands use `-o` for an output **file** instead: `lm source rest from-openapi`, `lm view get`,
  `lm view generate`, `lm entity export` and `lm proposal draft`.

A command that only groups others (`lm connector`, `lm entity`, `lm module`…) prints its subcommands
when typed alone. A word that is not one of them is an error with a non-zero exit:

```
$ lm connector lst; echo "exit=$?"
Error: unknown command "lst" for "lm connector"
exit=1
```

## Context & auth

| Command | What it does |
|---|---|
| `lm config set-context NAME --server URL --oidc-issuer URL` | create/update a context (`--oidc-client-id` defaults to `lm-cli`, the bundled realm client) |
| `lm config use-context NAME` | switch context (the default for every later command; inside `lm ui`, `:context` switches the session only) |
| `lm config view` | print `~/.lm/config.yaml` with each credential shown as `REDACTED` — safe to paste into a ticket; `--raw` prints the tokens in clear |
| `lm login` | OIDC **device flow**: prints a URL + code, stores tokens in the context |
| `lm version` | binary version |

First run? The [deploy page](../deploy.md) walks through standing up a hub and pointing `lm` at it.

### Environment & TLS

| Setting | Where | Effect |
|---|---|---|
| `LUMNIK_URL` | env var | replaces the current context's server, per run |
| `LUMNIK_TOKEN` | env var | the Bearer token, per run — for CI. Used only when the context holds no `lm login` session; `--token` always wins |
| `ca-file: /path/to/root.crt` | `~/.lm/config.yaml`, by hand | trust an internal CA (the TLS proxy's LAN certificate) |
| `insecure-skip-verify: true` | `~/.lm/config.yaml`, by hand | disable TLS verification; `ca-file` wins when both are set |
| `tenant:` / `user:` | `~/.lm/config.yaml` | dev-bypass hubs only; a real hub ignores them |

The env vars need an existing current context: create it once with
`lm config set-context ci --server … && lm config use-context ci`.

## Manifests

| Command | What it does |
|---|---|
| `lm validate -f FILE` | validate a `kind: Connector` manifest without applying it — field-by-field errors, exit code 1 on error. Other kinds are checked by their own `apply` |
| `lm apply -f FILE` | apply a `kind: Connector` manifest — upserts by `metadata.name`, so re-applying an edited file updates it. Prints `Applied connector "NAME" — 1 endpoint`; `-o json` prints the hub's answer, the connector id included. Not `lm connector apply`, which takes the flat stored fields |
| `lm entity apply -f FILE` | apply a `kind: Entity` fusion manifest |
| `lm entity delete NAME` | remove an entity and its view (`lm_admin`). Names the view and the search indexes over it, then asks you to type `delete` (`--force` skips the question). This is what frees a connector blocked by the `409` below |
| `lm entity list` | the entities you may read — name, sources, scopes, `match` or `resolution` |
| `lm entity get NAME [--filter COLUMN=VALUE]… [--limit N] [--offset N]` | one page of an entity's canonical rows ([Read API](../entities.md#read-api)). `--filter` is an exact match — a date on a [date column](../entities-types.md) is equal as an instant — repeatable; `--limit` is 1..1000 (default 100); a full page is announced on stderr |
| `lm entity resolve NAME` | resolve a [`resolution:` entity](../entities-resolution.md) over every row now (`lm_admin`); prints what was resolved, ambiguous and unresolved |
| `lm entity ambiguous NAME [--limit N] [--offset N]` | the rows a `resolution:` entity could not decide, with their candidates |
| `lm entity history NAME SOURCE ID` | every decision about one source row: outcome, rule, candidates, `automatic` or `asserted` |
| `lm entity assert-same\|assert-distinct NAME --left SOURCE:ID --right SOURCE:ID [--reason TEXT]` | a correction that outranks every rule, applied at the next resolve (`lm_admin`) |
| `lm entity export NAME -o FILE [--force]` | download the entity's canonical CSV through the [one-shot door](../apps/exports.md#managed-export-vs-one-shot-download). An existing file is refused without `--force`; `-o -` streams to stdout |
| `lm app apply -f FILE` | declare a [`kind: App`](../apps/pwa.md) business app — title, scope, icon and theme |
| `lm app list` / `lm app get NAME` | the declared apps; one app as an applyable manifest (`lm app get NAME > app.yaml`, edit, re-apply) |
| `lm app rm NAME` | delete a declared app for good (no prompt); the last default app is refused |
| `lm view apply -f FILE` / `lm view list` / `lm view get NAME` / `lm view rm NAME` | saved [`kind: View`](../apps/views.md) manifests: store, list, fetch, delete (no prompt) |
| `lm process apply -f FILE` / `lm process list` | apply/list `kind: Process` notify rules |
| `lm process enable/disable NAME` | start or stop a process firing — the manifest stays stored |
| `lm process rm NAME` | delete a process for good (no prompt) |
| `lm workflow apply -f FILE` | apply a [`kind: Workflow`](../apps/workflows.md) lifecycle — prints the data-truth report |
| `lm workflow list` / `lm workflow get NAME` | declared lifecycles (name, table, column, scope, enabled); one manifest's YAML on stdout |
| `lm workflow enable/disable NAME` | serve a workflow again, or stop serving it — the manifest stays stored |
| `lm workflow rm NAME` | delete a workflow for good (no prompt) |
| `lm export apply -f FILE` | declare a [`kind: Export`](../apps/exports.md) |
| `lm proposal draft DOSSIER` | write a draft [`kind: Workflow`](../apps/proposal-dossier.md#draft-a-workflow-from-a-dossier) from a proposal dossier (`-` for stdin); flags `--name --scope --table --column --key --after -o`. Offline, never applies; `-o FILE` refuses an existing file |
| `lm describe connector-type KIND` | field reference for a connector kind |

```
$ lm validate -f docs/apps/demo/entities/10-customer.yaml
✗ 10-customer.yaml — 4 error(s), 0 warning(s)

  ERROR    apiVersion
          apiVersion is required
          → Use: apiVersion: connectors.lumnik.io/v1

  ERROR    kind
          kind must be "Connector"
…
$ lm workflow disable acme-order-lifecycle
workflow acme-order-lifecycle enabled=false
$ lm app rm clients
Error: API error 400: cannot remove the last default app — apply a different app with default: true first, which demotes this one automatically
$ lm app rm lumilait
app lumilait deleted
```

## Connectors, endpoints, sources

| Command | What it does |
|---|---|
| `lm connector list` / `get NAME` | the connector instances; one connector's flat stored fields |
| `lm connector apply -f FILE` | edit a connector from the file `lm connector get` prints — [Editing a connector's stored fields](#editing-a-connectors-stored-fields) |
| `lm connector delete NAME` | **erases the connector's data**: tables, run history, RAG chunks ([erasure](../security/gdpr.md#the-rights-in-practice)). Says what goes, then asks you to type `delete`. `--dry-run` stops there; `--force` skips the question. A table an entity reads blocks it (capture below) |
| `lm connector run NAME` | trigger an immediate run |
| `lm connector pause/resume NAME` | stop/restart scheduling; `lm connector list` shows a paused connector's cron followed by `(paused)`, and `lm connector run` still runs it |
| `lm connector scope ls/set/add/rm/sync` | manage the connector's business scopes; `sync` re-stamps its already-indexed chunks |
| `lm endpoint enable/disable/reset-cursor C/E` | per-endpoint scheduler and cursor |
| `lm endpoint purge C/E` | delete one endpoint's rows after stating how many and asking you to type `purge` (`--dry-run` only counts, `--force` skips the question). For `rest-generic` endpoints and one-shot `lm csv ingest` tables; refused for `jdbc-generic`, `csv-file` and `kafka` — see [Emptying one endpoint](../connectors/jdbc.md#emptying-one-endpoint-on-its-own) |
| `lm source rest test C` | pre-flight every enabled endpoint — the request a run would send, nothing written; non-zero exit if one fails |
| `lm source rest probe C --endpoint PATH` | sample one path with the connector's connection and credential; drafts the endpoint fragment to paste under `spec.endpoints` |
| `lm source rest from-openapi URL --name N` | generate a manifest (`--scope` repeatable, `-o FILE`, `--apply` upserts it) |
| `lm source jdbc test/discover/add` | open a JDBC source directly; the password is prompted without echo, or piped with `--password-stdin`. There is no `--password` |
| `lm source jdbc list-tables NAME` | the tables registered under a source the hub already holds |
| `lm source schema --scope TAG` / `lm source rediscover --scope TAG` | show / rebuild the scope's schema card (`--scope` is required) |
| `lm csv ingest FILE\|sftp://…\|s3://…` | one-shot CSV ingest into an `ext.*` table, no run recorded — flags on [CSV & files](../connectors/csv-quick-look.md). `lm source csv ingest` is the same command |

```
$ lm connector delete orders --dry-run
Would delete connector orders (csv-file) — 8 rows of connector.t_orders, its run history and RAG chunks (dry run, nothing deleted)
$ lm connector delete acme-desk-erp --dry-run
Error: API error 409: table connector.t_acme_desk_erp feeds entity acmecustomer — remove the entity first (lm entity delete acmecustomer)
```

A one-shot ingest:

```
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

With the AI off, the `askable` line is replaced by: `A one-shot: no run recorded, nothing indexed for chat.`

### Editing a connector's stored fields

`lm connector get NAME` prints the connector as the hub stores it. Edit that file and apply it back:

```bash
lm connector get erp-clients > c.yaml     # keep the id: line, it names the connector to update
lm connector apply -f c.yaml
```

- Create a connector with `lm apply -f` and its `kind: Connector` manifest. `lm connector apply`
  creates only `csv-file` and `hello-world`; for another type it says to use `lm apply -f`.
- A file without `id:` under a name already taken is refused, naming the id to add.

## Data, runs & answers

| Command | What it does |
|---|---|
| `lm run list` / `lm run get ID` | connector runs, **one row per endpoint** (records in/out/skipped); `list` is newest first, `--connector NAME`, `--limit N`. An `EVALUATED` column appears once the tenant has an enabled [Process](../apps/processes.md): whether it has evaluated the rows the run wrote. `get --wait-evaluated [--timeout D] [--interval D]` waits for it, and exits 1 when the hub cannot say (no enabled Process, processes changed since the run started, or a run from before the hub recorded it) |
| `lm run cancel ID` | stop a run. A `Running` run stops at its next chunk; a `Queued` one ends at once. Refusals: [Stopping a run](tui.md#stopping-a-run) |
| `lm dlq list` / `lm dlq get ID` | dead-letter entries (reason + offending row); `--run RUN`, `--connector NAME`, `list --limit N` |
| `lm dlq replayed ID --new-run-id RUN` / `lm dlq discard ID` | close an entry: carried again by that run, or dropped — [the rules](../recipes/diagnose-a-run.md) |
| `lm conflicts list` | fused-entity rows with a source conflict or a lookup ambiguity (`--limit N`) |
| `lm observations list` | the workflow tape: watched documents, stillest first (`--limit N`); `--workflow NAME` narrows the hub's read to one workflow |
| `lm observations transitions` | the journal: each observed move, newest first, with the seconds spent in the state it left (`IN_FROM_S`, counted from the first observation for a document's first move); `--workflow NAME`, `--doc KEY`, `--limit N` |
| `lm observations stats` | the tape per workflow: watched, dormant, median detection delay (seconds; `-` when nothing is dormant) |
| `lm integration add NAME --url U --secret-stdin [--events T]` | register a webhook target. The signing secret is required and read on stdin. `--events` defaults to `decision.*`; `--platform` is a label only |
| `lm integration list` / `describe ID` | registered targets; the table shows scheme and host only, `-o json\|yaml` the full URL |
| `lm integration test ID [--event T]` | fire a catalog event at the target and report the delivery; `decision.*` events cannot be fired here |
| `lm integration rm ID` | remove a target — delivery history is kept |
| `lm notify add --events T --to EMAIL [--template N]` / `list` / `rm ID` | email rules (needs the deploy's SMTP settings — see the self-host guide) |
| `lm outputs list` | what was sent out and whether it arrived, failures first (`--limit N`) |
| `lm outputs replay ID` | re-send one failed delivery as a new attempt |
| `lm data tables` / `lm data row HASH` | dynamic tables with your row count (`unknown` when it could not be counted); the raw row behind a citation, a timestamp as its UTC instant |
| `lm secret set/list/describe/rm` | encrypted secrets; values never returned. `set NAME` prompts without echo, `set NAME -` reads stdin, `set NAME VALUE` warns (shell history). `--description`, `rm --force`. `rm` does not check references: a connector naming the secret fails at its next run |

The two tables you will read most:

```
$ lm run list --connector orders
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
884f487d  orders     default   Completed  10  8    2        2             2026-10-19 01:38  2026-10-19 01:38
$ lm dlq list --run 884f487d
ID        CONNECTOR  RUN       REASON           ATTEMPTS  MESSAGE
b6bc9601  orders     884f487d  MalformedSource  1         /tmp/exports/orders.csv:9 — Record has 2 values but 5 column…
b4176d39  orders     884f487d  MalformedSource  1         /tmp/exports/orders.csv:8 — Record has 6 values but only 5 c…
```

!!! tip "The ID columns are abbreviated — and the abbreviation works"

    `lm run list` and `lm dlq list` show the first 8 characters of each id. `get`, `discard`,
    `replayed` and `--run` accept those 8 characters; an ambiguous prefix is refused. `-o yaml`
    prints every id in full.

!!! tip "A full page says so"

    Lists answer in pages. Each takes `--limit N`, up to a cap:

    | Command | Default | Cap |
    |---|---|---|
    | `lm run list` | 50 | 200 |
    | `lm dlq list` | 100 | 200 |
    | `lm inbox` | 50 | 200 |
    | `lm export runs NAME` | 20 | 200 |
    | `lm outputs list` | 200 | 500 |
    | `lm conflicts list` | 200 | 1000 |
    | `lm observations list` / `transitions` | 200 | 1000 |
    | `lm entity get NAME` / `lm entity ambiguous NAME` | 100 | 1000 |

    A full page prints `note: result hit the limit of N — there may be more` on **stderr**, so pipes
    and `-o json` stay clean. Raise `--limit` to see further back. `--connector` filters on the hub,
    so a quiet connector's old runs are still found.

## Ask your data

!!! info "lumnik Pro"
    `lm ask`, `lm chat`, `lm rag` and `lm scopes` need the AI. On lumnik Open, or with the AI off,
    they answer `Error: API error 404 (no details from the server)`.

| Command | What it does |
|---|---|
| `lm ask [--scope S] [--lang XX] [--previous "question"] "question"` | analytic text-to-SQL over the fused hub: the answer, a `data as of` line, any [disputed rows](../transparency/ask-rules.md#disputed-rows-a-disclosure-not-a-veto), the rows the query returned and **the SQL that ran** ([the guards](../transparency/ask-honesty.md)). `--lang` sets the answer language (default: from `LC_ALL`, `LC_MESSAGES` or `LANG`). Without `--scope`, asks your default scope. `--previous` names the question this one continues: "Et SUP-004 ?" is answered as the full question, shown on an `understood as` line. `-o json\|yaml` for a script |
| `lm chat` | interactive REPL chat — in-REPL commands: `:corpus NAME` (or `all`), `:scope TAG` (or `default`), `:topK N` (1 to 50), `:clear`, `:help`, `:exit` |
| `lm scopes` / `lm scope` / `lm scope set TAG` | list scopes (a scope-bound user sees only granted ones); show/set your default (`404 no user record on this hub…`: see [Tenants, users & scopes](../security/identity-admin.md)) |
| `lm rag search/status/reindex` | semantic search (`--top-k N`, 1 to 50, default 5); corpus status; re-index of the platform corpus (manifests, API, hooks) |

### The rows under the answer

`lm ask` prints the rows its query returned under the answer, then how many there are, then the
SQL. When the hub withholds a phrased answer, these rows are the answer. Played on the
[demo desk](../apps/demo-desk.md):

```
$ lm ask --scope desk "Quels clients GOLD sont à Lyon ? Donne leur code et leur nom."
Il y a 2 clients GOLD à Lyon :

- Code : P-1000, Nom : Hydraulique Lyon 1000
- Code : P-1008, Nom : Filtration Lyon 1008

Total de clients : 2
  data as of: 2026-10-16T08:50:37.235585Z (last completed run)

  disputed — a source disagrees on a value this answer filtered on:
    P-1008  city = 'Lyon'  counted  t_acme_desk_erp: Lyon · t_acme_desk_crm: Villeurbanne

  CODE    NAME
  P-1000  Hydraulique Lyon 1000
  P-1008  Filtration Lyon 1008
  2 rows returned

  SQL: SELECT v.code, v.name
FROM connector."v_acmecustomer" v
WHERE v.segment = 'GOLD' AND v.city = 'Lyon'
LIMIT 500

  try: lm conflicts list  — where this scope has source conflicts or lookup ambiguities, row by row
```

- The query stops at 500 rows; every row returned is printed. A line break inside a value shows as `\n`.
- `no rows returned` when the query found nothing; no table when the answer ran no query.

For a script, ask for JSON (or `-o yaml`): one document, nothing else on stdout.

```
$ lm ask -o json --scope desk "Quels clients GOLD sont à Lyon ? Donne leur code et leur nom." | jq -r '.rows[] | .code + "  " + .name'
P-1000  Hydraulique Lyon 1000
P-1008  Filtration Lyon 1008
```

| Field | Holds |
|---|---|
| `answer` | the sentence |
| `abstained` | `true` for a refusal. Test this, never the wording of `answer` |
| `sql`, `columns`, `rows`, `rowCount` | the query and what it returned; numbers stay numbers |
| `dataAsOf` | the last run that fed the scope |
| `understoodAs` | the full question, when `--previous` was used |
| `disputed`, `disputedTruncated` | the disputed rows, and whether the list was cut |
| `otherDoors` | the `try:` suggestions printed under the answer |

## The apps ladder

| Command | What it does |
|---|---|
| `lm view -f FILE [--filter …]` | render a [`kind: View`](../apps/views.md) list — filters `col=v` · `col~text` · `col>v` (`>= < <=`, number/ISO date), repeatable (AND). The value is a bare literal: `--filter 'city=Lyon'`; `city='Lyon'` is refused |
| `lm view KEY -f FILE` | one record's detail (with the `_conflicts` ledger) |
| `lm view NAME [KEY] [--filter …]` | the same for a **saved** view of `lm view list`. A view named like a subcommand (`list`, `get`, `apply`, `generate`, `rm`) runs the subcommand instead |
| `lm view generate SCOPE "DESCRIPTION" [-o FILE]` | ask the AI to write a `kind: View` manifest over a scope; review it, then render with `lm view -f` |
| `lm view list [--scope S]` | the saved views you may read |
| `lm view get NAME [-o FILE]` | one saved view's manifest YAML — to stdout, or to a file |
| `lm view apply -f FILE` / `lm view rm NAME` | store a `kind: View` manifest; delete one (no prompt) |
| `lm app apply -f FILE` / `list` / `get NAME` / `rm NAME` | business apps — see [Manifests](#manifests) |
| `lm workflow …` | lifecycles — see [Manifests](#manifests) |
| `lm export apply -f FILE` | declare a [`kind: Export`](../apps/exports.md); inline credentials are refused, naming the field |
| `lm export list` / `lm export get NAME` | the exports your scopes can see; one export's manifest YAML (`user:password@` printed as `***@`) |
| `lm export run NAME [--wait]` | start a managed run. Without `--wait` it returns once the run is **accepted**; `--wait` follows it to `Completed` (prints the receipt) or `Failed` (exit 1). `--timeout` (default 30m) and an interrupt stop the waiting, never the run |
| `lm export retry NAME RUN_ID [--wait]` | a new attempt of a `Failed` run, regenerated from byte 0 |
| `lm export runs NAME [--limit N]` / `lm export status NAME [RUN_ID]` | the export's run ledger, newest first; one run in full (latest by default) |
| `lm inbox [--limit N] [--all] [--process NAME] [--type TYPE]` | recent `decision.*` events — [what needs you](../apps/inbox.md); `--process`/`--type` narrow the hub's read |
| `lm inbox ack ID` | dispose of a decision (idempotent) |

## Admin & platform

| Command | What it does |
|---|---|
| `lm module list` | installed business modules, with `ACTIVE`/`INACTIVE` and the reason |
| `lm migration status` | migration status per schema: version, pending count, last applied (`—` when none). No `apply`: the hub migrates at every startup — [capture](../operations/upgrade.md#the-short-version) |
| `lm hook list` / `lm hook get NAME` | the Kotlin platform hooks loaded at startup |
| `lm hook scripts` | the Kotlin scripts a connector manifest can name as `script: hooks:<name>.kts`: name, size, SHA-256 |

## Search

| Command | What it does |
|---|---|
| `lm search apply -f FILE` | declare a [`kind: SearchIndex`](../search/search-indexes.md) — validate, then create or update; `-f -` reads stdin (integrator) |
| `lm search rm NAME` | delete a declared index and queue the purge of its documents ([Deleting](../search/search-indexes.md#deleting-and-the-purge-it-leaves-behind)); asks you to type `delete` (`--force` skips the question; in a script without it: `aborted: no confirmation read`) |
| `lm search query "TEXT" [--entity E] [--facet field=value]… [--limit N] [--cursor C]` | keyword search over the declared indexes, no model — the [full guide](../search/querying.md) |
| `lm search status [INDEX]` | every index (name, status, lag, documents, last rebuild), or one index's detail |
| `lm search rebuild INDEX [--wait]` | full rebuild; without `--wait` it prints the run id once claimed, `--wait` follows it to `ready`/`failed`/`cancelled` (`--timeout`, default 30m) |
| `lm search cancel INDEX` | request cancellation of an index's active rebuild; refused once it has reached publish |


## The TUI

Bare `lm` (or `lm ui`) opens the TUI: [The TUI](tui.md).
