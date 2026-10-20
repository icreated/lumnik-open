# Architecture — the why

Every other page describes a *what*. This one lists the design commitments everything else
hangs from — the page to read when evaluating lumnik.

## Read-only first

Ingestion is one-way: there is no write path back to your systems — not behind a flag, not
for administrators. The hub lands data in its own PostgreSQL, and fusion, chat, apps and events
work on that copy. Your system of record stays the sole owner of its data.

The same posture extends downstream: the SQL that answers a chat question executes on
a **separate, physically read-only database connection** — see the next commitment.

## Isolation lives in the database

Multi-tenant isolation is not application code you have to trust; it is PostgreSQL
Row-Level Security, enforced by the layer that cannot be bypassed by a bug in the
layer above. Three database roles carry three trust levels:

- the request path runs **RLS-enforced**, and owns nothing;
- LLM-generated SQL runs on a role that can **only read**, with an empty
  `search_path`;
- deliberate cross-tenant work (migrations, purges) runs on a separate role that
  every code path must use *explicitly*.

The full mechanics — policies, the tenant GUC, what RLS does and does not protect
against — are on [RLS & database roles](security/rls.md).

## Refusal over guessing

The system prefers a refusal to a plausible guess:

- on the way **in**, a run's arithmetic always closes — a row is written, benignly
  skipped, or quarantined with its reason
  ([seeing what was ingested](transparency/ingestion-honesty.md));
- on the way **out**, deterministic guards stand between a question and its answer,
  and an empty result is never phrased as a confident "there are none"
  ([the five guards](transparency/ask-honesty.md));
- disagreements between sources are preserved in a ledger instead of silently
  resolved ([entities](entities.md)).

## Declared over inferred

Where meaning enters the system, a human declares it: connectors, entities, views,
processes and lifecycles are all **manifests** an integrator applies — validated,
refusable, versionable in git. A [workflow](apps/workflows.md) even records *how* its
lifecycle is known (`provenance: declared`), so the day something is inferred instead,
the difference will be citable — not retrofitted.

## One hub, optional extensions

```mermaid
flowchart LR
  subgraph core["the hub — runs on its own"]
    ing["connectors"] --> raw[("raw tables")]
    raw --> ent["entities"]
  end
  subgraph ext["extensions — discovered at boot"]
    rag["search · chat"]
    llm["LLM providers"]
  end
  ent -.->|"if present"| rag
  rag -.-> llm
```

A hub without the chat extension still ingests, fuses and serves. What needs a model says so:

```
$ lm view generate desk "les clients de Lyon" -o lyon.yaml
Error: API error 400: generation abstained: no chat model is available on this hub — view generation needs one
```

---

Ready to verify any of this against a running system? [Deploy](deploy.md) is one
command, and the [cheat sheet](cheat-sheet.md) is the minimum you need to poke at it.
