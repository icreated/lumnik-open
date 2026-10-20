# The TUI

> Bare `lm` (or `lm ui`) opens the k9s-style console — the installer's daily screen. `:` is the
> command palette (type a page name, Tab/Enter to commit); `?` opens the in-app help; `q` quits.

This page tours the TUI by task: watch an ingestion, decide on a pending item, ask your data,
create something new. The [key reference](#key-reference) at the bottom lists every gesture.

## Getting around

`:` opens the palette — type the start of a page name (`:so` offers `:sources`), then Tab or Enter.

| Group | Pages |
|---|---|
| Your data | `:sources` (alias `:connectors`) `:endpoints` `:runs` `:dlq` `:tables` `:entities` `:conflicts` `:ambiguous` |
| Decide | `:inbox` `:outputs` `:observations` |
| Explore & ask | `:ask` `:view` `:scopes` `:search` |
| Create & declare | `:new` `:processes` `:views` `:apps` `:workflows` `:exports` |
| Admin | `:modules` `:hooks` `:migrations` `:rag` `:secrets` `:integrations` `:notify` |

Global keys, from any page:

| Key | Effect |
|---|---|
| `:` | open the command palette |
| `r` | refresh the current page |
| `d` | toggle the describe panel (full detail on the selected row) |
| `Tab` | with the describe panel open: give it the keys, so `↑` `↓` `PgUp` `PgDn` scroll a long record; `Tab` or `Esc` gives them back to the list |
| `/` | on list pages: filter the table to the rows holding a text (`Esc` clears) — the [key reference](#key-reference) lists the pages |
| `y` | yank the current row — copies it to your local clipboard, even over SSH |
| `q` | quit |
| `?` | help screen (the same table, always at hand) |
| `Esc` | close a modal, cancel an input, or back out one level |

- `:sources`, `:endpoints`, `:runs`, `:dlq` and `:inbox` live-update; the cursor stays on your row.
- The header names the hub (context and host) and the event stream: `[CONNECTING]`, `[LIVE✓]`, or
  `[RECONNECTING]` with the reason in the footer.
- A key that acts on the hub (run, toggle, delete, apply) is ignored while its first press still
  waits on the hub; the footer says it is in progress.

**Another hub.** `:context` (or `:ctx`) lists the contexts of `~/.lm/config.yaml`, the current one
marked `●`. `Enter` switches for this session only: every page is rebuilt on the chosen hub, and
`lm config use-context` still sets the default for the next command. The session scope starts over
on the new hub, and the `--server`/`--token` flags `lm ui` was started with stay behind.

**Session expired.** If your login expires mid-session, a **session-expired** modal opens. `L` logs
in again with the same device flow as `lm login` and the TUI resumes; `Esc` dismisses it until the
next request needs the session.

## Watch an ingestion

`:sources` is the home page — one row per connector instance.

| Column / key | Where | What it tells you |
|---|---|---|
| `SCHED` | `:sources` | the connector's schedule |
| `ENABLED` | `:sources` | whether the connector is on at all |
| `Enter` | `:sources` | REST or JDBC source → its `:endpoints` (one per spec entry / per table); any other kind → its `:runs` |
| `SCHEDULE`, `LAST_RUN`, `STATUS` | `:endpoints` | per endpoint — for a REST source, where to check "did it even fire?" |
| `Enter` | `:endpoints` | that endpoint's own `:runs` (`Esc` clears it) |
| `T` / `P` | `:endpoints` | test the endpoint — the request a run would send, nothing written · probe a path of the API and draft its endpoint fragment |
| `^P` / `C` / `Del` | `:endpoints` | enable/disable the endpoint · reset its cursor — the next run re-reads from the start (asks first) · purge the rows it landed |
| `D` | `:sources`, `:runs` | the connector's / the run's dead letters in `:dlq` — `Esc` there clears the filter |
| `at:` (under `── last run ──`) | `d` describe panel | the exact last-run time |

`Del` on an endpoint asks the hub first. A `jdbc-generic`, `csv-file` or `kafka` endpoint shows the
hub's refusal; an endpoint with no rows says there is nothing to purge; otherwise the question names
the count.

In `:sources`:

- `Del` asks the hub what the delete would take. A table an entity reads shows the refusal naming
  the entity; otherwise the confirmation names each table and its rows:
  `Delete connector "cust-csv"? Goes with it: 3 rows of connector.t_cust_csv, its run history and RAG chunks.`
- `t` edits the connector's tags, then offers to re-stamp its indexed RAG chunks
  (`lm connector scope sync`). `Re-stamp` says how many chunks changed; `Cancel` leaves the old scopes on them.

**Reading a run.** `:runs` shows `CONNECTOR`, `ENDPOINT`, `STATUS`, `STARTED`, `DURATION`,
`RECORDS` (in/out/skip) and `TRIGGER` — one row per endpoint. `d` adds the run id, the chunk count
and, for a failed or cancelled run, an `error:` section with the full message.

**Stopping a run.** Select a `Running` row and press `c`. The chunk in flight finishes and the run
ends at the next chunk boundary. On a `Queued` row, `c` ends the run at once (`it had not started`).

| Refusal toasted | Meaning |
|---|---|
| `run … already ended (STATUS)` | nothing left to stop |
| `run … is being started; try again in a moment` | a `Queued` run is starting — retry |
| `run … is not in flight on this hub` | the run started a moment ago — retry; refused again, no process runs it, and the hub marks it `Failed` on its own |

`STATUS` takes six values. Check them in this order when a run's outcome isn't obvious:

| What you see | What happened | Where the detail lives |
|---|---|---|
| `STATUS` = `Queued` | Accepted, waiting for a free slot — only with the [run queue](../connectors/index.md) on | `:runs`; it starts on its own |
| `STATUS` = `Running` | Still going | `:runs` live-updates; `d` shows progress so far |
| `STATUS` = `Failed` | The run itself failed: source unreachable, bad credentials, a refused config | The `error:` section in `d` |
| `STATUS` = `Completed`, a row is in `:dlq` | A quality failure on that one row (e.g. `validate-row`) | `:dlq` — reason and payload |
| `STATUS` = `Completed`, row counted in `skip` but not in `:dlq` | Excluded by a declared filter (`filter-where`, `skip-empty-rows`), not rejected | The `skip` figure in `RECORDS` |
| `STATUS` = `Partial` | Read less than the source held when it started; nothing written twice, the next run resumes from the kept position | The `error:` section in `d` names the target and what was not reached |
| `STATUS` = `Cancelled` | Cancelled by a user, or skipped because another run for the endpoint was already active | `d`: `cancelled by user` or `skipped: another run is already active for this endpoint` |
| No entry in `:runs` at all | The schedule hasn't fired yet | `:sources` / `:endpoints` |

Every dead letter is also counted in `skip`, so `in/out/skip` alone tells you whether a run dropped
anything.

Once you have the cause, fix it (`e` edits the connector's YAML) and press `R` to run again. The
full walk is [Diagnosing a failed run](../recipes/diagnose-a-run.md).

`:dlq` is the **dead-letter queue**: each row a connector could not place, with its reason and
payload. `^R` marks one replayed, `^D` discards it.

## Decide

Four pages answer "what needs me":

- **`:inbox`** — your métiers' `decision.*` events, raised by processes and workflows (never run
  failures: those are in `:runs`). `a` acks the selected one (idempotent); `A` shows or hides
  disposed entries.
- **`:outputs`** — what was sent out and whether it arrived, failures first. `^R` replays a failed
  delivery.
- **`:observations`** — the workflow tape: documents that have NOT moved, stillest first. The title
  shows, per workflow, the documents watched, the dormant ones and the median detection time.
- **`:conflicts`** — the fused rows with a source conflict or a lookup ambiguity. Read-only: `d`
  shows which source said what; the fix is a source or manifest change.

## Explore & ask

`:ask` is analytic chat: a plain-language question comes back as generated SQL, rows and an answer.
It asks the session scope shown in the header — your default (`lm scope`) at startup, changed with
`:scopes` → `Enter` or `:scope <tag>`.

| Key | Effect |
|---|---|
| `Enter` | run the question |
| `y` | yank the generated SQL |
| `e` | export the result rows to a CSV file |
| `n` | new question |

`:view` opens a [`kind: View`](../apps/views.md) manifest file and renders it — `Enter` on an empty
path opens the living example.

| Key | Effect |
|---|---|
| `Enter` (on a rendered row) | record detail, with the `_conflicts` ledger |
| `f` | add a server-side filter (a bad one is refused with the reason) |
| `F` | clear all filters |
| `g` | generate a view from a sentence over the active scope (AI) — set the scope first with `:scopes` or `:scope <tag>` |
| `a` | apply the rendered view into the hub's saved-view store |
| `o` | open another file |
| `r` | refresh |

## Create

`:new` is the authoring loop. Pick a kind; every kind you can apply is offered — the connector
types, `Entity`, `Process`, `Workflow`, `App`, `View`, `Export` and `SearchIndex` — each with a real
example from these docs.

| Key | Effect |
|---|---|
| `Enter` | open the kind's example in `$EDITOR`; on save it validates and applies, then jumps to where you observe it |
| `v` | edit, then validate **without** applying; `a` then applies the buffer you checked |
| `e` | after refusals (shown field by field): re-open the *same* buffer |
| `Esc` | abandon — your edits are kept in a temp file |
| `w` | guided setup: `jdbc-generic` (name, JDBC URL, user, password typed masked, schema → test → pick tables → one connector per table) · `csv-file` (ingest a local file once, then opens `:tables`) |
| `d` | the kind's example and, for a connector kind, its type's configuration schema (`lm describe connector-type`) |

- `v` checks a connector through the hub's validator; any other kind by its YAML and `kind` only.
- `graphql` and `soap` show `rest-generic`'s schema: they are request shapes of that type.
- In the JDBC picker, a table of an unsupported shape is listed but cannot be ticked.

`:processes` lists declared [`kind: Process`](../apps/processes.md) rules: `e` re-opens one in the
same edit → validate → apply loop, `^P` enables or disables it (the manifest stays), `Del` deletes
it after a confirmation.

## Admin corner

| Page | What it lists | Keys |
|---|---|---|
| `:modules` | installed business modules | — |
| `:hooks` | installed Kotlin hooks (read-only: they load from deployment manifests at startup) | `d` the binding: handler, events, priority (`lm hook get`) |
| `:migrations` | migration status per schema; a pending one is applied at the hub's next start | — |
| `:rag` | chunks indexed per scope (connectors, last indexed); the title shows the platform corpus | `R` reindex the platform corpus (asks first) |
| `:tables` | the `t_*` tables your connectors landed, with your row count | `Enter` the raw rows in landing order, a timestamp as its UTC instant (`2026-10-19T21:20:04.123456Z`) (`n`/`p` page, `d` whole row, `Esc` back) |
| `:entities` | the entities you may read | see [`:entities` keys](#entities-and-ambiguous) |
| `:ambiguous` | the identities one entity's resolution could not settle | `Enter` type an entity name / load a row's history · `^R` re-run the pass · `d` the row |
| `:views` | the saved views the hub serves the PWA | `Enter` render in `:view` · `e` edit in `:new` · `Del` delete (asks first) |
| `:apps` | the métier apps (title, scope, default, tabs) | `e` edit in `:new` (rebuilt from the app's fields) · `Del` delete (asks first) |
| `:workflows` | the declared document lifecycles | `e` edit in `:new` · `^P` enable/disable · `Del` delete (asks first) |
| `:exports` | `kind: Export` manifests: entity, schedule, destination, last run (integrator role only) | `Enter` its runs · `R` run now · `^R` retry a Failed run · `e` edit in `:new` |
| `:search` | whether this hub has a Search provider, then the declared indexes (status, documents, last rebuild) | `/` query, bound to the session scope · `R` rebuild · `c` cancel the rebuild · `Del` delete (asks first) |
| `:secrets` | the credentials connectors refer to by name (values never shown) | `n` store one (typed masked) · `e` rotate its value · `Del` delete (asks first) |
| `:integrations` | the webhook targets `:outputs` delivers to, with recent delivery health; URLs shown to the host only | `n` register (URL and secret typed masked) · `T` fire a test event · `d` registration and latest deliveries · `Del` remove (asks first; history kept) |
| `:notify` | the email rules | `n` add one (events, recipient, entity filter, template) · `Del` remove (asks first) |
| `:scopes` | the tenant's métier scopes | `Enter` set the active scope |

What the pages show when a feature is absent:

- `:rag` with the AI switched off says so (the module stays installed, its chunks are kept); in an
  Open build it says the module is not in the build.
- `:search` on a hub without a Search provider says *not installed*.
- `:exports` for an `lm_user` says the page needs the integrator role.

### `:entities` and `:ambiguous`

| Key | Effect |
|---|---|
| `Enter` | the entity's canonical rows (no View needed; `Esc` back to the list) |
| `n` / `p` | next / previous page |
| `d` | the whole row, ledger included |
| `a` | the entity's ambiguous identities in `:ambiguous` |
| `Del` | delete the entity (asks first) |
| `m` | mark a row (on a [`resolution:`](../entities-resolution.md) entity) |
| `=` / `!` | assert this row and the marked one are the **same** / **different** entities — asks a reason, then confirms naming both rows |
| `h` | the row's resolution history |
| `^R` | re-run the resolution pass so the assertion takes effect |

## Key reference

| Key | Where | Effect |
|---|---|---|
| `:` `r` `d` `y` `q` `?` `Esc` | global | palette · refresh · describe · yank row · quit · help · close |
| `Tab` | global, describe panel open | scroll the panel (`↑` `↓` `PgUp` `PgDn`) · `Tab` / `Esc` back to the list |
| `:context` | global | switch to another configured hub, for this session (`Enter` switches, `Esc` leaves) |
| `/` | `:sources` `:runs` `:dlq` `:endpoints` `:processes` `:views` `:apps` `:workflows` `:inbox` `:secrets` `:integrations` `:notify` `:exports` | filter to the rows holding a text (any column, case-insensitive); survives the live refresh, `Esc` clears it |
| `s` | `:sources` `:runs` `:dlq` `:conflicts` `:ambiguous` | cycle the sort column |
| `c` | `:runs` | request cancellation of the selected `Running` run |
| `e` / `R` (or `^R`) / `^P` / `Del` / `t` | `:sources` | edit YAML in `$EDITOR` · run now · pause/resume · delete · edit tags (then offers to re-stamp the indexed chunks) |
| `Enter` | `:sources` | REST / JDBC → its `:endpoints`; others → its `:runs` |
| `Enter` / `Esc` | `:endpoints` | the endpoint's `:runs` · back to `:sources` |
| `T` / `P` / `^P` / `C` / `Del` | `:endpoints` | test · probe · enable/disable · reset cursor · purge |
| `^R` / `^D` | `:dlq` | mark replayed, naming the run that carried it (Enter: the connector's latest · `-`: none) · discard |
| `^R` | `:outputs` | replay the selected failed delivery |
| `a` / `A` | `:inbox` | ack the selected decision · show/hide disposed (`[traité]`) |
| `Enter` / `n` `p` / `a` / `Del` / `m` `=` `!` / `h` / `^R` | `:entities` | rows · page · ambiguous · delete · mark, same, distinct · history · re-resolve |
| `Enter` / `^R` | `:ambiguous` | list an entity / load a row's history · re-run the pass |
| `R` | `:rag` | reindex the platform corpus (asks first) |
| `d` | `:hooks` | the hook's binding (`lm hook get`) |
| `n` / `e` / `Del` | `:secrets` | store a secret · rotate its value · delete it (asks first) |
| `n` / `T` / `Del` | `:integrations` | register a target · fire a test event · remove it (asks first) |
| `n` / `Del` | `:notify` | add an email rule · remove it (asks first) |
| `Enter` / `R` / `^R` / `e` | `:exports` | the export's runs · run it now · retry a Failed run · edit the stored manifest |
| `/` / `R` / `c` / `Del` | `:search` | query (scope-bound) · rebuild · cancel the rebuild · delete (asks first) |
| `Enter` / `v` / `a` / `e` / `w` / `d` / `Esc` | `:new` | edit the example · validate only · apply the validated buffer · re-edit after refusals · guided setup · describe · abandon (buffer kept) |
| `e` / `^P` / `Del` | `:processes` `:workflows` | edit the stored manifest · enable/disable · delete (asks first) |
| `Enter` / `f` `F` / `g` / `a` / `o` / `r` | `:view` | record detail · add/clear filters · generate (AI) · apply to the hub · open another file · refresh |
| `Enter` | `:scopes` | set the active scope (used by `:ask`) |
| `Enter` / `y` / `e` / `n` | `:ask` | run · yank the SQL · export rows to CSV · new question |

## See also

- [CLI reference](lm-reference.md) — the same surface, from scripts and CI.
- [Diagnosing a failed run](../recipes/diagnose-a-run.md) — from a failed run to a fix, with the
  dead-letter reasons table.
- [Connectors overview](../connectors/index.md) — what a source, an endpoint and a run are.
- [Inbox](../apps/inbox.md) — the decisions `:inbox` surfaces.
