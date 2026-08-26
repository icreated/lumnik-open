# Installer — 60-second Quickstart

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Quickstart</p>
<p class="lm-lead" markdown="span">Show customer-visible value in under a minute, from data already living somewhere in the customer's estate. This page is for system integrators and installers — ERP, LIMS, or a folder of CSV exports — who want to show value before explaining architecture.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Audience</strong>

System integrators and installers.
</div>
<div class="lm-meta-item" markdown>
<strong>Outcome</strong>

One live connector and rows you can see. The question comes after you promote it — see
<a href="#what-makes-a-scope-answerable">what makes a scope answerable</a>.
</div>
<div class="lm-meta-item" markdown>
<strong>Before this page</strong>

The hub should already be [running](../deploy.md).
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Fastest demo path</strong>

CSV or REST first. Recurrence and polish come after proof.
</div>
</div>
</section>

## Once per workstation

### Build `lm`

`lm` builds from source (Go 1.25+ — the floor is `lm/go.mod`) — today's official install path:

```bash
cd lm && go build -o lm ./cmd/lm
# then put ./lm on your PATH, e.g.:  sudo mv lm /usr/local/bin/
```

> Pre-built binaries (GitHub Releases) and a Homebrew tap will arrive with the public opening —
> they are part of the publication ritual, not shipped yet.

### Run lumnik

See the [deploy docs](../deploy.md) (Docker Compose recommended for a single customer). The
lumnik JVM must have `LUMNIK_SECRET_MASTER_KEY` set at startup (validated — the JVM refuses to
start without it).

> **Already done by `up.sh`.** If you came from the Deploy page, `deploy/selfhost/up.sh`
> generated `LUMNIK_SECRET_MASTER_KEY` into `.env` (mode 600) — do not regenerate it. The manual
> recipe below is only for the non-compose paths:
>
> - **systemd**: `openssl rand -base64 32` → `EnvironmentFile=/etc/lumnik/.env`
> - **Kubernetes**: a Secret mounted as an env var

!!! info "Three gestures here are not in every build"
    The ask (`lm ask`, once a scope is answerable) posts to `POST /api/platform/rag/chat`;
    `lm source schema`, in
    [Scenario A](#scenario-a-stripe-via-openapi)'s troubleshooting note, calls
    `GET /api/platform/rag/schema`; and so does the `lm source rediscover` that same command
    prints when a card comes back empty. All three doors live in `lumnik-rag` — a module the
    **open edition does not carry**, along with every `ChatPort`/`EmbeddingPort`
    implementation, which lives in `lumnik-llm-langchain`. The hub carries a resource on that
    same `/api/platform/rag` base path, but it serves only `/status` and `/reindex`, so on an
    open build those three answer `404` — including the two you reach for when an ask returns
    nothing. An open-edition install runs every other gesture on this page: it ingests,
    promotes, maps, runs and serves.
    See [which edition you installed](../editions.md#which-edition-am-i-running).

### Point `lm` at the hub

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login   # prints a URL + device code — demo user: integrator (password: INTEGRATOR_PASSWORD in .env)
```

Every scenario below assumes this context: on the deployed stack, **each `lm` call is
Bearer-authenticated** — without `lm login`, everything returns `401`.

## Choose a demo shape

<!-- The `_1` in three of the card hrefs below is POSITIONAL, not decoration. Those cards'
     <h3> text matches a `##` section further down, and mkdocs suffixes the SECOND heading it
     sees with the same slug. The cards come first, so the SECTION is the one that gets `_1`.
     Rename either heading of a pair, or reorder this grid, and the suffix moves — the card
     link then lands on the card itself. If you change a card title, read the id back out of
     the built HTML. scripts/check-docs-render.sh catches this (`make docs-build`, and the
     docs-publish workflow). -->
<div class="lm-grid" markdown>
<article class="lm-card" markdown>
<div class="lm-kicker">2 min</div>
<h3><a href="#scenario-a-stripe-via-openapi">Scenario A — REST via OpenAPI</a></h3>

Generates a manifest from an OpenAPI spec. It applies as generated — but a big spec yields a long one, so you trim the endpoints you don't want first.
</article>
<article class="lm-card lm-card--accent" markdown>
<div class="lm-kicker">Start here · 30 sec</div>
<h3><a href="#scenario-b-one-shot-csv_1">Scenario B — one-shot CSV</a></h3>

Best when the customer can hand you one export immediately — and the shortest path from nothing to an answer.
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">90 sec</div>
<h3><a href="#scenario-c-combined_1">Scenario C — combined</a></h3>

Best when one question should span an API and a file at once — needs Scenario A's manifest first.
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">90 sec</div>
<h3><a href="#scenario-d-nightly-sftp-export">Scenario D — nightly SFTP</a></h3>

Best when the customer already has a scheduled export ritual.
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">60 sec</div>
<h3><a href="#scenario-e-s3-prefix_1">Scenario E — S3 prefix</a></h3>

Best for object-store exports or S3-compatible storage like MinIO or R2.
</article>
</div>

## Scenario A — Stripe via OpenAPI

```bash
# 1. Register the API key (AES-256-encrypted in DB via pgcrypto)
lm secret set STRIPE_API_KEY sk_test_xxx

# 2. Generate the manifest from Stripe's OpenAPI — -o keeps the file so you can trim it
#    (add --apply to skip the file and post it straight to the hub)
#    (Stripe publishes the spec on GitHub; api.stripe.com/openapi.json is NOT a URL — 404)
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --tag billing -o stripe.yaml

# 3. Check it before you apply it
lm validate -f stripe.yaml
```

!!! note "Validate answers in two passes when the envelope is wrong"

    The envelope (`kind`, `metadata.connector_type`) is checked first and validation
    **returns there** if it found anything — so a manifest you edited by hand can report its
    envelope faults on one run and its endpoint faults only on the next. A generated manifest
    passes both, but the two-pass shape is worth knowing before you start editing.

That spec is about 8 MB and lists Stripe's whole surface, so the generated manifest is long —
budget a minute for the fetch and the read, and expect to delete most of the endpoints before
you apply. This scenario is not the 30-second one; [Scenario B](#scenario-b-one-shot-csv_1) is.

Once it validates, `lm apply -f stripe.yaml` upserts it and **`lm connector run stripe`**
triggers the first ingestion. Two things that cost an hour if you learn them the hard way:

- **Endpoint ids are slugged from the whole path**, not the resource: Stripe's `/v1/charges`
  becomes `v1-charges`. The gesture is `lm endpoint disable stripe/v1-charges`, never
  `stripe/charges` — lookup is exact-match and an unknown name is simply not found.
- **`lm apply` already enables every endpoint it writes**, and `lm endpoint enable` only
  flips that flag — it starts nothing. A connector ingests on a schedule only when its
  `spec.schedule` cron is set **and the connector itself is enabled**; apply creates a *new*
  connector enabled, so on this path both hold, while one that arrived dormant by another road
  needs `lm connector resume` as well. A run you ask for by hand needs neither. Use
  `lm endpoint disable` to narrow a noisy import instead.

Then talk to the data — **naming the scope**. A connector's tags *are* its scopes, so the
`--tag billing` above is what `--scope billing` here reaches; an ask with no `--scope` goes
to the `system` corpus, which is not where this connector put anything:

```bash
lm ask "how many charges above 1000?" --scope billing
# answers once the connector has COMPLETED a run — the run registers the ext.* table
# and rebuilds the scope's schema card ("What makes a scope answerable" below)
```

!!! note "A scope answers only once it holds a schema"

    Ask-to-SQL reads a *schema card* built per scope, not the raw tables. If an ask comes
    back with nothing to query, look before you guess:

    ```bash
    lm source schema --scope billing
    ```

    It prints what the card holds, and when the card is empty it prints the command that
    rebuilds it. Applying an entity over the scope also rebuilds it.

## Scenario B — one-shot CSV

```bash
lm csv ingest customers.csv --scope sales --tag demo
# (a one-shot leaves the scope un-askable — see below)
```

The ingest creates a dormant connector (`enabled=false`, no scheduler) + endpoint + an
`ext.<name>` table. It indexes no RAG corpus — the one-shot writes rows, not chunks.
Promotion to recurring ingestion comes
[after the demo](#from-demo-to-production).

## Scenario C — combined

Needs the `stripe.yaml` Scenario A generated — see [Scenario A](#scenario-a-stripe-via-openapi).

```bash
lm secret set STRIPE_API_KEY sk_test_xxx
lm apply -f stripe.yaml        # the REST manifest from Scenario A
lm csv ingest contacts.csv --scope billing
lm connector run stripe        # apply enabled the endpoints; this is what starts a run
# (neither half of this scenario makes the scope answerable — see below)
```

Both halves land in the same scope on purpose — the connector's `--tag billing` and the
ingest's `--scope billing` — which is what lets one question span the API and the file.

## Scenario D — nightly SFTP export

The most common shape: a customer ERP exports to an SFTP server every night.

```bash
lm secret set ACME_SFTP_KEY -      # paste the private SSH key on stdin, Ctrl+D
lm csv ingest sftp://lumnik@sftp.acme.io/export/billing-latest.csv \
    --key-secret ACME_SFTP_KEY --scope billing --name acme-billing
# (ask only after you promote it and it runs — see below)
```

To switch to daily recurrence you **declare** it: the one-shot leaves no manifest behind and
nothing exports one, so you write the `csv-file` manifest yourself — fields on
[CSV & files](../connectors/csv.md) — and put it through the same
[validate → apply model](../how-it-works.md) every manifest follows.

**Reuse the exact registered name or you get two connectors.** The one-shot slugifies
`--name`: every run of non-alphanumerics becomes `_`, so `acme-billing` was registered as
**`acme_billing`**. Apply matches on that name exactly; a near-miss creates a *second*
connector, enabled from birth, and both then ingest the same files. Confirm with
`lm connector list` before you write the manifest.

```bash
$EDITOR acme-nightly.yaml   # a csv-file manifest with metadata.name: acme_billing — the name
                            # lm connector list shows. spec.transport (kind: sftp, host:, user:,
                            # path: /export/*.csv, key_env: ACME_SFTP_KEY,
                            # host_key_fingerprint: "SHA256:…" — the connector refuses to
                            # connect without it), spec.parser and
                            # spec.target are all required blocks; spec.schedule: "0 6 * * *",
                            # after_process: move, archive_path: /archive/
lm validate -f acme-nightly.yaml   # names any block you left out, field by field
lm apply -f acme-nightly.yaml
lm connector resume acme_billing   # apply upserts by name and never flips enabled: the connector
                                   # the one-shot registered stays dormant until you resume it
```

## Scenario E — S3 prefix

```bash
lm secret set AWS_ACCESS_KEY_ID AKIA...
lm secret set AWS_SECRET_ACCESS_KEY ...
lm csv ingest s3://acme-exports/billing/ \
    --region eu-west-3 --suffix .csv --scope billing
# (a one-shot leaves the scope un-askable — see below)
```

Those two secret names are not decoration — they are the **defaults** the S3 source looks up
(`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`), which is why no flag references them. If the
customer's credentials live under any other name, say so explicitly:

```bash
lm csv ingest s3://acme-exports/billing/ --region eu-west-3 --scope billing \
    --access-key-secret ACME_S3_KEY --secret-key-secret ACME_S3_SECRET
```

For MinIO, Cloudflare R2, or GCS-S3-compatible endpoints, add `--endpoint https://...`.

## From demo to production

### Customize the generated manifest

```bash
$EDITOR stripe.yaml         # the file Scenario A's -o kept — set spec.schedule, promote
                            # columns, adjust pagination, etc.
lm validate -f stripe.yaml  # optional but recommended — field-by-field errors before apply
lm apply -f stripe.yaml
```

`spec.schedule` is the cron the hub's scheduler selects on. A `default_schedule` key inside
the connector config is read by nothing and schedules nothing.

Generate with `-o` or you lose the manifest: `lm describe` reads connector-type *schemas*
(`lm describe connector-type csv-file`), never an applied connector.

### Promote the CSV to recurring ingestion

Coming in v1.1 via `lm source csv add --path /watched/*.csv --schedule "*/5 * * * *"`.

Until then the escape hatch is the one in
[Scenario D](#scenario-d-nightly-sftp-export): write the `csv-file` manifest under the
connector's own `metadata.name`, `lm apply -f` it, then `lm connector resume` it.

### Enter a secret without a shell trace

```bash
lm secret set STRIPE_API_KEY -
# (then paste the value on stdin, end with Ctrl+D)
```

Keeps the value out of shell history and `ps -ef`.

### Production security

- **Master-key rotation**: planned for v1.1 (`lm secret rotate-master-key`)
- **Audit**: every secret **write** (create/update/delete), plus each read that hits the
  database, emits `platform.secret.{created,updated,deleted,read}` events
  into the outbox (SOC 2)
- **RBAC**: create/update/delete require the realm role `lm_admin` (`@RequiresPermission("lm_admin")` on `SecretResource`, matched flat against the JWT's roles — there is no separate permission registry)
- **No REST endpoint ever returns a secret's value** — if the installer loses it, they rewrite it

## How to…

### Show the customer what the demo actually read

```bash
lm connector list                  # the registered names
lm connector run <connector>       # nothing runs on its own until spec.schedule is set
lm run list --connector <connector>
lm run get <ID>
```

**Result:** one row per connector run — `IN`, `OUT`, `SKIPPED`, `DEAD-LETTERS` — and the
arithmetic `IN = OUT + SKIPPED` holds on every run, so every row read is accounted for,
written or dropped. `lm run get` also names a failure and points at `lm dlq list --run <ID>`
when rows were quarantined.

Two ways to end up staring at an empty table:

- **No run has happened.** The scheduler picks a connector up only when **two** things hold
  together: its `spec.schedule` cron is set *and* the connector is enabled. A manifest applied
  without a cron waits forever — and so does one whose cron you just added, if the connector
  was registered dormant by a one-shot, because apply upserts by name and never flips
  `enabled`. `lm connector resume <name>` is the other half. `lm connector run <name>` starts
  a run by hand without either — enabling an endpoint does not.
- **You took a one-shot path.** `lm csv ingest` is not a connector run: it prints its own
  summary (rows written, promoted columns) and records no run row, so Scenarios
  B, D and E leave `lm run list` empty by design.

### Take the demo back off the customer's machine

```bash
lm connector list                                     # the registered (slugified) names
lm endpoint purge acme_billing/acme_billing --force   # the one-shot's endpoint
lm connector delete acme_billing
```

**Result:** the purge deletes every `ext.*` row that endpoint wrote; the delete then takes
the connector row, its endpoints with it, its `connector.t_*` tables and its RAG chunks.
Order matters, and specifically for a **one-shot**: its `ext.*` table is not in the
dynamic-table registry, so the delete alone would leave that table behind — the purge is
the only handle on those rows, and deleting the connector first cascades the endpoints
away, taking that handle with them. (An *applied* REST connector's registered `ext.*`
tables are dropped by the delete itself.)

**Two endpoints, one purge.** A connector can carry more than one, and Scenario D produces
exactly that — but they do not come off the machine the same way. The one-shot registers its
endpoint under the *same* slugified name as its connector (hence `acme_billing/acme_billing`)
and writes into an `ext.<name>` table; that table is what the purge empties, and nothing else
touches it. The later `lm apply -f` of the recurring manifest registers a second endpoint
under the fixed name **`default`**, which writes into the dynamic table
`connector.t_acme_billing` — and `lm connector delete` drops that one itself. Do not reach for
`lm endpoint purge acme_billing/default`: that endpoint's config is `{"name": …}` with no
`target` key, so the call answers `400 no target.table found in endpoint config` and always
will.

What survives all of this is the empty `ext.<name>` table itself: the connector delete
deliberately drops only tables it created under `connector.t_`, so the shell of the one-shot
table stays until someone drops it in the database.

## What makes a scope answerable

`lm ask` always asks in **analytic** mode, and the analytic pipeline's first act — before any
model is touched — is to load a **schema card** for the scope. No card, no answer:
the ask refuses immediately.

Cards are written only when a **connector run completes and something changed in the dynamic-table
registry**. One shape on this page never reaches that:

- **A one-shot `lm csv ingest` records no run at all.** It registers a dormant connector and
  writes rows, and stops there — nothing to complete, nothing to refresh, so the scope
  stays un-askable until promoted to a real connector.

**Two paths work**: a connector writing to a dynamic table (`connector.t_*`) and a REST
connector writing a `hybrid` target (`ext.*`). Either way the recipe is the same: apply the
manifest, make sure the connector is enabled (`lm connector resume` if it arrived dormant),
run it, and the card appears on its own. That is the promotion described under Scenario D.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Bearer token unavailable (env=STRIPE_API_KEY)` | secret set neither in DB nor as an env var | `lm secret list` then `lm secret set STRIPE_API_KEY ...` |
| `apply failed: …` (400) right after `from-openapi --apply` | the hub refused the manifest — a hand-edit broke it, or `metadata.tenant` was added and does not match your own | generate with `-o` instead, then `lm validate -f` to see which field the hub names |
| a second connector appears after `lm apply -f` | `metadata.name` did not match the registered (slugified) name, so apply created rather than updated | `lm connector list` for the real name, `lm connector delete` the duplicate, re-apply |
| `429 Too Many Requests` from the vendor | too many calls for the quota | edit `default_rate_limit: 50/s` in the YAML, then `lm apply -f` |
| `lumnik.secret.master-key is too short` at JVM startup | env var missing or < 16 chars | regenerate with `openssl rand -base64 32` + export; restart the JVM |
| `secret with this name already exists` on `secret set` | transparent update not handled | `lm secret rm NAME --force` then `set`; or the CLI already falls back to update on 409 |

For connector details: [REST](../connectors/rest.md).
