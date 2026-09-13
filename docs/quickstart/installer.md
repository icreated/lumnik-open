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

One live connector, rows you can see, and a first question answered in the scope you named —
see <a href="#what-makes-a-scope-answerable">what makes a scope answerable</a>.
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
cd lm && make install   # builds, then copies to ~/.local/bin (no sudo)
```

If the shell cannot find `lm` afterwards, `~/.local/bin` is not on your `PATH` — add it, or run
the binary in place as `./lm`.

> Pre-built binaries (GitHub Releases) and a Homebrew tap will arrive with the public opening —
> they are part of the publication ritual, not shipped yet.

### Run lumnik

See the [deploy docs](../deploy.md) — `deploy/selfhost/up.sh` for a single customer. It
generates every secret the hub needs into `.env` (mode 600), `LUMNIK_SECRET_MASTER_KEY`
included — nothing to set by hand, and nothing to regenerate.

> Running the JVM outside compose (systemd, Kubernetes)? Set `LUMNIK_SECRET_MASTER_KEY`
> yourself (`openssl rand -base64 32`) — the hub refuses to start without it.

!!! info "Three gestures are not in the open edition"
    `lm ask`, `lm source schema` and `lm source rediscover` answer `404` on an open-edition
    hub — the chat lives in the closed edition. Everything else on this page runs there.
    See [which edition you installed](../editions.md#which-edition-am-i-running).

### Point `lm` at the hub

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login   # prints a URL + device code — demo user: integrator (password: INTEGRATOR_PASSWORD in .env)
```

`lm login` prints a link and a code, then waits — open the link in any browser, sign in as
`integrator`, and the terminal continues on its own:

```
$ lm login
To sign in, open:
  http://localhost:8180/realms/lumnik/device?user_code=EYQP-LLJN
and enter code: EYQP-LLJN

Waiting...
```

The round trip is proven when a listing answers:

```
$ lm connector list
NAME  TYPE  TENANT  SCHED  WINDOW  STATUS  LAST RUN
```

An empty table is the right answer on a fresh hub — nothing is registered yet.

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
#    No value on the command line: it prompts, and what you type is not shown
lm secret set STRIPE_API_KEY

# 2. Generate the manifest from Stripe's OpenAPI — -o keeps the file so you can trim it
#    (add --apply to skip the file and post it straight to the hub)
#    (Stripe publishes the spec on GitHub; api.stripe.com/openapi.json is NOT a URL — 404)
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing -o stripe.yaml

# 3. Check it before you apply it
lm validate -f stripe.yaml
```

!!! note "Validate stops at the envelope"

    If `kind` or `metadata.connector_type` is wrong, validate reports only that. Fix it and
    run validate again to see the rest.

That spec is about 8 MB and lists Stripe's whole surface, so the generated manifest is long —
budget a minute for the fetch and the read, and expect to delete most of the endpoints before
you apply. This scenario is not the 30-second one; [Scenario B](#scenario-b-one-shot-csv_1) is.

Once it validates, `lm apply -f stripe.yaml` upserts it and **`lm connector run stripe`**
triggers the first ingestion. Two things that cost an hour if you learn them the hard way:

- **Endpoint ids are slugged from the whole path**, not the resource: Stripe's `/v1/charges`
  becomes `v1-charges`. The gesture is `lm endpoint disable stripe/v1-charges`, never
  `stripe/charges` — lookup is exact-match and an unknown name is simply not found.
- **`lm endpoint enable` starts nothing** — it only flips a flag. Scheduled ingestion needs
  `spec.schedule` *and* an enabled connector (`lm connector resume` if it was registered
  dormant by a one-shot); `lm connector run` needs neither. Use `lm endpoint disable` to
  narrow a noisy import.

Then ask **in the scope you declared** — the same word you gave `from-openapi`. Without
`--scope`, the ask goes to your default scope (`lm scope`) — `system` on a fresh install —
where this connector put nothing:

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
lm csv ingest customers.csv --scope sales
# askable at once in that scope — the last line of the output gives the question to try
```

What it prints, on a three-row stock file:

```
$ lm csv ingest stock.csv --scope retail --name stock-count
Ingested stock.csv into ext.stock_count
  • rows written:    3
  • promoted cols:   4
      - sku (text)
      - label (text)
      - qty (bigint)
      - warehouse (text)

  • askable:         scope 'retail' — try: lm ask --scope retail "how many rows are in ext.stock_count?"
A dormant connector is registered — see `lm connector list`.
```
The one-shot writes the rows into an `ext.<name>` table and registers a dormant connector —
nothing runs again on its own, and no run appears in `lm run list`. The scope is askable
right away, on an edition that carries the chat. Promotion to recurring ingestion comes
[after the demo](#from-demo-to-production).

## Scenario C — combined

Needs the `stripe.yaml` Scenario A generated — see [Scenario A](#scenario-a-stripe-via-openapi).

```bash
lm secret set STRIPE_API_KEY    # prompts; the value never reaches your shell history
lm apply -f stripe.yaml        # the REST manifest from Scenario A
lm csv ingest contacts.csv --scope billing
lm connector run stripe        # apply enabled the endpoints; this is what starts a run
# the CSV half is askable at once; the API half joins the scope once this run completes
```

Both halves land in the same scope on purpose — `--scope billing` on the connector's
`from-openapi` and on the ingest — which is what lets one question span the API and the file.

## Scenario D — nightly SFTP export

The most common shape: a customer ERP exports to an SFTP server every night.

```bash
lm secret set ACME_SFTP_KEY -      # paste the private SSH key on stdin, then Ctrl+D (PowerShell: Ctrl+Z, Enter)
lm csv ingest sftp://lumnik@sftp.acme.io/export/billing-latest.csv \
    --key-secret ACME_SFTP_KEY --scope billing --name acme-billing
# askable at once; recurrence is what the promotion below adds
```

```
$ lm secret set SFTP_EXPORT_PASSWORD --description "nightly export server"
Value for SFTP_EXPORT_PASSWORD: 
Created secret "SFTP_EXPORT_PASSWORD"
$ lm secret list
NAME                           ROTATED_AT                     DESCRIPTION
SFTP_EXPORT_PASSWORD           -                              nightly export server
```

The value is never printed back — not by `list`, not by any API. Lose it, write it again.
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
$EDITOR acme-nightly.yaml   # a csv-file manifest: metadata.name: acme_billing (the name
                            # lm connector list shows), metadata.scopes: [billing] (or the
                            # tables stay un-askable — validate warns), spec.transport
                            # (kind: sftp, host:, user:, path: /export/*.csv,
                            # key_env: ACME_SFTP_KEY, host_key_fingerprint: "SHA256:…" —
                            # refused without it), spec.parser, spec.target: {},
                            # spec.schedule: "0 6 * * *", after_process: move,
                            # archive_path: /archive/
lm validate -f acme-nightly.yaml   # names any block you left out, field by field
lm apply -f acme-nightly.yaml
lm connector resume acme_billing   # apply upserts by name and never flips enabled: the connector
                                   # the one-shot registered stays dormant until you resume it
```

## Scenario E — S3 prefix

```bash
lm secret set AWS_ACCESS_KEY_ID          # both prompt; neither value is echoed
lm secret set AWS_SECRET_ACCESS_KEY
lm csv ingest s3://acme-exports/billing/ \
    --region eu-west-3 --suffix .csv --scope billing
# askable at once in that scope
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
lm secret set STRIPE_API_KEY
# (prompts: type or paste the value, it is not shown)
```

Keeps the value out of shell history and `ps -ef` — passing it as an argument still works, and
says so on stderr. For a value that spans lines (a private SSH key) use `lm secret set NAME -`
and end with Ctrl+D — **Ctrl+Z then Enter in PowerShell**; that form reads until EOF, and warns
that a terminal shows what you paste. The CLI names the right gesture for the shell you are in.

### Production security

- **Master-key rotation**: planned for v1.1 (`lm secret rotate-master-key`)
- **Audit**: every secret write, and every read from the database, emits a
  `platform.secret.{created,updated,deleted,read}` event
- **RBAC**: creating, updating and deleting secrets requires the realm role `lm_admin`
- **No REST endpoint ever returns a secret's value** — if the installer loses it, they rewrite it

## How to…

### Show the customer what the demo actually read

```bash
lm connector list                  # the registered names
lm connector run <connector>       # nothing runs on its own until spec.schedule is set
lm run list --connector <connector>
lm run get <ID>
```

```
$ lm connector list
NAME          TYPE      TENANT  SCHED  WINDOW  STATUS     LAST RUN
customers     csv-file  1                      Completed  2026-09-05 05:38
orders        csv-file  1                      Completed  2026-09-05 05:38
products      csv-file  1                      Failed     2026-09-05 05:38
crm-contacts  csv-file  1                      Completed  2026-09-05 05:39
$ lm run list --connector orders
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
f0466804  orders     default   Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
$ lm run get f0466804
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
f0466804  orders     default   Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
⚠ 2 rows quarantined — see: lm dlq list --run f0466804-0824-4eb4-8aa2-54e263dccafe
```
**Result:** one row per connector run — `IN`, `OUT`, `SKIPPED`, `DEAD-LETTERS` — and the
arithmetic `IN = OUT + SKIPPED` holds on every run, so every row read is accounted for,
written or dropped. `lm run get` also names a failure and points at `lm dlq list --run <ID>`
when rows were quarantined.

Two ways to end up staring at an empty table:

- **No run has happened.** Scheduled runs need `spec.schedule` *and* an enabled connector;
  apply never flips `enabled`, so a connector registered dormant by a one-shot needs
  `lm connector resume <name>` too. `lm connector run <name>` starts one by hand regardless.
- **You took a one-shot path.** `lm csv ingest` records no run — Scenarios B, D and E leave
  `lm run list` empty by design.

### Take the demo back off the customer's machine

```bash
lm connector list               # the registered (slugified) names
lm connector delete acme_billing
```

```
$ lm connector delete stock_demo
deleted: stock_demo
```

One gesture takes everything with it: the connector, its endpoints, its hub table
(`connector.t_<name>` for a manifest connector, `ext.<name>` for a one-shot) and every RAG
chunk it produced. A connector whose table feeds a [fused entity](../entities.md) is the
exception — the entity's view depends on the table, so the delete is **refused** and names
the entity to remove first:

```
$ lm connector delete cust-csv
Error: API error 409: table connector.t_cust_csv feeds entity customer — remove the entity first (lm entity delete customer)
$ lm entity delete customer
deleted: customer
$ lm connector delete cust-csv
deleted: cust-csv
```

(The entity name in the message is lowercase — the hub derives it from the view name
`connector.v_customer`, and `lm entity delete` resolves the name case-insensitively.)

## What makes a scope answerable

`lm ask` always asks in **analytic** mode, and the analytic pipeline's first act — before any
model is touched — is to load a **schema card** for the scope. No card, no answer:
the ask refuses immediately.

A card is written three ways: when a **connector run completes** with a new or changed
table (Scenario A, after its first run), when a **one-shot `lm csv ingest` commits**
(Scenarios B, D and E — askable the moment the command returns), and when an
[entity](../entities.md) is applied over the scope. Nothing to configure: the card appears
on its own, and `lm source schema --scope <tag>` shows what it holds.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Bearer token unavailable (env=STRIPE_API_KEY)` | secret set neither in DB nor as an env var | `lm secret list` then `lm secret set STRIPE_API_KEY` |
| `apply failed: …` (400) right after `from-openapi --apply` | the hub refused the manifest — a hand-edit broke it, or `metadata.tenant` was added and does not match your own | generate with `-o` instead, then `lm validate -f` to see which field the hub names |
| a second connector appears after `lm apply -f` | `metadata.name` did not match the registered (slugified) name, so apply created rather than updated | `lm connector list` for the real name, `lm connector delete` the duplicate, re-apply |
| `429 Too Many Requests` from the vendor | too many calls for the quota | edit `default_rate_limit: 50/s` in the YAML, then `lm apply -f` |
| `lumnik.secret.master-key is too short` at JVM startup | env var missing or < 16 chars | regenerate with `openssl rand -base64 32` + export; restart the JVM |
| `secret with this name already exists` on `secret set` | the create door refuses a name a live secret holds; `lm` falls back to an update, a direct `POST` does not | nothing to do through `lm`. To replace rather than rotate: `lm secret rm NAME --force`, then `set` — a removed name is free again |
| `403 {"error":"scope not granted"}` on `lm apply` | your user holds `scope:` roles and the manifest declares a scope you do not hold (or none) | declare only scopes you hold, or apply as a user with no `scope:` role |
| `WARN metadata.scopes — no scopes declared` on validate | the manifest names no métier, so its tables will not be askable | add `scopes: [<scope>]` under `metadata` |

For connector details: [REST](../connectors/rest.md).
