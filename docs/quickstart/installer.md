# Quickstart — the customer's data in minutes

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Quickstart</p>
<p class="lm-lead" markdown="span">Install `lm`, log in, load one export, show the rows, ask a question.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Before this page</strong>

The hub [runs](../deploy.md).
</div>
<div class="lm-meta-item" markdown>
<strong>You need</strong>

One CSV export from the customer.
</div>
<div class="lm-meta-item" markdown>
<strong>You get</strong>

Rows you can show, and a first answer in French or English (Pro).
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Other sources</strong>

An API, an SFTP server, an S3 bucket: [Other first sources](other-sources.md).
</div>
</div>
</section>

## 1. Install `lm`

Take the archive for your platform from the
[Releases page](https://github.com/icreated/lumnik-open/releases/latest). There are five:
macOS (Intel, Apple Silicon), Linux (x86-64, arm64), Windows x86-64.

```bash
shasum -a 256 -c lm_<version>_checksums.txt --ignore-missing   # check the download first
tar -xzf lm_<version>_darwin_arm64.tar.gz                      # your platform's archive
install -d ~/.local/bin && install -m 0755 lm ~/.local/bin/lm
```

- **Windows:** the archive is a `.zip` with `lm.exe`. Unpack it into a folder on your `PATH`.
- **From source** (Go 1.26+): `cd lm && make install`.
- The shell cannot find `lm`? Add `~/.local/bin` to your `PATH`.

Check it:

```
$ lm version
lm version dev (commit 94ae18cc9667, built from 2026-10-19T05:34:15Z)
```

A downloaded binary prints its release number instead of `dev`. Put this line in a bug report.

## 2. Log in

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login
```

`lm login` prints a link and a code, then waits. Open the link in a browser. Sign in as
`integrator`: the password is `INTEGRATOR_PASSWORD` in `.env`. The terminal then finishes:

```
$ lm login
To sign in, open:
  http://localhost:8180/realms/lumnik/device?user_code=EYQP-LLJN
and enter code: EYQP-LLJN

Waiting...
Logged in to lumnik Open Edition.
```

A Pro hub prints `Logged in.` Check the round trip. On a fresh hub, the table is empty:

```
$ lm connector list
NAME  TYPE  TENANT  SCHED  WINDOW  STATUS  LAST RUN
```

## 3. Load the export

The customer hands you `stock.csv`:

```
sku,label,qty,warehouse
PMP-100,Pompe doseuse,12,Lyon
VAN-200,Vanne papillon,0,Lyon
FLT-300,Filtre a cartouche,40,Grenoble
```

One command loads it, in a scope you name:

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

- `qty` holds numbers only, so it lands as a number. The other columns land as text.
- On lumnik Open, the last two lines read:

    ```
    A one-shot: no run recorded, nothing indexed for chat.
    The rows are in ext.stock_count, and a dormant connector is registered — see `lm connector list`.
    ```

- The load runs once. Nothing runs again on its own.

## 4. Ask a question (Pro)

Ask in the scope you named:

```
$ lm ask --scope retail "quels articles ont une quantité à zéro ?"
Les articles ayant une quantité à zéro sont :

- SKU : VAN-200
  Libellé : Vanne papillon

  SKU      LABEL
  VAN-200  Vanne papillon
  1 row returned

  SQL: SELECT "sku", "label"
FROM "ext"."stock_count"
WHERE "qty" = 0
LIMIT 500
```

- The rows and the SQL always come with the answer. Show them to the customer.
- **Use the words of the data.** *« Quels articles sont en rupture de stock ? »* is declined:
  no column says "rupture". *« Quantité à zéro »* names the column, and works.
- Without `--scope`, the question goes to your default scope, where this file is not.
- `lm ask` answers `404` on lumnik Open, and on Pro with the AI switched off.

Why Ask declines, and what to do: [When Ask refuses](../transparency/ask-honesty.md).

## 5. Take it back off

```
$ lm connector delete stock_count --dry-run
Would delete connector stock_count (csv-file) — 3 rows of ext.stock_count, its run history and RAG chunks (dry run, nothing deleted)
```

Without `--dry-run`, `lm` asks you to type `delete`. Any other answer deletes nothing. A script
passes `--force`.

## Next

- **Make it recurring:** a `csv-file` connector reads the export on a schedule:
  [CSV & files](../connectors/csv.md). Give it the registered name, here `stock_count`: `lm` turns
  every `-` of `--name` into `_`. Then `lm connector resume stock_count`.
- **Another source:** [Other first sources](other-sources.md) — an API, SFTP, S3.
- **The full demo**, with two sources fused into one customer: [The WOW](../wow.md).

## What makes a scope answerable

Ask needs to know a scope's tables. A connector run, a one-shot load or an entity apply tells it,
with nothing to configure. See what Ask knows of a scope:

```bash
lm source schema --scope retail
```

Empty, or not found? Rebuild it with `lm source rediscover --scope retail`, then ask again.

## Troubleshooting

| You see | Do this |
|---|---|
| `401` on every command | Your session ended. Run `lm login` again. |
| `Error: access refused (403): scope not granted` | Your account holds `scope:` roles, and not this one. Use a scope you hold. |
| `Error: API error 404 (no details from the server)` on `lm ask` | lumnik Open, or the AI is off. See [The two editions](../editions.md#which-edition-am-i-running). |
| Ask declines a question | Rephrase it with the words of your columns. Check `lm source schema --scope <tag>`. |
| A second connector appears after `lm apply -f` | `metadata.name` differs from the registered name. Delete the duplicate, re-apply under the name `lm connector list` shows. |
