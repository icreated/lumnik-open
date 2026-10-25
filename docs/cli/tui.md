# The TUI

> Bare `lm` (or `lm ui`) opens the k9s-style console — the installer's daily screen. `:` is the
> command palette (type a page name, Tab/Enter to commit); `?` opens the in-app help; `q` quits.

```
 lumnik 2026 · lm @ selfhost (localhost:18080) · :sources /desk (Esc clears) · scope: system · 00:32:33
╔═════════════════════════════════════════════════ :sources ═════════════════════════════════════════════════╗
║NAME                         TYPE           KIND       TENANT       SCHED       ENABLED       SCOPES        ║
║● acme-desk-crm              csv-file       csv        1                        yes           desk          ║
║● acme-desk-deliveries       csv-file       csv        1                        yes           desk          ║
║● acme-desk-erp              csv-file       csv        1                        yes           desk          ║
║● acme-desk-orders           csv-file       csv        1                        yes           desk          ║
║● due-erp                    csv-file       csv        1                        yes           desk          ║
║● due-partner                csv-file       csv        1                        yes           desk          ║
║● stage-deliveries           csv-file       csv        1                        yes           desk          ║
║● stage-orders               csv-file       csv        1                        yes           desk          ║
╚════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
 e: edit  t: scopes  d: describe  D: dead letters  ^R: run  ^P: pause  Del: delete  y: yank  s: sort  Enter:
```

The footer lists the keys of the page you are on. The [key reference](#key-reference) lists them all.

## Getting around

`:` opens the palette. Type the start of a page name (`:so` offers `:sources`), then Tab or Enter.

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
| `y` | copy the current row to your clipboard, through the terminal: works over SSH where the terminal allows it (iTerm2, kitty, WezTerm, Windows Terminal; not macOS Terminal) |
| `q` | quit |
| `?` | help screen (the same table, always at hand) |
| `Esc` | close a modal, cancel an input, or back out one level |

- The header names the hub, the page, the session scope and the event stream: `[LIVE✓]`, or
  `[RECONNECTING]` with the reason in the footer.
- `:sources`, `:endpoints`, `:runs`, `:dlq` and `:inbox` update live; the cursor stays on your row.
- **Another hub:** `:context` (or `:ctx`) lists your contexts. `Enter` switches for this session
  only; `lm config use-context` sets the default.
- **Session expired:** a window opens. `L` logs in again, as `lm login` does, and the TUI resumes.

## Watch an ingestion

`:sources` is the home page: one row per connector. `/desk` above kept the rows holding `desk`.

- `^R` (or `R`) runs the selected connector now; `^P` pauses or resumes its schedule.
- `e` opens its YAML in `$EDITOR`; `t` edits its scopes, then offers to re-stamp its chat chunks.
- `Del` says what the delete takes, then asks:
  `Delete connector "cust-csv"? Goes with it: 3 rows of connector.t_cust_csv, its run history and RAG chunks.`
  A table an entity reads shows the refusal instead, naming the entity.
- `Enter` opens a REST or JDBC source's `:endpoints`, any other connector's `:runs`.

`Enter` on `acme-desk-crm`, then `d` on its run:

```
 lumnik 2026 · lm @ selfhost (localhost:18080) · :runs · scope: system · 00:32:36  [LIVE✓]
╔══════════════════════════════════════ :runs › connector acme-desk-crm ═════════════════════════════════════╗
║CONNECTOR          ENDPOINT      STATUS           STARTED          DURATION      RECORDS      TRIGGER       ║
║acme-desk-crm      default       ● Completed      10-24 23:07      37ms          8/8/0        MANUAL        ║
╚════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
── describe: cb24e3a8 ──
┌────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│id:              cb24e3a8-c418-4985-8247-d5210ebc0687                                                       │
│connector:       acme-desk-crm                                                                              │
│endpoint:        default                                                                                    │
│status:          ● Completed                                                                                │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
 Esc: clear filter  d: describe  D: dead letters  y: yank  s: sort  c: cancel  r: refresh  Tab: scroll the
```

- `RECORDS` is in/out/skip. Every dead letter is counted in `skip`.
- `d` shows the run in full: for a failed or cancelled run, an `error:` section with the message.
- `D` opens the run's dead letters in `:dlq`.

**`:endpoints`** (REST and JDBC sources) shows each endpoint's `SCHEDULE`, `LAST_RUN` and `STATUS`:
where to check "did it even fire?". `T` tests the endpoint, nothing written; `P` probes a path and
drafts its fragment; `C` resets its cursor (asks first); `Del` purges its rows, when the type
allows it — the question names the count.

### Stopping a run

Select a `Running` row in `:runs` and press `c`. The chunk in flight finishes; the run ends at the
next one. A `Queued` run ends at once.

| Refusal toasted | Meaning |
|---|---|
| `run … already ended (STATUS)` | nothing left to stop |
| `run … is being started; try again in a moment` | a `Queued` run is starting — retry |
| `run … is not in flight on this hub` | the run started a moment ago — retry; refused again, no process runs it, and the hub marks it `Failed` on its own |

### What a status means

| `STATUS` | What happened | Look at |
|---|---|---|
| `Queued` | waiting for a free slot (only with the [run queue](../operations/upgrade.md#turning-on-the-run-queue) on) | nothing: it starts on its own |
| `Running` | still going | `d` |
| `Completed` | done. Rows in `:dlq` were set aside; other `skip` rows were filtered on purpose | `:dlq`, `RECORDS` |
| `Partial` | read less than the source held; the next run resumes | `d`: the `error:` section |
| `Failed` | the run failed: source unreachable, bad credentials, refused config | `d`: the `error:` section |
| `Cancelled` | stopped by a user, or skipped: another run of the endpoint was active | `d` |
| no row at all | the schedule has not fired yet | `:sources`, `:endpoints` |

Found the cause? In `:sources`, `e` fixes the connector and `R` runs it again. The full
walk: [Diagnosing a failed run](../recipes/diagnose-a-run.md).

`:dlq` lists the rows a run set aside, with reason and row. `^R` marks one replayed (naming the run
that carried it again), `^D` discards it.

## Decide

Four pages answer "what needs me":

- **`:inbox`** — the decisions your processes and workflows raised. `a` acks the selected one; it
  leaves the list. `A` shows the acked ones too:

  ```
   lumnik 2026 · lm @ selfhost (localhost:18080) · :inbox · scope: system · 00:34:00  [LIVE✓]
  ╔══════════════════════════════════════════════════ :inbox ══════════════════════════════════════════════════╗
  ║ID WHEN PROCESS                          MESSAGE                                                            ║
  ║66 1h   workflow:acme-delivery-lifecycle Livraison DL-1 promise pour le 2026-10-20, toujours en route [acke…║
  ║63 1h   litige-ouvert                    Commande SO-000013 du client P-1007 en litige — 860.00 €           ║
  ║8  2h   lyon-client-arrive               Nouveau client Camille Roux à Lyon [acked]                         ║
  ╚════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   a: ack · A: show disposed · d: describe · y: yank · r: refresh
  ```

- **`:outputs`** — what was sent out and whether it arrived, failures first. `^R` replays a failed
  delivery.
- **`:observations`** — the workflow tape: documents that have NOT moved, stillest first. The title
  shows, per workflow, the documents watched, the dormant ones and the median detection time.
- **`:conflicts`** — the fused rows with a source conflict or a lookup ambiguity. Read-only: `d`
  shows which source said what; the fix is a source or manifest change.

## Explore & ask

`:ask` is analytic chat: a plain-language question comes back as generated SQL, rows and an answer.
It asks the session scope shown in the header — your default (`lm scope`) at startup, changed with
`:scopes` → `Enter` or `:scope <tag>`. A question continues the last one answered, as
[`lm ask --previous`](lm-reference.md) does: "Et SUP-004 ?" is answered as the full question, shown
in the headline after `understood as:`. An answer that ran no SQL keeps the question it continues; changing the scope starts over.

| Key | Effect |
|---|---|
| `Enter` | run the question |
| `y` | yank the generated SQL |
| `e` | export the result rows to a CSV file |
| `n` | new question — the next one stands alone |
| `r` | clear the page — the next question stands alone |

`:view` renders a [`kind: View`](../apps/views.md) manifest file. The path field starts on the
WOW's `docs/demo/wow-customer/clients-view.yaml`, relative to where you started `lm`: from the repo
root, `Enter` renders it:

```
 lumnik 2026 · lm @ selfhost (localhost:18080) · :view · scope: desk · 00:33:37  [LIVE✓]
╔══════════════════════════════════════════════════ Clients ═════════════════════════════════════════════════╗
║CODE CLIENT NOM              VILLE SEGMENT                                                                  ║
║C-100       Jean Dupont      Paris GOLD                                                                     ║
║C-200       Marie            Lyon  SILVER                                                                   ║
║C-300       Solo             Nice                                                                           ║
║C-400       Camille Roux     Lyon                                                                           ║
║C-500       Claire Martin    Lyon                                                                           ║
║C-600       Pierre Corneille Paris                                                                          ║
║C-700       Maurice Druon    Paris                                                                          ║
║C-800       Albert Camus     Lyon                                                                           ║
║C-900       Saint Exupery    Lyon                                                                           ║
╚════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
 Enter: detail (_conflicts) · f: add filter · F: clear filters · g: generate (AI) · a: apply to hub · o: open
```

`Enter` on a row opens the record, with its conflicts:

```
 lumnik 2026 · lm @ selfhost (localhost:18080) · :view · scope: desk · 00:33:41  [LIVE✓]
╔════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
║Clients — C-100                                                                                             ║
║Code client  C-100                                                                                          ║
║Nom          Jean Dupont                                                                                    ║
║Ville        Paris                                                                                          ║
║Segment      GOLD                                                                                           ║
║conflicts                                                                                                   ║
║no conflicts or ambiguities                                                                                 ║
╚════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
 Esc: back to the list
```

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
| `:apps` | the business apps (title, scope, default, tabs) | `e` edit in `:new` (rebuilt from the app's fields) · `Del` delete (asks first) |
| `:workflows` | the declared document lifecycles | `e` edit in `:new` · `^P` enable/disable · `Del` delete (asks first) |
| `:exports` | `kind: Export` manifests: entity, schedule, destination, last run (integrator role only) | `Enter` its runs · `R` run now · `^R` retry a Failed run · `e` edit in `:new` |
| `:search` | whether this hub has a Search provider, then the declared indexes (status, documents, last rebuild) | `/` query, bound to the session scope · `R` rebuild · `c` cancel the rebuild · `Del` delete (asks first) |
| `:secrets` | the credentials connectors refer to by name (values never shown) | `n` store one (typed masked) · `e` rotate its value · `Del` delete (asks first) |
| `:integrations` | the webhook targets `:outputs` delivers to, with recent delivery health; URLs shown to the host only | `n` register (URL and secret typed masked) · `T` fire a test event · `d` registration and latest deliveries · `Del` remove (asks first; history kept) |
| `:notify` | the email rules | `n` add one (events, recipient, entity filter, template) · `Del` remove (asks first) |
| `:scopes` | the tenant's business scopes | `Enter` set the active scope |

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
| `D` | `:sources` `:runs` | the dead letters of the connector / the run, in `:dlq` |
| `Tab` | global, describe panel open | scroll the panel (`↑` `↓` `PgUp` `PgDn`) · `Tab` / `Esc` back to the list |
| `:context` | global | switch to another configured hub, for this session (`Enter` switches, `Esc` leaves) |
| `/` | `:sources` `:runs` `:dlq` `:endpoints` `:processes` `:views` `:apps` `:workflows` `:inbox` `:secrets` `:integrations` `:notify` `:exports` | filter to the rows holding a text (any column, case-insensitive); survives the live refresh, `Esc` clears it |
| `s` | `:sources` `:runs` `:dlq` `:conflicts` `:ambiguous` `:scopes` | cycle the sort column |
| `c` | `:runs` | request cancellation of the selected `Running` run |
| `e` / `R` (or `^R`) / `^P` / `Del` / `t` | `:sources` | edit YAML in `$EDITOR` · run now · pause/resume · delete · edit tags (then offers to re-stamp the indexed chunks) |
| `Enter` | `:sources` | REST / JDBC → its `:endpoints`; others → its `:runs` |
| `Enter` / `Esc` | `:endpoints` | the endpoint's `:runs` · back to `:sources` |
| `T` / `P` / `^P` / `C` / `Del` | `:endpoints` | test · probe · enable/disable · reset cursor · purge |
| `^R` / `^D` | `:dlq` | mark replayed, naming the run that carried it (Enter: the connector's latest; with no run, `^D` instead) · discard |
| `^R` | `:outputs` | replay the selected failed delivery |
| `a` / `A` | `:inbox` | ack the selected decision · show/hide disposed (`[acked]`) |
| `Enter` / `n` `p` / `a` / `Del` / `m` `=` `!` / `h` / `^R` | `:entities` | rows · page · ambiguous · delete · mark, same, distinct · history · re-resolve |
| `Enter` / `^R` | `:ambiguous` | list an entity / load a row's history · re-run the pass |
| `R` | `:rag` | reindex the platform corpus (asks first) |
| `Enter` / `d` | `:tables` | the raw rows (`n` `p` page, `d` whole row, `Esc` back) · describe |
| `Enter` / `e` / `Del` | `:views` | render in `:view` · edit in `:new` · delete (asks first) |
| `e` / `Del` | `:apps` | edit in `:new` · delete (asks first) |
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
