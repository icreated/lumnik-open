# Other first sources — an API, SFTP, S3

> The [Quickstart](installer.md) loads one CSV. Here, three other first sources. Each one ends
> where the Quickstart does: rows you can show, then a question.

## Store the credential first

A password or a key never goes on the command line or in a manifest. Store it in the hub:

| Gesture | Use it for |
|---|---|
| `lm secret set NAME` | a password or a token. It prompts; nothing shows on screen or in your shell history. |
| `lm secret set NAME -` | a value on several lines, such as a private SSH key. Paste it, then Ctrl+D (PowerShell: Ctrl+Z, Enter). |

```
$ lm secret set SFTP_EXPORT_PASSWORD --description "nightly export server"
Value for SFTP_EXPORT_PASSWORD:
Created secret "SFTP_EXPORT_PASSWORD"
$ lm secret list
NAME                           ROTATED_AT                     DESCRIPTION
SFTP_EXPORT_PASSWORD           -                              nightly export server
```

- No command prints a secret back. Lost it? Set it again.
- Writing a secret needs the `lm_admin` role ([roles](../security/role-management.md#which-role-for-which-gesture)).

## An API with an OpenAPI description

`lm` writes the connector manifest from the API's description:

```bash
lm secret set STRIPE_API_KEY
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing -o stripe.yaml
```

Open `stripe.yaml` before you apply it:

- A big API gives a long manifest: one endpoint per `GET` path. Delete the ones the customer does
  not need. A path the draft cannot page correctly stays commented out, under a line that says
  what to declare.
- Add `spec.schedule` (a cron) to run it every night. Nothing else schedules it.

```bash
lm validate -f stripe.yaml
lm apply -f stripe.yaml
lm connector run stripe          # apply declares it; this starts the first run
```

- `lm validate` stops at the first envelope error. Fix it, run it again.
- An endpoint's id comes from its whole path: `/v1/charges` is `stripe/v1-charges`.
  Silence one with `lm endpoint disable stripe/v1-charges`.

Then ask in the scope you declared (Pro), once the run is `Completed`:

```bash
lm ask --scope billing "how many charges above 1000?"
```

All the REST options: [REST](../connectors/rest.md).

## A nightly SFTP export

The most common case: the ERP drops a file on an SFTP server every night.

```bash
ssh-keyscan sftp.acme.io | ssh-keygen -lf -    # read the server's SHA256:… fingerprint
lm secret set ACME_SFTP_KEY -                  # paste the private key, then Ctrl+D
lm csv ingest sftp://lumnik@sftp.acme.io/export/billing-latest.csv \
    --key-secret ACME_SFTP_KEY --host-key-fingerprint "SHA256:…" \
    --scope billing --name acme-billing
```

- Without `--host-key-fingerprint`, the hub refuses to connect.
- A server reached by password takes `--password-secret NAME` instead of `--key-secret`.

### Make it nightly

The one-shot leaves no manifest. Write a `csv-file` manifest under **the name the one-shot
registered**. `lm` turns `acme-billing` into `acme_billing`: read it in `lm connector list`.
Another name creates a second connector.

```yaml
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata:
  name: acme_billing                  # the name lm connector list shows
  connector_type: csv-file
  scopes: [billing]
spec:
  schedule: "0 6 * * *"
  transport:
    kind: sftp
    host: sftp.acme.io
    user: lumnik
    path: /export/*.csv
    key_env: ACME_SFTP_KEY
    host_key_fingerprint: "SHA256:…"
  after_process: move
  archive_path: /archive/
```

```bash
lm validate -f acme-nightly.yaml
lm apply -f acme-nightly.yaml
lm connector resume acme_billing       # the one-shot left it dormant; apply does not wake it
```

All the file options: [CSV & files](../connectors/csv.md).

## An S3 bucket

```bash
lm secret set AWS_ACCESS_KEY_ID
lm secret set AWS_SECRET_ACCESS_KEY
lm csv ingest s3://acme-exports/billing/ \
    --region eu-west-3 --suffix .csv --scope billing
```

- The S3 source reads these two secret names by default.
- Other names? Pass `--access-key-secret NAME --secret-key-secret NAME`.
- MinIO, Cloudflare R2, GCS in S3 mode: add `--endpoint https://…`.

## Troubleshooting

| You see | Do this |
|---|---|
| `Bearer token unavailable (env=STRIPE_API_KEY)` | The secret is missing. `lm secret set STRIPE_API_KEY`. |
| `apply failed: …` (400) | The hub refused the manifest. `lm validate -f` names the field. |
| `429 Too Many Requests` from the API | Add `default_rate_limit: 50/s` under `spec:`, then `lm apply -f` again. |
| `WARN metadata.scopes — no scopes declared` on validate | Add `scopes: [<scope>]` under `metadata`. Without it, Ask cannot see the tables. |
| An empty `lm run list` after a one-shot | Expected: a one-shot records no run. A connector run does. |
