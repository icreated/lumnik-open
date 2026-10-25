# Inbox — the human side of the decision

> The third rung: the tenant's recent `decision.*` events, newest first — what needs a human.
> And the disposal gesture: **ack**.

```bash
lm inbox                 # open decisions (ID, WHEN, PROCESS, MESSAGE, ENTITY_KEY)
lm inbox --limit 100     # 1..200, default 50
lm inbox --all           # include disposed ones, marked [acked]
lm inbox --process litige-ouvert         # one process's decisions (as PROCESS shows it)
lm inbox --type decision.litige-ouvert   # one decision type
lm inbox ack 42          # dispose of decision 42
```

On the [demo desk](demo-desk.md) after **day 3** (see [Processes](processes.md#watch-a-second-condition-on-the-same-table)),
two processes fired on the same order; a human disposes of one:

```
$ lm inbox
ID  WHEN  PROCESS          MESSAGE                                                    ENTITY_KEY
25  2min  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003          3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
24  2min  litige-ouvert    Commande SO-000014 du client P-1003 en litige — 5000.00 €  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
$ lm inbox ack 25
acked.
$ lm inbox
ID  WHEN  PROCESS        MESSAGE                                                    ENTITY_KEY
24  2min  litige-ouvert  Commande SO-000014 du client P-1003 en litige — 5000.00 €  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
$ lm inbox --all
ID  WHEN  PROCESS          MESSAGE                                                           ENTITY_KEY
25  2min  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003 [acked]         3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
24  2min  litige-ouvert    Commande SO-000014 du client P-1003 en litige — 5000.00 €         3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
18  3min  litige-ouvert    Commande SO-000013 du client P-1007 en litige — 860.00 € [acked]  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

| Column | Reads |
|---|---|
| `WHEN` | a humanized age: `just now`, `2min`, `2h`, `yesterday`, `3d` |
| `PROCESS` | the process name, or `workflow:<name>` for a [dormancy](workflows.md#dormancy-the-memory-of-the-breathing) or [due-date](workflows.md#due-date-a-deadline-the-data-carries) alert |
| `ENTITY_KEY` | a process decision: the row's content hash. A workflow alert: the document's business key (`SO-000014`) |
| `[acked]` | after the message: a disposed decision |

Narrow the list: `lm inbox --limit 5 --process litige-ouvert` gives that process's five newest
decisions. A workflow alert's process is `workflow:<name>`; `--type decision.overdue.<name>` keeps
one kind of alert.

Re-acking is harmless: it answers the same `acked.`. In the TUI, `:inbox` shows the same list
live — `a` acks, `A` toggles disposed entries. The inbox needs the `lm_user` role.

## Who sees which decision

Each decision belongs to the `scope` of the [`kind: Process`](processes.md) or
[`kind: Workflow`](workflows.md) that raised it, as the manifest stands now:

- a user with no `scope:*` role sees the whole tenant's decisions;
- a user holding `scope:<tag>` roles sees, and can ack, only decisions in those scopes — an id from
  another scope answers `404`, like an id that does not exist (or another tenant's);
- re-scope a manifest and its past decisions move with it; remove or rename it and they belong to
  no scope, so only users with no `scope:*` role still see them.

Above, `litige-ouvert` has since been removed (`lm process rm`). A user holding `scope:desk`
therefore sees only `grosse-commande`'s decision, and a `scope:stock` user sees none:

```
$ lm inbox --all                      # as a scope:desk user
ID  WHEN  PROCESS          MESSAGE                                                    ENTITY_KEY
25  2min  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003 [acked]  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
$ lm inbox ack 24                     # as a scope:stock user
Error: API error 404: no decision 24 in your inbox
```

!!! note "Retention"
    Decisions age out with the event log (`lumnik.events.retention-days`, default 30). The inbox
    is a work surface, not an archive.

## Decision sources

- **`decision.<process-name>`** — a [`kind: Process`](processes.md) reacting to a row landing
  (the end-to-end story: [How lumnik breathes](../respiration.md)).
- **`decision.dormant.<workflow-name>`** — a [`kind: Workflow`](workflows.md)'s dormancy sweep:
  a non-terminal document observed unchanged past its declared threshold. See
  [Dormancy](workflows.md#dormancy-the-memory-of-the-breathing) for its wording.
- **`decision.overdue.<workflow-name>`** — a workflow's due date: a document still in a watched
  state after the date its data carries. See [Due date](workflows.md#due-date-a-deadline-the-data-carries).

The ack is itself an event (`annotation.decision.acked`), so a webhook can react to it.

## See also

- [Processes](processes.md) — where decisions come from.
- [Workflows](workflows.md) — declares what the values behind a decision mean.
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — how a
  decision also reaches an external system.
- The [WOW walkthrough](../wow.md) — the whole ladder in one thread: see → react → decide,
  with [declare](workflows.md) underneath.
