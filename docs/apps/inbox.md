# Inbox — the human side of the decision

> The third rung: the tenant's recent `decision.*` events, newest first — what needs a human.
> And the disposal gesture: **ack**.

```bash
lm inbox                 # open decisions (ID, QUAND, PROCESS, MESSAGE, ENTITY_KEY)
lm inbox --limit 100     # 1..200, default 50
lm inbox --all           # include disposed ones, marked [traité]
lm inbox ack 42          # dispose of decision 42
```

A process watching an orders table for `status=ON HOLD` just fired; then a human disposes of it:

```
$ lm inbox
ID  QUAND        PROCESS        MESSAGE                                       ENTITY_KEY
50  à l'instant  order-on-hold  Order ORD-1011 for C-007 is on hold (860.00)  ef68325b1cfbee9eb96f42c72a48d21d2c53922f144a26b855a4142bfbb94949
$ lm inbox ack 50
acked.
$ lm inbox
ID  QUAND  PROCESS  MESSAGE  ENTITY_KEY
$ lm inbox --all
ID  QUAND  PROCESS        MESSAGE                                                ENTITY_KEY
50  1min   order-on-hold  Order ORD-1011 for C-007 is on hold (860.00) [traité]  ef68325b1cfbee9eb96f42c72a48d21d2c53922f144a26b855a4142bfbb94949
```

The inbox speaks French — `QUAND` (when) is a humanized age, `à l'instant` is "just now", and
`[traité]` marks a disposed entry. `ENTITY_KEY` is the row's content hash, printed whole.
In the TUI, `:inbox` shows the same fold live — `a` acks, `A` toggles disposed entries.

API: `GET /api/inbox?limit&all`, `POST /api/inbox/{id}/ack` — requires the `lm_user` role.
An id from another tenant is a plain `404` (no cross-tenant existence leak).

The inbox is **tenant-wide, not métier-scope-bound**: unlike views, entities, and chat
(which refuse with the uniform `403 {"error":"scope not granted"}` outside your scopes),
any `lm_user` of the tenant sees all its decisions. Per-métier bounding is a known
follow-up — the PWA's Décisions tab says so in its footer.

## The ack IS an event

Acking does not update a status column — it **emits** `annotation.decision.acked` into the same
outbox as everything else. The inbox you read is a *fold over the event log*: a decision is
"open" if no ack event follows it. There is no separate inbox state to migrate, back up, or
drift.

**Idempotent by construction**: the ack event carries a dedup key (`ack:<id>`) with
`ON CONFLICT DO NOTHING` — re-acking returns the same `200 {"acked": true}` and writes **zero**
new rows. Retry your script freely.

!!! note "Retention"
    The inbox reads the outbox log; entries age out with the event retention window
    (`lumnik.events.retention-days`, default 30). The inbox is a work surface, not an archive.

## Decision sources

Two things emit into the same `decision.*` outbox, and the inbox does not distinguish their
origin beyond the event type:

- **`decision.<process-name>`** — a [`kind: Process`](processes.md) reacting to a row landing
  (the end-to-end story: [How lumnik breathes](../respiration.md)).
- **`decision.dormant.<workflow-name>`** — a [`kind: Workflow`](workflows.md)'s dormancy sweep
  reacting to a row's *absence of movement*: a non-terminal document observed unchanged past
  its declared threshold. See [Dormancy](workflows.md#dormancy-the-memory-of-the-breathing) for
  the wording the message carries and why it never asserts a source-absolute duration.

## See also

- [Processes](processes.md) — where decisions come from.
- [Workflows](workflows.md) — declares what the values behind a decision mean.
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — how a
  decision here also reaches an external system, not just this list.
- The [WOW walkthrough](../wow.md) — the whole ladder in one thread: see → react → decide,
  with [declare](workflows.md) underneath, grounding all three.
