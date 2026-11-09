# When a consumer fails on an event

Three consumers read the event log in the background: **processes**, **webhooks** and **e-mail
notifications**. Each keeps its own place in the log. A failure never holds that place. The consumer
moves on, so one bad event cannot stop the others.

What it fails on is **kept**. It is a *dead letter*: the event, who failed on it, and why.

## What a letter says

| Field | Meaning |
|---|---|
| `consumer` | `process`, `webhook` or `email` |
| `eventType`, `entity`, `entityKey` | The event it failed on |
| `target` | What failed. Empty: the consumer failed on the whole event. `process:<name>`: that process failed, the others on the same event decided |
| `error` | The kind of failure: the SQLSTATE of a database error, else the exception's class. Never its message |
| `failedAt`, `replayedAt` | When it failed, and when it was redone |

The same event failing again reopens its letter. It does not add a second one.

## See them

The tenant's letters, newest first, the 200 most recent. `open=true` keeps the ones not replayed yet.

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$HUB/api/platform/events/dead-letters?open=true&consumer=process"
```

The door needs the integrator role, like the rest of `/api/platform`.

## Redo one

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$HUB/api/platform/events/dead-letters/42/replay"
```

Fix the cause first: the process, the endpoint, the database. The replay redoes the work the letter names, with the
event as it was first read. It stays valid after the log's retention.

| Answer | Meaning |
|---|---|
| `200` | Done. The letter is closed |
| `404` | No such letter in your tenant |
| `409` | Already replayed, or the consumer has no replay |
| `500` | The replay failed, or had nothing to redo: no process listens on the event any more, no webhook endpoint takes it, every recipient was mailed already. The letter stays open. The hub's log has the details |

A replay is safe to repeat. A process never writes a decision twice. A webhook or e-mail replay sends what the
event missed, and no second mail to a rule that already has one.

## What stays outside

- A webhook that is **delivered** and fails has its own retry, per endpoint, three attempts. It is not a dead letter.
- An e-mail that fails to **send** is retried, three attempts, then given up for that recipient alone. One that
  fails to **render** (a broken or unknown template) is given up at once. Neither is a dead letter.
  `lm notify deliveries` lists them, given-up ones first, with the kind of failure; `lm notify replay ID` sends one again.
- A mail may rarely be sent twice: if the hub stops right after the mail server accepted it.
- A process whose rule cannot be read is skipped and logged. It is a fault in the declaration, not in the event.
- A letter is purged once the event log's retention has passed (30 days by default), replayed or not. Look at the open ones before.
