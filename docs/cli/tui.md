# The TUI

> Bare `lm` (or `lm ui`) opens the k9s-style console (the Kubernetes operators'
> console — same navigation idiom) — the installer's daily screen. `:` is the
> command palette (type a page name, Tab/Enter to commit); `?` opens the in-app
> help; `q` quits.

This page tours the TUI by task — what you press to watch an ingestion, decide on a
pending item, ask your data a question, or create something new. The [key
reference](#key-reference) at the bottom lists every gesture in one table if you
already know what you're looking for.

## Getting around

`:` opens the palette — start typing a page name (`:so` offers `:sources`) and press
Tab or Enter to commit. The pages: `:new` `:ask` `:inbox` `:processes` `:view`
`:modules` `:sources` `:conflicts` `:observations` `:outputs` `:endpoints` `:runs`
`:dlq` `:hooks` `:migrations` `:scopes` (`:connectors` still works — it's an older
spelling that resolves to `:sources`).

Global keys, from any page:

| Key | Effect |
|---|---|
| `:` | open the command palette |
| `r` | refresh the current page |
| `d` | toggle the describe panel (full detail on the selected row) |
| `y` | yank the current row — copies it to your local clipboard, even over SSH |
| `q` | quit |
| `?` | help screen (the same table, always at hand) |
| `Esc` | close a modal, cancel an input, or back out one level |

`:runs`, `:dlq` and `:inbox` live-update — new rows appear without pressing `r`.

**Session expired.** If your login expires or is rotated mid-session, the TUI raises
a **session-expired** modal instead of failing silently. Press `L` to log in again —
the same OIDC device flow as `lm login`: a URL plus a short code, approved on your
identity provider's sign-in page, and the TUI resumes automatically — or `Esc` to
dismiss it for now (it reappears on the next request that needs the dead session).

## Watch an ingestion

`:sources` is the home page — where your data comes from, one row per connector instance.

| Column / key | Where | What it tells you |
|---|---|---|
| `SCHED` | `:sources` | the connector's schedule |
| `ENABLED` | `:sources` | whether the connector is on at all |
| `Enter` | `:sources` | REST source → drills into `:endpoints`; any other kind → jumps straight to `:runs`, filtered to that connector |
| `SCHEDULE`, `LAST_RUN`, `STATUS` | `:endpoints` | per endpoint — for a REST source, this is where to check "did it even fire?" without leaving the table |
| `Enter` | `:endpoints` | drills into that endpoint's own `:runs` |
| `at:` (under `── last run ──`) | `d` describe panel, any source | the exact last-run time, even when it isn't a table column |

In `:sources`: `s` cycles the sort column, `e` opens the connector's YAML in
`$EDITOR` for a live edit, `R` (or `^R`) triggers a run now, `^P` pauses or resumes
it, `Del` deletes it (no confirmation), `t` edits its tags.

**Reading a run.** `:runs` lists `CONNECTOR`, `STATUS`, `STARTED`, `DURATION`,
`RECORDS` (in/out/skip) and `TRIGGER` per row. Select one and press `d` for the full
record — the same fields plus the run's id, chunk count, and, when the run failed,
a final `error:` section with the failure message in full.

`STATUS` takes exactly four values. The table below decodes every one of them, in the
order to check when a run's outcome isn't obvious:

| What you see | What happened | Where the detail lives |
|---|---|---|
| `STATUS` = `Running` | The run hasn't reached a terminal status yet — it's still going | `:runs` live-updates on its own; press `d` to watch progress so far |
| `STATUS` = `Failed` | A connection or manifest problem (source unreachable, bad credentials, a refused config) failed the *run itself* | The `error:` section in `d`'s full record |
| `STATUS` = `Completed`, a row is in `:dlq` | A quality failure on that one row (e.g. `validate-row`) | `:dlq` — reason and payload |
| `STATUS` = `Completed`, row counted in `skip` but not in `:dlq` | The row was excluded by a declared filter (`filter-where`, `skip-empty-rows`), not rejected | The `skip` figure in `RECORDS` |
| `STATUS` = `Cancelled` | Either cancelled by a user mid-run, or this run was skipped because another run for the same endpoint was already active | The `error:` section in `d`'s full record — `cancelled by user` or `skipped: another run is already active for this endpoint` |
| No entry in `:runs` at all | Nothing failed — the schedule hasn't fired yet | `:sources` / `:endpoints`, not `:runs` |

Every dead-letter is also counted in `skip` — `:dlq` is always a subset of it,
never more — so `in/out/skip` alone already tells you whether a run dropped
anything, DLQ'd or not.

Once you have the cause: fix it (`e` to edit the connector's YAML, or the secret/env
value on the hub if that's the problem), then `R` to run it again. The full walk —
including the dead-letter reasons table — is [Diagnosing a failed
run](../recipes/diagnose-a-run.md).

Once you're watching runs, `:dlq` is next: the **dead-letter queue**, where a row
that a connector could not place waits with its reason and payload rather than being
silently dropped. `^R` marks a dead-letter replayed, `^D` discards it.

## Decide

Four pages answer "what needs me":

- **`:inbox`** — the tenant's `decision.*` events, business decisions raised by processes
  and workflows, never run failures (run health lives in `:runs`, above). `a` acks the
  selected one (idempotent — acking twice is harmless); `A` toggles whether
  already-disposed entries show.
- **`:outputs`** — what left the substrate and whether it arrived, failures listed first.
  `^R` replays a failed delivery — a succeeded one has nothing to replay, and the TUI
  says so instead of no-op'ing.
- **`:observations`** — the workflow tape: documents that have NOT moved, stillest first.
- **`:conflicts`** — the fused rows where sources still disagree (read-only — resolving
  one is an upstream manifest change, never a keystroke here).

## Explore & ask

`:ask` is analytic chat — ask your data a question in plain language and it comes
back as generated SQL plus rows plus a plain-language answer. `Enter` submits, `y`
yanks the generated SQL, `e` exports the result rows to a CSV file, `n` clears the
input for a new question. The scope it asks against comes from `:scopes` — `Enter`
on a row there sets the active scope for the rest of the session (shown in the
header, and it's what `:ask` uses on the next question).

`:view` opens a [`kind:View`](../how-it-works.md#five-kinds-two-families) manifest file
and renders it — `Enter` opens the suggested living example on an empty path, or type
your own.

| Key | Effect |
|---|---|
| `Enter` (on a rendered row) | drills into detail, with the `_conflicts` ledger |
| `f` | adds a server-side filter (a bad one is refused with the reason, not silently dropped) |
| `F` | clears all filters |
| `g` | generates a view from a plain-language sentence, over the active scope — set one first with `:scopes` or the `:scope <tag>` palette shortcut |
| `a` | applies the rendered view into the hub's saved-view store |
| `o` | opens another file |
| `r` | refreshes |

## Create

`:new` is the authoring loop's front door: pick a kind, `Enter` opens its living
example in `$EDITOR`. On save, the manifest validates (or applies directly, for
kinds whose apply step *is* the validation) — a clean pass applies it and jumps to
where you'd observe it; issues show field-by-field and drop you into the issues
view, where `e` re-opens the *same* buffer for another edit and `Esc` abandons it —
but never destroys it: your edits are written to a temp file first, so nothing is
ever lost to a stray `Esc`.

`:processes` lists declared
[`kind:Process`](../how-it-works.md#five-kinds-two-families) rules; `e` re-opens a
stored manifest through that same edit → validate → apply loop, so create and edit
share one path.

## Admin corner

`:modules` lists installed business modules. `:hooks` lists installed Kotlin hooks;
`^L` reloads the selected one. `:migrations` shows Flyway status per schema.
`:scopes` lists the tenant's per-métier scopes (connector tags) — `Enter` sets the
active one, as noted above.

## Key reference

| Key | Where | Effect |
|---|---|---|
| `:` `r` `d` `y` `q` `?` `Esc` | global | palette · refresh · describe · yank row · quit · help · close |
| `s` | `:sources` `:runs` `:dlq` | cycle the sort column |
| `e` / `R` (or `^R`) / `^P` / `Del` / `t` | `:sources` | edit YAML in `$EDITOR` · run now · pause/resume · delete · edit tags |
| `Enter` | `:sources` | REST → its `:endpoints`; others → its `:runs` |
| `Enter` / `Esc` | `:endpoints` | the endpoint's `:runs` · back to `:sources` |
| `^R` / `^D` | `:dlq` | mark replayed · discard |
| `^R` | `:outputs` | replay the selected failed delivery (a new attempt is recorded) |
| `a` / `A` | `:inbox` | ack the selected decision (idempotent) · toggle disposed (`[traité]`) |
| `Enter` / `e` / `Esc` | `:new` | edit the kind's living example in `$EDITOR` · re-edit after refusals · abandon (buffer kept in a temp file) |
| `e` | `:processes` | edit the stored manifest through the same validate → apply loop |
| `Enter` / `f` `F` / `g` / `a` / `o` / `r` | `:view` | record detail (`_conflicts` ledger) · add/clear server-side filters (refusals name the reason) · generate from a sentence (AI) · apply the rendered view to the hub store · open another file · refresh |
| `^L` | `:hooks` | reload |
| `Enter` | `:scopes` | set the active scope (used by `:ask`) |
| `Enter` / `y` / `e` / `n` | `:ask` | run · yank the SQL (OSC52) · export rows to CSV · new question |

## See also

- [CLI reference](lm-reference.md) — the same surface, driven from scripts and CI
  instead of the console.
- [Diagnosing a failed run](../recipes/diagnose-a-run.md) — the full walk from a
  failed run to a fix, `lm run get` / `lm dlq get` and the dead-letter reasons table.
- [Connectors overview](../connectors/index.md) — what a source, an endpoint, and a
  run actually are.
- [Inbox](../apps/inbox.md) — the decisions the TUI's `:inbox` page surfaces.
