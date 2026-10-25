# JDBC — Oracle

> **lumnik Pro.** The Oracle provider ships in lumnik Pro with its JDBC driver — you drop no jar. The open
> edition has no Oracle provider: a manifest declaring `dialect: oracle` there is refused with
> `JDBC dialect 'oracle' is not installed. Available: postgres`.

```yaml
spec:
  connection:
    url: jdbc:oracle:thin:@//erp-db.acme.io:1521/ERP   # by service name
    user: ro_user
    password_env: ACME_DB_PASSWORD
    dialect: oracle                                    # optional, inferred from the url
  defaults:
    schema: ERP                                        # the OWNER, exactly as Oracle stores it
  source:
    tables:
      - name: ORDERS                                   # exactly as `lm source jdbc discover` prints it
        mode: watermark
        watermark_column: UPDATED_AT
        pk_columns: [ID]
        rename:
          ID: order_id                                 # optional: land a column under another name
```

- **Versions.** Oracle 12c and later; tested on Oracle 23ai Free only. Oracle 11g is not supported.
- **Connect by service name**: `@//host:1521/SERVICE`, `@host:1521/SERVICE`, or a full
  `@(DESCRIPTION=…)` descriptor (quote it in YAML). The **SID** form (`@host:1521:SID`) works on a
  non-multitenant database only; on a multitenant one it fails with `ORA-01017`. TCPS and wallets are not tested.
- **`lm` needs `--schema`.** `lm source jdbc discover` and `add` default to `--schema public`; for
  Oracle pass the owner, e.g. `--schema ERP`, or the listing is empty.
- **Names are exact, never folded.** Write schema, table and column names exactly as
  `lm source jdbc discover` lists them (`ORDERS`, not `orders`).
- **The hub columns keep that spelling** — quoted upper-case (`"ID"`, `"UPDATED_AT"`), next to the
  hub's lower-case system columns. Quote them in a query, or `rename:` them.
- **Empty string is `NULL`.** Oracle stores `''` as `NULL`; the hub receives `NULL`.
- **Index `(watermark, key)` on every Oracle table you page.** Without it every page scans the whole
  table. A very large group of rows sharing one watermark value still pages more slowly.
- **Watermark ties and `NULL`s** behave as on PostgreSQL — see
  [A run reads what the table held when it started](../jdbc.md#resume-restart-reset).

| Oracle type | Lands as |
|---|---|
| `NUMBER`, `FLOAT`, `BINARY_FLOAT`, `BINARY_DOUBLE` | by the [Column types](../jdbc.md#column-types) table |
| `VARCHAR2`, `NVARCHAR2`, `CHAR`, `NCHAR`, `CLOB`, `NCLOB` | `TEXT` |
| `DATE`, `TIMESTAMP` (no time zone) | `TIMESTAMPTZ`, read in the connection's [`zone`](../jdbc.md#a-time-without-a-time-zone), UTC without one: `DATE '2026-01-02'` lands as `2026-01-02 00:00:00+00` |
| `TIMESTAMP WITH [LOCAL] TIME ZONE` | `TIMESTAMPTZ`, the same instant — usable as a watermark |
| `BLOB`, `RAW`, `LONG`, `LONG RAW`, `XMLTYPE`, `BFILE`, `ROWID`, object types | **refused**: `discover` lists the column under `UNSUPPORTED`, `add` refuses the table by name |
