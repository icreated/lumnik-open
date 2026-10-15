# Where lumnik stands

**beta.** Between the `v1.7.3` tag (20 September 2026) and `v1.8.0` (15 October 2026): 240 pull
requests and 1 212 commits. **370 of those commits are fixes**, against 233 additions. The additions
are new surfaces this time:
- [entity resolution](entities-resolution.md), one identity across systems that never shared a key;
- [managed exports](apps/exports.md), lumnik's first outbound path;
- [queued runs](operations/upgrade.md#turning-on-the-run-queue);
- views that read past their old 1 000-row cut.

What is not here is not here on purpose, and the page that owns each gap says so in its own words.

What we do not know yet: **nobody outside this team has run lumnik in production.**

That paragraph is checkable, which is why it is written that way — the evidence is one command:

```bash
git log v1.7.3..v1.8.0 --no-merges --pretty=%s \
  | sed -E 's/^([a-z]+)(\(.*\))?:.*/\1/' | sort | uniq -c | sort -rn
```

*beta* is only the word sitting on top of that count. The word is the part that goes away first.

## What is deliberately not there

Every line below is a decision, not a backlog entry. Each is already declared where it belongs —
on the page for the surface that has it — and this table only **gathers** them, so the boundary
can be seen at once instead of met one page at a time.

| Surface | What is not there | Declared in |
|---|---|---|
| **GraphQL** | mutations, subscriptions, schema-introspection discovery, persisted queries, query batching, per-endpoint header overrides, `on_error: warn` | [GraphQL — Not yet supported](connectors/graphql.md#not-yet-supported) |
| **REST** | `mode: api_cursor` — declaring it today behaves like `full`; per-endpoint headers, which belong at `spec.headers` | [REST](connectors/rest.md) |
| **SOAP** | SOAP **1.1** envelopes only — a service that requires SOAP 1.2 (`application/soap+xml`) is refused | [SOAP](connectors/soap.md) |
| **Kafka** (Pro) | JSON only — no Avro, Protobuf or Schema Registry; one topic per connector, no filtering by key or header; no mapping block, so no hooks at ingestion; no pre-flight test; SASL/SCRAM, mTLS and OAuth | [Kafka — Limitations](connectors/kafka.md#limitations) |
| **Transformers** | `validate-row` after `split-row`, and `aggregator` on a `csv-file` run — both refused at `lm apply`, with the arithmetic reason | [Transformers](connectors/transformers.md) |
| **Events** | REST-, GraphQL- and SOAP-ingested tables are event-silent — the respiration rides jdbc, CSV and kafka ingestion | [How lumnik breathes](respiration.md) |
| **Chat** | declared status aliases feed the dormancy watch and the chat's background knowledge, **not** the chat's SQL or a view's filters — the chat matches the literal values in the column | [How lumnik breathes](respiration.md) |
| **Workflows** | the transition journal's retention is unbounded, and the dormancy sweep costs the database a full pass over the source table every tick — both on purpose in v1 | [Workflows](apps/workflows.md) |
| **Exports** | CSV only, one entity per export, `mode: snapshot` only (no delta, no resume); SFTP and S3 only; four formatters, a row filter with `equals`/`in` only; no back-fill of missed slots, no automatic retry | [Exports — Not in this version](apps/exports.md#not-in-this-version) |
| **Search** | a row deleted straight from an ingested table — or by `lm endpoint purge` on a `connector`-schema hybrid target — stays searchable until the index is rebuilt | [SearchIndex — Rows deleted directly in the database](search/search-indexes.md#rows-deleted-directly-in-the-database) |
| **PWA** | decisions are not bounded by métier — the tab shows the whole tenant's, and says so in its own footer | [The métier PWA](apps/pwa.md) |
| **SCIM** (Pro) | no `/Groups`; no bulk, sorting, etags or `changePassword`; no pagination beyond the two `eq` filters; not the full PATCH path grammar; no `/Schemas` or `/ResourceTypes` | [SCIM — Not supported](security/scim.md#not-supported) |
| **Identity** | SAML-only IdPs are not supported by direct trust — front them with a broker and point lumnik at the broker | [Bring your own IdP](security/byo-idp.md) |
| **Encryption** | rotating the **master** key. Rotating one secret's value is supported, and stamps `rotated_at` | [RLS and encryption](security/rls.md) |

Fourteen surfaces, fourteen decisions. Two of them — Kafka and SCIM — are not gaps at all but the
shape of the paid build; [The two editions](editions.md) draws that boundary properly.

## What is not finished

These are different in kind from the table above: started, known, and not yet closed. They are on
this page because **you may meet them**.

- **A few conflict refusals do not name what holds the name.** A `409` can tell you a name is
  taken without telling you which integration took it. Most now name it; the rest are on the list.
- **The live event tail is best-effort, and says so here rather than in a footnote.** An event's
  id is assigned at `INSERT` but only becomes visible at `COMMIT`, so a long transaction can
  commit a *lower* id after a higher one — and a live poll can miss the late arrival. That is
  acceptable for a live view, which is what it drives; durable delivery is the consumer-position
  model's job. Closing the window (overlap, then dedup) is a separate change, not yet built.
- **`lm secret rm` leaves a tombstone rather than deleting the row.** Whether it should be a real
  delete is an open product decision, not an oversight — the tombstones are unbounded and the
  stored value stays decryptable, which is exactly why the decision is still open.

## What we do not know yet

Everything above is knowledge — either a decision we made or a defect we can name. This section is
the absence of knowledge, and it is the honest reason the word *beta* sits at the top.

lumnik has been deployed, driven end to end, and hammered hard — by the people who wrote it. No
outside team has stood it up against their own ERP. That is the one thing no amount of bug-fixing
replaces: the first defect an outsider finds will be one we could not have predicted, because the
ones we could predict are already in the two lists above.

If you are that outsider, [the guided path](parcours.md) is three half-days, and it is the fastest
way to hand us a defect we have never seen.

## When the word goes away

Two things have to be true, and neither of them is a version number:

1. **What is not finished** is empty.
2. Someone outside this team has run lumnik in production.

Then `beta` disappears from the top of this page and the sentence underneath it stays. Nothing is
renamed and no tag is re-pointed — published tags are immutable here by policy, so the version
stream only ever moves forward. The word going away **is** the announcement.

## See also

- [The two editions](editions.md) — what each build contains, and how the boundary is enforced
- [Parcours — the guided path](parcours.md) — the three half-days
- [How lumnik works](how-it-works.md) — the mechanism behind all of it
