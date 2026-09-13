# Email notifications

The webhook road is for tools; this one is for people who live in their mailbox. A rule
routes matching events to a recipient — one mail per event, rendered through a template
you choose. Wiring is `lm_admin` surface.

## Route events to a mailbox

```bash
# The métier case: every minted decision, to the ADV inbox, in métier words
lm notify add --events 'decision.*' --to adv@example.com --template decision-alert

# The ops case: failed runs, to the integrator, raw payload included
lm notify add --events 'connector.run.failed' --to integrator@example.com

lm notify list
lm notify rm <ID>
```

`--events` takes an exact type or a prefix ending in `.*` — the vocabulary is the
[event registry](outputs.md#the-event-registry). Over HTTP (`POST /api/notifications`)
a rule can also carry an `entity_filter` narrowing it to one ingested table. It must be that
table's **full name** — `connector.t_customers`, the name an event carries as its entity — and the
door refuses anything else rather than storing a rule that can never match.

## Templates

Templates are [Qute](https://quarkus.io/guides/qute) HTML files at
`templates/email/<name>.html` inside the hub. The rule picks one by name
(`--template`); the name is restricted to kebab-case (`[a-z0-9-]+`) at the door and at
send time, so a stored rule can never traverse out of the template root. An unknown
name is logged and skipped, never a crash.

Two ship today:

| Template | Voice | Body |
|---|---|---|
| `event-notification` (default) | ops | subject + source/entity/time line + the payload as JSON in a `<pre>` |
| `decision-alert` | métier | the process **message as headline** ("Commande 12045 bloquée depuis 32 jours"), entity & key, the referenced fields as a table |

### The variables a template sees

| Variable | Type | Content |
|---|---|---|
| `{eventType}` | string | `decision.commande-bloquee` |
| `{source}` | string | the emitter (`process:commande-bloquee`, `connector-engine`, …) |
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

`decision.*` payloads come in **two shapes**, sharing only `message` — which is why
`decision-alert` leads with it and guards everything else:

- **[Process](apps/processes.md) rules** (`decision.<name>`): `message` (the rule's
  rendered sentence), `process`, `entity`, `entity_key` — and `fields` (the row columns
  the message referenced, with their values) **only when the message references any**.
- **[Dormancy watches](apps/workflows.md)** (`decision.dormant.<workflow>`): `message`,
  `workflow`, `doc_key`, `state`, `since`, `first_observed_at`, `threshold` — never
  `process`, `entity_key` or `fields`.

### Add your own template

Drop `templates/email/<kebab-name>.html` into the hub's resources and rebuild — templates
ship inside the hub image; there is no runtime upload. Rehearse against the
[Mailpit drill](deploy.md#turn-on-email-delivery) before pointing rules at it.

## Delivery — what to expect

Rules are swept every 5 s with their own cursor (a webhook outage never starves email).
Delivery is **mocked until the deploy sets the SMTP knobs**
([turn on email delivery](deploy.md#turn-on-email-delivery)) — the boot log states the
posture. Email is **best-effort**: a failed send is logged and skipped, no retry, no
journal — per-rule, so one broken recipient never starves the others. When delivery
must be provable, wire a [webhook](outputs.md#wire-a-webhook) as well.

## How to…

### Mail the ADV every time an order gets blocked

One rule declares the watch, one rule routes the mail — the French sentence you write in
the manifest is the headline the ADV reads:

```yaml
# commande-bloquee.yaml — modeled on the WOW's proven lyon-process.yaml
apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: commande-bloquee
spec:
  scope: ventes
  on:
    event: data.row.changed
    table: connector.t_commandes
  when:
    - statut=BLOQUEE
  emit:
    type: decision.commande-bloquee
    message: "Commande {num} bloquée ({montant} €)"
```

```bash
lm process apply -f commande-bloquee.yaml
lm notify add --events decision.commande-bloquee --to adv@example.com --template decision-alert
```

**Result:** the next ingestion run that lands a `statut=BLOQUEE` row sends a mail whose
headline is *Commande 12045 bloquée (8 400 €)* — with `num` and `montant` in the fields
table, because the message referenced them.

### Rehearse without a mail provider

```bash
docker compose --profile mailpit -f docker-compose.selfhost.yml up -d
# .env: LUMNIK_SMTP_HOST=mailpit  LUMNIK_SMTP_PORT=1025  LUMNIK_SMTP_MOCK=false
docker compose -f docker-compose.selfhost.yml up -d hub
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
<p>N° <strong>{payload.fields.num ?: '—'}</strong> · {payload.fields.montant ?: '—'} €</p>
```

Rebuild the hub (templates ship in the image), then point the rule at it:
`lm notify add --events decision.commande-bloquee --to adv@example.com --template commande-courte`.

## See also

- [Outputs — the roads out](outputs.md) — the full wiring manual, registry included
- [Processes — react](apps/processes.md) — where `decision.*` and its `message` are declared
- [Deploy — turn on email delivery](deploy.md#turn-on-email-delivery) — SMTP knobs & Mailpit
