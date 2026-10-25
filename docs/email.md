# Email notifications

A rule routes matching events to a recipient — one mail per event, rendered through a template
you choose. Needs the `lm_admin` role. For tools rather than people, use a [webhook](outputs.md#wire-a-webhook).

## Route events to a mailbox

```bash
# Business alerts: send every decision to the sales administration inbox
lm notify add --events 'decision.*' --to adv@example.com --template decision-alert

# The ops case: failed runs, to the integrator, raw payload included
lm notify add --events 'connector.run.failed' --to integrator@example.com

lm notify list
lm notify rm <ID>
```

```
$ lm notify add --events 'connector.run.failed' --to integrator@example.com
rule 1000: connector.run.failed → integrator@example.com
$ lm notify list
ID    EVENTS                RECIPIENT               TEMPLATE            ENABLED
1000  connector.run.failed  integrator@example.com  event-notification  true
```

- `--events` takes an exact type or a prefix ending in `.*` — see the
  [event registry](outputs.md#the-event-registry).
- **Narrow a rule to one table (HTTP only):** no `lm` flag does it. Post the rule with
  `entityFilter` and the table's full name ([mint `$TOKEN`](recipes/test-the-api.md#mint-a-token)):

    ```bash
    curl -s -X POST http://localhost:8080/api/notifications -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"eventPattern":"data.row.changed","entityFilter":"connector.t_customers","recipient":"adv@example.com"}'
    ```

    A misspelt key is refused with `400`, naming it and the accepted keys:

    ```
    {"error":"unknown key: entity_filter — accepted keys: enabled, entityFilter, eventPattern, recipient, template"}
    ```

    For `data.row.changed`, a name that is not a connector table is refused.

## Templates

Templates are [Qute](https://quarkus.io/guides/qute) HTML files at
`templates/email/<name>.html` inside the hub. The rule picks one by name (`--template`), in
kebab-case only; an unknown name is logged and the mail skipped.

```
$ lm notify add --events 'decision.*' --to adv@example.com --template '../x'
Error: API error 400: template must match [a-z0-9-]+ (a template name, not a path)
```

Two ship with the hub:

| Template | Voice | Body |
|---|---|---|
| `event-notification` (default) | ops | subject + source/entity/time line + the payload as JSON in a `<pre>` |
| `decision-alert` | business | the process **message as headline** ("Commande SO-000013 du client P-1007 en litige — 860.00 €"), entity & key, the referenced fields as a table |

### The variables a template sees

| Variable | Type | Content |
|---|---|---|
| `{eventType}` | string | `decision.litige-ouvert` |
| `{source}` | string | the emitter (`process:litige-ouvert`, `connector-engine`, …) |
| `{entity}` | string | the ingested table, or `—` |
| `{occurredAt}` | string | ISO timestamp |
| `{payload}` | **map, navigable** | the event's payload — `{payload.entity_key}`, `{#for f in payload.fields}` |
| `{payloadJson}` | string | the same payload, serialized — for `<pre>` blocks |

Payloads differ per event type ([registry](outputs.md#the-event-registry)), so guard
what may be absent — a missing reference fails the render and the mail is skipped:

```html
<h2>{payload.message ?: eventType}</h2>       {! fallback when the key is absent !}
{#if payload.fields}
  {#for f in payload.fields}<tr><td>{f.key}</td><td>{f.value}</td></tr>{/for}
{/if}
```

`decision.*` payloads come in **three shapes**, sharing only `message` — which is why
`decision-alert` leads with it and guards everything else:

- **[Process](apps/processes.md) rules** (`decision.<name>`): `message` (the rule's
  rendered sentence), `process`, `entity`, `entity_key` — and `fields` (the row columns
  the message referenced, with their values) **only when the message references any**.
- **[Dormancy watches](apps/workflows.md)** (`decision.dormant.<workflow>`): `message`,
  `workflow`, `doc_key`, `state`, `since`, `first_observed_at`, `threshold` — never
  `process`, `entity_key` or `fields`.
- **[Due dates](apps/workflows.md#due-date-a-deadline-the-data-carries)** (`decision.overdue.<workflow>`): `message`, `workflow`,
  `doc_key`, `state`, `due`, `due_at`.

### Add your own template

Templates ship inside the hub image: the published image cannot take your own. Put the file at
`lumnik-hub/src/main/resources/templates/email/<kebab-name>.html`, then rebuild with
`./deploy/selfhost/up.sh --build`. Rehearse against the
[Mailpit drill](deploy.md#turn-on-email-delivery) before pointing rules at it.

## Delivery — what to expect

- A mail leaves within about 5 seconds.
- **Mocked** until the deploy sets the SMTP knobs ([turn on email delivery](deploy.md#turn-on-email-delivery)).
- **Best-effort:** a failed send is logged and skipped — no retry, no journal. One broken
  recipient does not block the others.
- **A hub crash can lose a batch of mails**; it never sends one twice.

When delivery must be provable, wire a [webhook](outputs.md#wire-a-webhook) as well.

## How to…

### Mail the ADV every time an order lands in dispute

The demo desk's `litige-ouvert` process already writes the sentence the ADV reads
([Processes](apps/processes.md#quickstart)). Route it to a mailbox:

```bash
lm notify add --events decision.litige-ouvert --to adv@example.com --template decision-alert
```

**Result:** the next run that lands an order in dispute sends one mail. Its headline is the
process's sentence, as `lm inbox` shows it: *Commande SO-000013 du client P-1007 en litige —
860.00 €*. Below it, the referenced fields as a table.

### Rehearse without a mail provider

```bash
docker compose --profile mailpit -f docker-compose.selfhost.yml up -d
# .env: LUMNIK_SMTP_HOST=mailpit  LUMNIK_SMTP_PORT=1025  LUMNIK_SMTP_MOCK=false
./deploy/selfhost/up.sh --build    # re-run with the flags you installed with
# then browse to http://127.0.0.1:8025
```

**Result:** every rule-matched event lands in Mailpit's inbox, rendered exactly as your
provider would send it — templates included.

### Show only the fields that matter

A template is ordinary HTML around the variables — this one renders two columns and
nothing else:

```html
<!-- templates/email/commande-courte.html -->
<h2>{payload.message ?: eventType}</h2>
<p>N° <strong>{payload.fields.order_no ?: '—'}</strong> · {payload.fields.amount ?: '—'} €</p>
```

Rebuild the hub (templates ship in the image), then point the rule at it:
`lm notify add --events decision.litige-ouvert --to adv@example.com --template commande-courte`.

## See also

- [Outputs — the roads out](outputs.md) — the full wiring manual, registry included
- [Processes — react](apps/processes.md) — where `decision.*` and its `message` are declared
- [Deploy — turn on email delivery](deploy.md#turn-on-email-delivery) — SMTP knobs & Mailpit
