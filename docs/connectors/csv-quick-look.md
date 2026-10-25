# One file, one command — `lm csv ingest`

> Load one export in one command, to look at it before writing a connector. Nothing is
> scheduled, nothing is transformed.

## Load it

```
$ lm csv ingest ./clients.csv --name quick-look --scope demo
Ingested ./clients.csv into ext.quick_look
  • rows written:    2
  • promoted cols:   3
      - row_id (bigint)
      - email (text)
      - signup_date (text)

  • askable:         scope 'demo' — try: lm ask --scope demo "how many rows are in ext.quick_look?"
A dormant connector is registered — see `lm connector list`.
```

- The rows land in `ext.<name>`. `--name quick-look` gives `ext.quick_look`: every `-` becomes `_`.
- Numbers and true/false become typed columns. Dates stay text, like `signup_date` above.
- The file is askable at once in the scope you pass (Pro, AI on). Without `--scope`, it goes to
  `system`.
- On lumnik Open, or with the AI off, the last lines read `A one-shot: no run recorded, nothing
  indexed for chat.` and `The rows are in ext.quick_look, and a dormant connector is registered`.

A **dormant** connector is registered under the same name. It never runs on its own. Inspect it,
turn it into a manifest, or remove it with `lm connector delete quick_look`.

## Where the file can be

```bash
lm csv ingest ./clients.csv                                       # a file on your machine, uploaded
lm csv ingest sftp://user@host/export/clients.csv --key-secret ACME_SFTP_KEY \
    --host-key-fingerprint "SHA256:…"                             # or --password-secret NAME
lm csv ingest s3://acme-exports/billing/ --region eu-west-3 --suffix .csv
```

- An uploaded file is limited to 10 MB. A larger one: put it on SFTP or S3.
- A remote file is limited to 500 MB, and stays where it is.
- A folder holds several files: each lands in its own `ext.<filename>`. `--name` takes one file
  only, and is refused on a folder.

## Load it again

The same name refreshes the table. Rows are deduplicated, new columns added:

```
$ lm csv ingest ./clients.csv --name quick-look --scope demo
Re-ingested ./clients.csv into ext.quick_look — refreshed, rows deduplicated by key
  • rows written:    3
  • promoted cols:   3
      - row_id (bigint)
      - email (text)
      - signup_date (text)
```

A different `--scope` moves the table to that scope.

## What it does not do

| | A `csv-file` connector | `lm csv ingest` |
|---|---|---|
| Runs on a schedule | yes | no |
| Repairs the data ([transformers](transformers.md)) | yes | no |
| A malformed row | set aside, the run continues | **the whole load is refused**, nothing written |
| Moves or deletes the source file | sftp/s3: yes | no |
| Lands in | `connector.t_<name>` | `ext.<name>` |

To make it recurring, write a `csv-file` connector under **the registered name**, then wake it:
[Make it nightly](../quickstart/other-sources.md#make-it-nightly).

## Troubleshooting

| You see | Do this |
|---|---|
| `Error: API error 400: malformed CSV row at line 3 — Record has 3 values but only 2 columns are declared` | Fix line 3 of the file, load it again. |
| `Error: API error 413 (request too large): the hub accepts a request body up to 10 MB …` | Put the file on SFTP or S3, and load it from there. |
| `Error: API error 409: the name 'articles' belongs to a configured connector …` | Pass another `--name`. |
| `name 'clients' would ingest 3 files into one dataset …` | Drop `--name` (one table per file), point at one file, or write a connector with a glob. |
| A second connector appears after `lm apply -f` | The manifest's `metadata.name` differs from the registered name. Delete the duplicate. |
