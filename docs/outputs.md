# Outputs — the roads out

How to wire what leaves the hub: a webhook to n8n or Teams, a mail, a pull. Why and when:
[How lumnik breathes](respiration.md). In `lm ui`: `:integrations`, `:notify` and `:outputs`
([the terminal console](cli/tui.md)).

| Command | Roles needed |
|---|---|
| `lm integration`, `lm outputs` | `lm_integrator` **and** `lm_admin` |
| `lm notify` | `lm_admin` |
| `lm inbox` | `lm_user` |

```
$ lm integration list          # as a user holding lm_admin but not lm_integrator
Error: API error 403: missing role lm_integrator
```

**Wiring is tenant-wide**: a webhook or an email rule receives every matching event of the tenant.
An admin holding any `scope:*` role is refused `lm integration`, `lm notify`, `lm outputs` and the
live event stream with `403 {"error":"scope not granted"}`.

## The three roads out

| Road | Carries | Command family |
|---|---|---|
| **Webhook** | every subscribed event, signed, retried, journaled, replayable | `lm integration` |
| **Email** | events matching a rule, one mail per event, best-effort | `lm notify` |
| **Pull** | the decision pile and the delivery journal, when you come asking | `lm inbox` · `lm outputs` |

## Wire a webhook

```bash
echo -n "$SIGNING_SECRET" | lm integration add crm-bridge \
  --url https://n8n.example.com/webhook/lumnik \
  --events 'decision.*' \
  --secret-stdin
lm integration test <ID>          # fires a real POST at the target, result inline
```

- `--events` takes exact types or a prefix ending in `.*` (default `decision.*`). The
  [registry below](#the-event-registry) is the vocabulary.
- The signing secret rides **stdin, never argv** — it is stored encrypted and never
  returned by `list`/`describe`.
- `test` fires a catalog event (`--event`, default `org.created`); `decision.*` types are
  dynamic and not testable this way — trigger the rule instead.
- Forwarding starts **at registration** — nothing is delivered retroactively.
- Without `--secret-stdin` the hub refuses the registration (`400`) and stores nothing.
- `list` and `describe` show the target's **scheme and host only**: a webhook URL's path
  and query are often its credential. `-o json` / `-o yaml` print the hub's body as it is,
  full URL included — that is the machine contract, and you asked for it.

```
$ echo -n "$SIGNING_SECRET" | lm integration add n8n \
    --url https://n8n.example.com/webhook/lumnik --events 'decision.*' --secret-stdin
registered: n8n (id 1001) — forwards subscribed events from now on
$ lm integration list
ID    NAME  URL                      EVENTS      ENABLED
1001  n8n   https://n8n.example.com  decision.*  true
```

### What arrives, and how to verify it

One HTTP `POST` per event, JSON payload as in the registry, with three headers:

```
X-Lumnik-Event: decision.commande-bloquee
X-Lumnik-Event-Id: 3639
X-Lumnik-Signature: sha256=<hex HMAC-SHA256 of the raw request body, keyed with your secret>
```

- **Verify:** recompute the HMAC over the **raw body bytes** and compare; reject on mismatch.
  n8n and Make have HMAC-verification nodes — give them the value after `sha256=`.
- **`X-Lumnik-Event-Id`** is the id `lm inbox` shows, and the `decision_id` a later
  `annotation.decision.acked` carries. A replay keeps it. It is **not** signed.

### Delivery semantics

The outbox is swept every **5 s**; a failed delivery is retried by a **30 s** sweep, **3
attempts in total**, per target — a target that already succeeded is not sent the event again.
Delivery is at-least-once: a hub stop mid-send can deliver an event twice, so deduplicate on
`X-Lumnik-Event-Id`.

Every attempt lands in the journal:

```bash
lm outputs list                   # deliveries across every integration, failures first
lm integration describe <ID>      # one target's registration; :integrations adds its recent deliveries
```

A decision fired while the target was down — the journal says so, failures first:

```
$ lm outputs list
ID  INTEGRATION     EVENT                   STATUS       WHEN              ERROR
41  ops-automation  decision.order-on-hold  no response  2026-09-05 05:40  ConnectException: null
```
A delivery that exhausted its attempts can be **replayed**: `lm outputs replay ID` (or `^R` on
`:outputs` in `lm ui`).

| What | Kept | Setting | Purged |
|---|---|---|---|
| delivery journal (payload + receiver's response) | 90 days; `0` = forever | `LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS` | daily, 05:00 — a purged delivery cannot be replayed; one still being retried is kept |
| events (the outbox) | 30 days | `LUMNIK_EVENTS_RETENTION_DAYS` | daily, 04:30 — never past what the slowest consumer has read |

## Route events to email

```
$ lm notify add --events 'decision.*' --to adv@example.com --template decision-alert
rule 1000: decision.* → adv@example.com
$ lm notify list
ID    EVENTS      RECIPIENT        TEMPLATE        ENABLED
1000  decision.*  adv@example.com  decision-alert  true
```

One mail per matching event, rendered through a template that can reach into the payload —
see [Email notifications](email.md). Mocked until the SMTP knobs are set; a failed send is not
retried — when delivery must be provable, use the webhook.

## How to…

### Wire n8n in two minutes

In n8n, add a **Webhook** node and copy its production URL. Then:

```bash
echo -n "$SIGNING_SECRET" | lm integration add n8n \
  --url https://n8n.example.com/webhook/lumnik --events 'decision.*' --secret-stdin
lm integration test <ID>
```

**Result:** the `test` fires a real `org.created` POST — the n8n execution list shows it
within seconds, with `X-Lumnik-Event` and the `sha256=` signature ready for an HMAC node.

### Replay a delivery that exhausted its attempts

```bash
lm outputs list                       # failures first — the ID column
lm outputs replay <ID>
```

**Result:** one fresh attempt at the same target with the same payload; the outcome lands
in the journal like any delivery.

### Nothing arrives — the four checks

1. **Registered after the event?** Forwarding starts at registration — nothing is
   retroactive. Trigger the event again.
2. **Watching `data.row.changed` on a REST/GraphQL/SOAP-ingested table?** Those tables emit
   no events ([named limits](respiration.md#named-limits-in-one-place)).
3. **Was anything subscribed at ingest time?** `data.row.changed` is only emitted when a
   subscription exists when the row lands — wire first, then re-run the ingestion.
4. **Is it the pipe or the event?** `lm integration test <ID>` proves the pipe;
   `lm outputs list` gives the journal's verdict on every real attempt.

## The event registry

The hub's governed vocabulary — what `--events` patterns match against and what each
payload carries. `decision.<name>` types are **dynamic**: your `kind: Process` rules and
dormancy watches mint them under the enforced `decision.` prefix.

??? note "Every event type, from the registry the hub ships (`events.yaml`)"

    ~~~~yaml
    --8<-- "lumnik-hub/src/main/resources/META-INF/events.yaml"
    ~~~~

Two emission rules worth planning around:

- **`data.row.changed`** is emitted only when an integration, rule or process subscribes to it,
  and only for jdbc, CSV and kafka ingestion ([named limits](respiration.md#named-limits-in-one-place)).
- **`decision.*` is the métier layer**: minted by [Processes](apps/processes.md) and
  [Workflow dormancy](apps/workflows.md), treated in the [Inbox](apps/inbox.md) —
  `annotation.decision.acked` closes the loop with who and when.

## See also

- [How lumnik breathes](respiration.md) — the story these roads serve
- [Processes — react](apps/processes.md) · [Workflows — declare](apps/workflows.md) · [Inbox — decide](apps/inbox.md)
- [Observability](operations/observability.md) — every consumer clock on one page
- [CLI reference](cli/lm-reference.md) — the full flag surface
