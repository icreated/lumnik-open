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
kebab-case only. A name the hub does not have is refused when you add the rule, with the names
it does carry:

```
$ lm notify add --events 'decision.*' --to adv@example.com --template '../x'
Error: API error 400: template must match [a-z0-9-]+ (a template name, not a path)
$ lm notify add --events 'decision.*' --to adv@example.com --template decision-alrt
Error: API error 400: template 'decision-alrt' is not one this hub carries — the ones it ships: event-notification, decision-alert
```

Two ship with the hub:

| Template | Voice | Body |
|---|---|---|
| `event-notification` (default) | ops | subject + source/entity/time line + the payload as JSON in a `<pre>` |
| `decision-alert` | business | the process **message as headline** ("Commande SO-000013 du client P-1007 en litige — 860.00 €"), entity & key, the referenced fields as a table |

The subject line does not depend on the template.

- A decision (`decision.*`) has its own sentence as the subject: the one `lm inbox` shows, on one
  line, cut at 100 characters. Three decisions give three different subjects.
- Any other mail, or a decision with no sentence, reads `lumnik event: ` and the event name.

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
what may be absent. A missing reference fails the render, and the mail is given up at once:

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
  `process` or `entity_key`; `fields` only when the message cites columns or the workflow
  [declares some](apps/workflows.md#what-a-decision-carries).
- **[Due dates](apps/workflows.md#due-date-a-deadline-the-data-carries)** (`decision.overdue.<workflow>`): `message`, `workflow`,
  `doc_key`, `state`, `due`, `due_at`; `fields` on the same terms.

### Add your own template

Templates ship inside the hub image: the published image cannot take your own. Put the file at
`lumnik-hub/src/main/resources/templates/email/<kebab-name>.html`, then rebuild with
`./deploy/selfhost/up.sh --build`. Rehearse against the
[Mailpit drill](deploy.md#turn-on-email-delivery) before pointing rules at it.

## Delivery — what to expect

- A mail leaves within about 5 seconds.
- **Mocked** until the deploy sets the SMTP knobs ([turn on email delivery](deploy.md#turn-on-email-delivery)).
- **A failed send is tried again**, three attempts in all, then given up on that mail alone. One broken
  recipient does not block the others. A mail whose template fails is given up at once.
- **A hub crash does not lose a mail.** A mail can go twice if the hub stops after the mail server took it
  and before the hub wrote it down.
- **See what happened:** `lm notify deliveries` lists the mails, the given-up ones first, with the kind of
  failure (never the text of the mail). `lm notify replay ID` sends a given-up mail again: the same
  delivery, so a rule never gets a second mail for an event, and a mail already sent is refused.
- **A given-up mail is told, not only listed.** Each one raises an event, `notification.mail.given-up`,
  once, when it is given up. It carries the mail's ID (the one `lm notify deliveries` shows), the rule,
  the event type and the kind of failure. Never the mail. A [webhook](outputs.md#wire-a-webhook) can
  carry it to the people who must act:
  `lm integration add relay-watch --url https://n8n.example.com/webhook/lumnik --events 'notification.mail.given-up' --secret-stdin`.
  Fix the cause, then `lm notify replay ID`. A new password gives up every mail at once: expect one
  event per mail.
- **Read the ERROR column** to know what to fix:

| ERROR says | What it means | The gesture |
|---|---|---|
| `authentication refused (SMTP 535)` | The relay refused the login. | Fix `LUMNIK_SMTP_PASSWORD`. |
| `relay unreachable` | Nothing answers at the host and port. | Fix the host, the port or the network. |
| `TLS refused` | The secure handshake failed. | Fix the TLS setting or the relay's certificate. |
| `address or message refused (SMTP 550)` | The relay refused an address or the message. The code does not say which. | Check the sender and the recipient, then the relay's own log. |
| `refused by the relay (SMTP 554)` | The relay refused the mail, with this code. | Read the relay's own log. |

Any other failure shows its own name.

```
$ lm notify deliveries
ID  RECIPIENT        EVENT                   STATUS          ATTEMPTS  WHEN              ERROR
12  adv@example.com  decision.litige-ouvert  given up: send  3         2026-11-08 10:12  authentication refused (SMTP 535)
13  adv@example.com  decision.litige-ouvert  sent            1         2026-11-08 10:13
$ lm notify replay 12
delivery 12: sent
```

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
