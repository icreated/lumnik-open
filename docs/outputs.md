# Outputs — the roads out

Data comes in so that something can *leave*: a Teams message at 02:01, a mail in the ADV's
inbox, a row in your n8n flow. [How lumnik breathes](respiration.md) tells that story;
this page is the manual for wiring it. The wiring commands (`lm integration`, `lm notify`,
`lm outputs`) are `lm_admin` surface; reading the decision pile (`lm inbox`) is every app
user's (`lm_user`).

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

```
$ echo -n "$SIGNING_SECRET" | lm integration add n8n \
    --url https://n8n.example.com/webhook/lumnik --events 'decision.*' --secret-stdin
registered: n8n (id 1001) — forwards subscribed events from now on
$ lm integration list
ID    NAME  URL                                     EVENTS      ENABLED
1001  n8n   https://n8n.example.com/webhook/lumnik  decision.*  true
```

### What arrives, and how to verify it

One HTTP `POST` per event, JSON payload as in the registry, with three headers:

```
X-Lumnik-Event: decision.commande-bloquee
X-Lumnik-Event-Id: 3639
X-Lumnik-Signature: sha256=<hex HMAC-SHA256 of the raw request body, keyed with your secret>
```

Recompute the HMAC over the **raw body bytes** and compare — reject on mismatch. (n8n and
Make have ready-made HMAC-verification nodes; point them at the `sha256=`-stripped value.)
`X-Lumnik-Event-Id` is **not** covered by that signature — it rides alongside the signed
body, so treat it as best-effort correlation, not as a verified claim. It carries the
outbox event's own id, the same id `lm inbox` shows and a later ack echoes back as
`annotation.decision.acked`'s `decision_id` — store a `decision.*` delivery keyed by this
id and you can resolve its ack without asking lumnik again. A replay of a delivery
carries the same id as the original attempt.

### Delivery semantics

The outbox is swept every **5 s**; a failed delivery is retried by a **30 s** sweep, **3
attempts in total**, per-endpoint — an endpoint that already succeeded is never
re-delivered while another one retries. Every attempt lands in the journal:

```bash
lm outputs list                   # deliveries across every integration, failures first
lm integration describe <ID>      # one target: config + its recent deliveries
```

A decision fired while the target was down — the journal says so, failures first:

```
$ lm outputs list
INTEGRATION     EVENT                   STATUS       WHEN              ERROR
ops-automation  decision.order-on-hold  no response  2026-09-05 05:40  ConnectException: null
```
A delivery that exhausted its attempts stays in the journal and can be **replayed**:
`POST /api/platform/deliveries/{id}/replay`. The journal keeps each delivery's payload and the
receiver's response for 90 days (`LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS`, purged daily at 05:00,
`0` = forever): a purged delivery can no longer be replayed. A delivery the retry sweep still owns is
never purged. The outbox itself is purged daily (04:30,
`LUMNIK_EVENTS_RETENTION_DAYS`, default 30) — never past what the slowest consumer has seen.

## Route events to email

```
$ lm notify add --events 'decision.*' --to adv@example.com --template decision-alert
rule 1000: decision.* → adv@example.com
$ lm notify list
ID    EVENTS      RECIPIENT        TEMPLATE        ENABLED
1000  decision.*  adv@example.com  decision-alert  true
```

One mail per matching event, rendered through a template that can reach into the payload —
templates, variables and the delivery contract have [their own page](email.md). The short
version: mocked until the SMTP knobs are set, best-effort by design — when delivery must
be provable, use the webhook.

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
lm outputs list                       # failures first — note the delivery id
TOKEN=$(awk '/^ *access:/ {print $2; exit}' ~/.lm/config.yaml)
curl -s -X POST https://your-hub.example.com/api/platform/deliveries/<id>/replay \
  -H "Authorization: Bearer $TOKEN"
```

**Result:** one fresh attempt at the same target with the same payload; the outcome lands
in the journal like any delivery.

### Nothing arrives — the four checks

1. **Registered after the event?** Forwarding starts at registration — nothing is
   retroactive. Trigger the event again.
2. **Watching `data.row.changed` on a REST/GraphQL/SOAP-ingested table?** Those tables are
   event-silent today ([named limits](respiration.md#named-limits-in-one-place)).
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

- **`data.row.changed` is gated**: it is emitted (in the ingestion transaction) only when
  at least one integration, rule or process subscribes to it — and only for jdbc/CSV/kafka
  ingestion; REST/GraphQL/SOAP-ingested tables are event-silent today
  ([named limits](respiration.md#named-limits-in-one-place)).
- **`decision.*` is the métier layer**: minted by [Processes](apps/processes.md) and
  [Workflow dormancy](apps/workflows.md), treated in the [Inbox](apps/inbox.md) —
  `annotation.decision.acked` closes the loop with who and when.

## See also

- [How lumnik breathes](respiration.md) — the story these roads serve
- [Processes — react](apps/processes.md) · [Workflows — declare](apps/workflows.md) · [Inbox — decide](apps/inbox.md)
- [Observability](operations/observability.md) — every consumer clock on one page
- [CLI reference](cli/lm-reference.md) — the full flag surface
