# Installer — 60-second Quickstart

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Quickstart</p>
<p class="lm-lead" markdown="span">Show the customer their own data in under a minute — from an ERP export, an API or a folder of CSV files.</p>
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

### Install `lm`

Take the archive for your platform from the
[Releases page](https://github.com/icreated/lumnik-open/releases/latest). Five are published:
macOS (Intel and Apple Silicon), Linux (x86-64 and arm64), Windows x86-64.

```bash
tar -xzf lm_<version>_darwin_arm64.tar.gz          # your platform's archive
install -d ~/.local/bin && install -m 0755 lm ~/.local/bin/lm
shasum -a 256 -c lm_<version>_checksums.txt --ignore-missing
```

- **Windows:** the archive is a `.zip` holding `lm.exe` — unpack it into a directory on your `PATH`.
- **Version numbers:** the archive carries `lm`'s own version, which is not the hub's release number.
- **From source** (Go 1.26+): `cd lm && make install` copies the binary to `~/.local/bin`, no `sudo`.

If the shell cannot find `lm`, add `~/.local/bin` to your `PATH`. Then check it:

```
$ lm version
lm version dev (commit 94ae18cc9667, built from 2026-10-19T05:34:15Z)
```

A source build prints `dev`, as above; a downloaded binary prints its release number instead. Put this line in a bug report.

### Run lumnik

Start the hub with `deploy/selfhost/up.sh` — see [Deploy](../deploy.md). It writes every
secret the hub needs into `.env`; there is nothing to set by hand.

> Running the hub outside compose (systemd, Kubernetes)? Set `LUMNIK_SECRET_MASTER_KEY`
> yourself (`openssl rand -base64 32`) — the hub refuses to start without it.

!!! info "`lm ask` needs lumnik Pro"
    `lm ask` and `lm chat` answer `404` on the open edition, and on a Pro hub with the AI
    switched off. Every other gesture on this page runs on both editions.
    See [which edition you installed](../editions.md#which-edition-am-i-running).

### Point `lm` at the hub

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login
```

`lm login` prints a link and a code, then waits. Open the link in any browser and sign in as
`integrator` (password: `INTEGRATOR_PASSWORD` in `.env`); the terminal continues on its own:

```
$ lm login
To sign in, open:
  http://localhost:8180/realms/lumnik/device?user_code=EYQP-LLJN
and enter code: EYQP-LLJN

Waiting...
```

Check the round trip — an empty table is the right answer on a fresh hub:

```
$ lm connector list
NAME  TYPE  TENANT  SCHED  WINDOW  STATUS  LAST RUN
```

Every scenario below needs this login: without it, every `lm` call answers `401`.

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

Generates a manifest from an OpenAPI spec. A big spec yields a long manifest: trim the endpoints you don't want before you apply it.
</article>
<article class="lm-card lm-card--accent" markdown>
<div class="lm-kicker">Start here · 30 sec</div>
<h3><a href="#scenario-b-one-shot-csv_1">Scenario B — one-shot CSV</a></h3>

Best when the customer can hand you one export immediately — the shortest path from nothing to an answer.
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
lm secret set STRIPE_API_KEY          # prompts; what you type is not shown

lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing -o stripe.yaml     # -o keeps the file; --apply posts it instead

lm validate -f stripe.yaml
lm apply -f stripe.yaml
lm connector run stripe               # applying declares it; this starts the first run
```

- Stripe's spec is about 8 MB: the manifest lists every endpoint. Delete the ones you don't
  want before `lm apply`.
- `validate` stops at the first envelope error (`kind`, `metadata.connector_type`). Fix it and
  run it again to see the rest.
- Endpoint ids come from the whole path: `/v1/charges` is `v1-charges`. Narrow a noisy import
  with `lm endpoint disable stripe/v1-charges` — `stripe/charges` is not found.
- `lm endpoint enable` and `disable` print nothing on success. They start no run.

Then ask **in the scope you declared** (Pro). Without `--scope`, the ask goes to your default
scope (`lm scope`), where this connector put nothing:

```bash
lm ask "how many charges above 1000?" --scope billing
# answers once the connector has completed a run
```

If the ask finds nothing to query, look at the scope — see
[what makes a scope answerable](#what-makes-a-scope-answerable).

## Scenario B — one-shot CSV

```bash
lm csv ingest customers.csv --scope sales
```

On a three-row stock file:

```
$ lm csv ingest stock.csv --scope retail --name stock-count
Ingested stock.csv into ext.stock_count
  • rows written:    3
  • promoted cols:   4
      - sku (text)
      - label (text)
      - qty (bigint)
      - warehouse (text)

A one-shot: no run recorded, nothing indexed for chat.
The rows are in ext.stock_count, and a dormant connector is registered — see `lm connector list`.
```

On a hub with the chat (Pro), the last two lines name the question to try instead:

```
  • askable:         scope 'retail' — try: lm ask --scope retail "how many rows are in ext.stock_count?"
A dormant connector is registered — see `lm connector list`.
```

The one-shot registers a dormant connector and records no run:

```
$ lm connector list
NAME         TYPE      TENANT  SCHED  WINDOW  STATUS  LAST RUN
stock_count  csv-file  1                              
$ lm run list
ID  CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED  ENDED
```

Nothing runs again on its own. To make it recurring, see [from demo to production](#from-demo-to-production).

## Scenario C — combined

Needs the `stripe.yaml` from [Scenario A](#scenario-a-stripe-via-openapi).

```bash
lm secret set STRIPE_API_KEY
lm apply -f stripe.yaml
lm csv ingest contacts.csv --scope billing
lm connector run stripe
```

Both halves carry `--scope billing`, so one question spans the API and the file. The CSV half is
askable at once; the API half once the run completes.

## Scenario D — nightly SFTP export

The most common shape: a customer ERP exports to an SFTP server every night.

```bash
ssh-keyscan sftp.acme.io | ssh-keygen -lf -    # read the server's SHA256:… fingerprint
lm secret set ACME_SFTP_KEY -                  # paste the private SSH key, then Ctrl+D (PowerShell: Ctrl+Z, Enter)
lm csv ingest sftp://lumnik@sftp.acme.io/export/billing-latest.csv \
    --key-secret ACME_SFTP_KEY --host-key-fingerprint "SHA256:…" \
    --scope billing --name acme-billing
```

Without `--host-key-fingerprint` the hub refuses to connect. A server reached by password
takes `--password-secret` instead of `--key-secret`:

```
$ lm secret set SFTP_EXPORT_PASSWORD --description "nightly export server"
Value for SFTP_EXPORT_PASSWORD: 
Created secret "SFTP_EXPORT_PASSWORD"
$ lm secret list
NAME                           ROTATED_AT                     DESCRIPTION
SFTP_EXPORT_PASSWORD           -                              nightly export server
```

No command ever prints a secret's value back. If it is lost, set it again.

### Make it nightly

The one-shot leaves no manifest behind: you write a `csv-file` manifest — fields on
[CSV & files](../connectors/csv.md) — and validate and apply it.

!!! warning "Reuse the exact registered name"
    The one-shot replaces each run of non-alphanumerics in `--name` with `_`: `acme-billing` is
    registered as **`acme_billing`**. A manifest under any other name creates a **second**
    connector, and both ingest the same files. Read the name in `lm connector list` first.

```yaml
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata:
  name: acme_billing                  # the name lm connector list shows
  connector_type: csv-file
  scopes: [billing]                   # without it the tables are not askable — validate warns
spec:
  schedule: "0 6 * * *"
  transport:
    kind: sftp
    host: sftp.acme.io
    user: lumnik
    path: /export/*.csv
    key_env: ACME_SFTP_KEY
    host_key_fingerprint: "SHA256:…"  # required unless strict_host_key: false — validate refuses it missing
  after_process: move
  archive_path: /archive/
```

```bash
lm validate -f acme-nightly.yaml       # names any block you left out, field by field
lm apply -f acme-nightly.yaml
lm connector resume acme_billing       # the one-shot registered it dormant; apply does not change that
```

## Scenario E — S3 prefix

```bash
lm secret set AWS_ACCESS_KEY_ID          # both prompt; neither value is echoed
lm secret set AWS_SECRET_ACCESS_KEY
lm csv ingest s3://acme-exports/billing/ \
    --region eu-west-3 --suffix .csv --scope billing
```

`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are the secret names the S3 source reads by
default. Credentials stored under other names are named on the command line:

```bash
lm csv ingest s3://acme-exports/billing/ --region eu-west-3 --scope billing \
    --access-key-secret ACME_S3_KEY --secret-key-secret ACME_S3_SECRET
```

For MinIO, Cloudflare R2 or GCS in S3 mode, add `--endpoint https://...`.

## From demo to production

### Customize the generated manifest

```bash
$EDITOR stripe.yaml         # set spec.schedule, promote columns, adjust pagination
lm validate -f stripe.yaml
lm apply -f stripe.yaml
```

- **Schedule:** `spec.schedule` (a cron) is the only key that schedules runs. A
  `default_schedule` inside the connector config schedules nothing.
- **Keep the file:** generate with `-o`. `lm connector get` prints the stored fields, not the manifest you applied.

### Promote the CSV to recurring ingestion

Write a `csv-file` manifest under the connector's registered name, `lm apply -f` it, then
`lm connector resume` it — exactly as in [Make it nightly](#make-it-nightly).

### Enter a secret without a shell trace

| Gesture | Use it for |
|---|---|
| `lm secret set NAME` | prompts; the value stays out of shell history and `ps` |
| `lm secret set NAME -` | a value that spans lines (a private SSH key): paste, then Ctrl+D (PowerShell: Ctrl+Z, Enter) |
| `lm secret set NAME VALUE` | works, and warns on stderr that the value is in your history |

### Production security

- **Master-key rotation:** not supported.
- **Audit:** every secret write, and every read from the database, emits a
  `platform.secret.{created,updated,deleted,read}` event.
- **Roles:** creating, updating and deleting secrets needs the realm role `lm_admin`.
- **No command returns a secret's value** — a lost value is set again.

## How to…

### Show the customer what the demo actually read

```bash
lm connector list
lm connector run <connector>
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

On every run `IN = OUT + SKIPPED`. `lm run get` names a failure, and points at
`lm dlq list --run <ID>` when rows were quarantined.

An empty `lm run list` means one of two things:

- **No run has happened.** Scheduled runs need `spec.schedule` *and* an enabled connector
  (`lm connector resume <name>` for one a one-shot registered). `lm connector run <name>`
  starts one by hand either way.
- **You took a one-shot path.** `lm csv ingest` records no run (Scenarios B, D and E).

### Take the demo back off the customer's machine

```bash
lm connector list               # the registered names
lm connector delete acme_billing
```

`lm` names what goes and asks you to type `delete`; any other answer deletes nothing.
`--dry-run` stops at the statement; a script passes `--force` (it ends the
[next section](#what-makes-a-scope-answerable)):

```
$ lm connector delete stock_count --dry-run
Would delete connector stock_count (csv-file) — 3 rows of ext.stock_count, its run history and RAG chunks (dry run, nothing deleted)
$ lm connector delete stock_count </dev/null
Type 'delete' to delete connector stock_count (csv-file) — 3 rows of ext.stock_count, its run history and RAG chunks: Error: aborted: no confirmation read (EOF) — pass --force in a script
```

The delete takes the connector, its endpoints, its table and its RAG chunks. A table another
connector also writes stays, with only the other connector's rows. The deleted tables leave
their scopes' [schema cards](#what-makes-a-scope-answerable).

A connector whose table feeds a [fused entity](../entities.md) is refused until the entity is gone:

```
$ lm connector delete cust-csv
Error: API error 409: table connector.t_cust_csv feeds entity customer — remove the entity first (lm entity delete customer)
$ lm entity delete customer
Type 'delete' to delete entity customer — its view, for every tenant: delete
deleted: customer
$ lm connector delete cust-csv
Type 'delete' to delete connector cust-csv (csv-file) — 9 rows of connector.t_cust_csv, its run history and RAG chunks: delete
deleted: cust-csv
```

## What makes a scope answerable

`lm ask` answers only over a scope that holds a **schema card** — the list of its tables and
columns. No card, no answer: the ask refuses at once.

A card is written, with nothing to configure, when:

- a **connector run completes** with a new or changed table;
- a **one-shot `lm csv ingest`** finishes;
- an [entity](../entities.md) is applied over the scope.

`lm source schema` shows what a scope's card holds. Changing a connector's scopes moves its
tables between cards; deleting the connector removes them, and a scope with no table left has no card:

```
$ lm source schema --scope retail
scope: retail

ext.stock_count
  external_id              TEXT
  sku                      TEXT
  label                    TEXT
  qty                      BIGINT
  warehouse                TEXT
  data                     JSONB
$ lm connector scope set stock_count stock
scopes: stock
$ lm source schema --scope retail
Error: API error 404: No schema card found for scope: retail
$ lm connector delete stock_count --force
deleted: stock_count
$ lm source schema --scope stock
Error: API error 404: No schema card found for scope: stock
```

An empty card prints the command that rebuilds it: `lm source rediscover --scope <tag>`.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Bearer token unavailable (env=STRIPE_API_KEY)` | the secret is set neither in the hub nor as an env var | `lm secret list`, then `lm secret set STRIPE_API_KEY` |
| `apply failed: …` (400) right after `from-openapi --apply` | the hub refused the manifest — a hand-edit broke it, or `metadata.tenant` does not match your own | generate with `-o`, then `lm validate -f` to see which field the hub names |
| a second connector appears after `lm apply -f` | `metadata.name` did not match the registered name, so apply created a new one | `lm connector list` for the real name, `lm connector delete` the duplicate, re-apply |
| `429 Too Many Requests` from the vendor | too many calls for the quota | edit `default_rate_limit: 50/s` in the YAML, then `lm apply -f` |
| `lumnik.secret.master-key is still the placeholder` at hub startup | `LUMNIK_SECRET_MASTER_KEY` is not set | `openssl rand -base64 32`, export it, restart the hub |
| `lumnik.secret.master-key is too short` at hub startup | `LUMNIK_SECRET_MASTER_KEY` is under 16 characters | the same |
| `secret with this name already exists` | `lm` updates an existing secret; a direct `POST` does not | through `lm`, nothing to do. To replace: `lm secret rm NAME --force`, then `set` |
| `403 {"error":"scope not granted"}` on `lm apply` | your user holds `scope:` roles and the manifest declares a scope you do not hold (or none) | declare only scopes you hold, or apply as a user with no `scope:` role |
| `WARN metadata.scopes — no scopes declared` on validate | the manifest names no scope, so its tables are not askable | add `scopes: [<scope>]` under `metadata` |

For connector details: [REST](../connectors/rest.md).
