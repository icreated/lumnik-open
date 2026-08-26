# kind: Process — react to your data

> The *how* of one link in the chain — the whole story, told for the métier reader, is
> [How lumnik breathes](../respiration.md).

> The second rung: a **declarative watcher** on ingested rows. When a row lands that matches your
> conditions, the process emits a `decision.*` event — and the effect IS the event: delivery rides
> the hub's existing webhook subscriptions (n8n, Make, Zapier, your endpoint). Zero new code paths.

## The real manifest (from the WOW demo — proven live)

```yaml
--8<-- "docs/demo/wow-customer/lyon-process.yaml"
```

## Fields

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | kebab-case (`[a-z0-9-]+`) |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) declares `on.table` |
| `spec.on.event` | yes | **must be `data.row.changed`** — the only v1 trigger |
| `spec.on.table` | yes | the **raw ingest table** (`connector.t_<name>`) — see the note below |
| `spec.when[]` | yes (≥1) | conditions in the [view filter grammar](views.md#filter-grammar), ANDed |
| `spec.emit.type` | yes | **must start with `decision.`** |
| `spec.emit.message` | yes | human message, `{col}` templated |

That table is the whole surface: `apply` **refuses a key it does not honor** and names it, so a
misspelled `mesage:` costs you a 400 rather than a decision that fires with no text. The
`apiVersion:` envelope is accepted and ignored.

```bash
lm process apply -f lyon-process.yaml
lm process list
```

In the TUI, `:processes` lists the declared rules; `e` re-opens the stored manifest through
the `:new` authoring loop (edit → field-by-field validation → apply). The stored manifest is
also retrievable raw: `GET /api/processes/{name}/manifest`.

The whole `/api/processes` surface (apply, list, manifest) is the **admin door** — the
`lm_admin` role: declaring an attention rule is tenant administration, not a métier gesture.
Unlike views, entities, and chat, it is *not* métier-scope-bound — there is no
`403 {"error":"scope not granted"}` here; the admin sees the whole tenant.

Every stored process carries an `enabled` flag (default `true`; the ENABLED column in
`lm process list`), and only enabled processes fire. There is no API or CLI gesture to
toggle it yet — disabling is a database-level edit, so if you don't operate the hub yourself,
this one goes to whoever does (your DBA, or whoever runs the Postgres instance).

!!! note "Trigger at the raw write path"
    `data.row.changed` is born where rows are written — so `on.table` is the **raw** table the
    connector fills, not a fused view, and `when` columns are that table's own columns. The
    scope must be the one whose card carries that raw table (in the demo: the CSV connector's
    `demo` tag, not the fused `clients` scope).

## Message templating

`{col}` interpolates the row's value (`"Nouveau client {cname} à {ville}"`). A null value renders
as an empty string; there is no escape syntax — a placeholder is exactly `{letters_digits_}`,
anything else (`{}`, `{a-b}`, a lone brace) passes through literal. Every `{col}` is validated
at **apply time** against the trigger table: an unknown placeholder is refused
(`message references {x} — not a column of …`), never discovered silently at fire time.
Every referenced column also rides in the event payload as `fields`, so the receiving
automation gets the data, not just the sentence.

## Failure contract

- **Refusable at apply**: structural errors, an unknown `when` column, or an out-of-domain value
  are refused with the card's hint — a process that could never fire is never accepted silently.
- **Fail-closed per process at runtime**: a process that breaks (e.g. its table vanished) logs
  and skips — it never blocks the event cursor or its sibling processes.
- **Declarative only, by doctrine**: there is no Kotlin escape hatch for processes. If a real
  métier process can't be expressed declaratively, that limitation should surface — not be
  hidden inside a script (the substrate breathes, it does not mutate).
- **Delivery is opt-in, not automatic**: a firing process's decision lands in the outbox — and
  in [the inbox](inbox.md) — every time, regardless of subscriptions. Reaching an external
  system (n8n, Make, Zapier, your endpoint) additionally needs a webhook subscription already
  registered for the tenant; none is seeded by default. With none registered, nothing is
  pushed anywhere — the inbox is still where the decision is. There is no `lm` command for it:
  registering one is a `POST /api/tenants/{tid}/integrations` call, and it needs the same
  `lm_admin` role the rest of this page needs. The
  [Admin API guide](../admin-console-guide.md#send-events-out-to-another-system) walks the whole
  gesture if "my team gets told" needs to be a real promise.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (e.g. `mesage:`) | the message lists what `kind:Process` honors — fix the spelling |
| apply refused `404`: `no schema for scope '…' — run discovery first` | `spec.scope` isn't a discovered scope | `lm source rediscover --scope <tag>` rebuilds the card — it needs **both** `lm_integrator` and `lm_admin`; `lm source schema --scope <tag>` shows what it currently holds |
| apply refused: `spec.on.table '…' is not in the '…' scope` | `spec.on.table` names a table the scope's card doesn't have | `lm source schema --scope <tag>` to see the card's raw tables, then fix the name |
| apply refused: `when: column '…' is not in …`, or `'…' is not an allowed value for …` | a `spec.when` condition names an unknown column, or an `=` value outside its declared domain | fix the condition against `lm source schema --scope <tag>` |
| apply refused: `message references {…} — not a column of …` | `spec.emit.message` templates a placeholder that isn't a column of the trigger table | fix the placeholder, or drop it |
| a declared, enabled process never fires | its trigger table vanished or broke at runtime — fail-closed per process | check the hub's logs; sibling processes keep firing regardless |
| a process still fires after you meant to turn it off | `enabled` has no API or CLI toggle | flip the `enabled` column at the database directly, or ask whoever operates the hub to |
| decisions show in `lm inbox` but never reach an external system | delivery additionally needs a webhook subscription registered for the tenant — none is seeded by default | register one with `lm integration add` (`lm_admin` — see [Admin API guide](../admin-console-guide.md#send-events-out-to-another-system) for the API form), or keep triaging from `lm inbox` in the meantime |
| `403` with an **empty body** | you don't hold `lm_admin` — the whole `/api/processes` surface requires it | check your roles — `GET /api/bff/v1/me` returns them under `roles` — then ask whoever manages your Keycloak roles to grant `lm_admin` |

## See also

- [Inbox](inbox.md) — where a human sees and disposes of the emitted decisions.
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — how to
  register the webhook subscription delivery depends on.
- [Workflows](workflows.md) — declares what a status column's values *mean*, so a
  `when: statut=TERMINE` condition matches a state someone actually declared; a process fires
  on a row landing, but silence — nothing landing — is the workflow's dormancy sweep to see.
- [Kafka connector](../connectors/kafka.md) — events *into* the hub.
