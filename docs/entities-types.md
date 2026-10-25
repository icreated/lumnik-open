# Entities — dates and numbers across sources

> Two systems write the same date as `2026-07-01 14:30` and `01/07/2026`, the same amount as
> `1500` and `1500.00`. Fused as written, they disagree on every row. Type them at the door, and
> only the real disagreements stay.

## The case

The ERP and a partner each send their copy of the same orders:

```csv
--8<-- "docs/apps/demo/data/due-erp.csv"
```

```csv
--8<-- "docs/apps/demo/data/due-partner.csv"
```

The ERP's connector types its date and amount ([`70-due-erp.yaml`](#the-files)). The partner's
connector reads its file as written. The entity fuses both on the order number:

```yaml
--8<-- "docs/apps/demo/entities/55-order-due.yaml"
```

## Left as written: every row disagrees

```
$ lm entity get orderdue -o json
[
  {
    "order_no": "SO-1",
    "due": "2026-07-01 14:30:00+00",
    "amount": "1500.0000",
    "_conflicts": "{\"due\": [{\"src\": \"t_due_erp\", \"value\": \"2026-07-01 14:30:00+00\"}, {\"src\": \"t_due_partner\", \"value\": \"01/07/2026\"}], \"amount\": [{\"src\": \"t_due_erp\", \"value\": \"1500.0000\"}, {\"src\": \"t_due_partner\", \"value\": \"1500.00\"}]}"
  },
  …
]
```

`01/07/2026` is text. The hub does not guess whether it is the first of July or the seventh of
January, so it compares it as written: a conflict on every row.

## Type it at the door: four steps

A table keeps the column types it was created with. Adding `parse-date` to a connector that
already ran changes nothing: create its table again.

```bash
lm entity delete orderdue --force                                     # 1. the entity holds the table
lm connector delete due-partner --force                               # 2. drops its table and rows
lm apply -f docs/apps/demo/sources/72-due-partner-typed.yaml          # 3. the same connector, typed…
lm connector run due-partner                                          #    …and its table created again
lm entity apply -f docs/apps/demo/entities/55-order-due.yaml          # 4. the entity, again
```

Step 3 adds two hooks to the partner's connector:

```yaml
  mapping:
    cell:
      - parse-date: { column: due, formats: ["dd/MM/yyyy"] }
      - parse-number: { column: amount, decimal-separator: "." }
```

The entity's columns now have types:

```
$ lm source schema --scope desk
…
connector.v_orderdue
  order_no                 text
  due                      timestamp with time zone
  amount                   numeric
```

## Typed: only the real disagreement stays

```
$ lm entity get orderdue -o json
[
  {
    "order_no": "SO-1",
    "due": "2026-07-01T14:30:00Z",
    "amount": "1500",
    "_conflicts": "{}"
  },
  {
    "order_no": "SO-2",
    "due": "2026-07-03T09:00:00Z",
    "amount": "980.5",
    "_conflicts": "{\"due\": [{\"src\": \"t_due_erp\", \"value\": \"2026-07-03 09:00:00+00\"}, {\"src\": \"t_due_partner\", \"value\": \"2026-07-04 00:00:00+00\"}]}"
  }
]
$ lm conflicts list
ENTITY    KEY   COLUMNS  SOURCES
OrderDue  SO-2  due      t_due_erp,t_due_partner
```

- **SO-1 agrees.** The partner knows only the day; the ERP's 14:30 is on that day. `1500.00` and
  `1500` are the same number.
- **SO-2 disagrees for real:** the ERP says the 3rd, the partner the 4th. That is the one row to
  look at.
- `980.50` and `980.5` agree. A number is written without its trailing zeros.

## The rules

**Dates** — the fused column follows what its sources hold:

| The sources hold | The entity's column | Compared |
|---|---|---|
| dates only | a date | as dates |
| dates and times | a time; a date is its midnight UTC | **at the day**: a time agrees with its day |
| times only | a time | to the instant |
| one source as text | text | as written |

**Numbers** — the same:

| The sources hold | The entity's column | Compared |
|---|---|---|
| whole numbers only | a whole number | by value |
| one decimal among them | a decimal, without trailing zeros | by value: `7.0000` = `7` |
| one source as text | text | as written: `"10.0"` ≠ `10` |

A typed column orders and filters as its type everywhere: `lm entity get --filter amount=1500.00`
finds `1500`, a view sorts `7`, `12`, `100`. Each value still reads as a string in JSON.

## A time of day and its zone

An ERP writes `2026-08-02 23:30` with no zone. Read as UTC, it is two hours off a Paris CSV parsed
with `zone: Europe/Paris`: a conflict on every row. Declare where each source writes:

- the ERP's JDBC connection: `zone: Europe/Paris` ([A time without a time zone](connectors/jdbc.md#a-time-without-a-time-zone));
- the CSV's `parse-date`: the same `zone` ([parse-date](connectors/transformers.md#which-time-zone-a-time-is-in)).

A date alone has no such trap: both sides are its midnight UTC.

## The files

All under `docs/apps/demo/`, landed like the [demo desk](apps/demo-desk.md#landing-the-sources)
(`docker cp` the two CSVs into `/tmp/acme-desk/`):

| File | Role |
|---|---|
| `data/due-erp.csv`, `data/due-partner.csv` | the two copies of the orders |
| `sources/70-due-erp.yaml` | the ERP's connector, date and amount typed |
| `sources/71-due-partner.yaml` | the partner's connector, as written |
| `sources/72-due-partner-typed.yaml` | the partner's connector, typed |
| `entities/55-order-due.yaml` | the fusion |
