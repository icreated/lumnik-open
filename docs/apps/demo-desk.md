# The demo desk

> Three small exports of the same fictional company, and the manifests over them:
>
> - an ERP;
> - a CRM that contradicts it;
> - an order book with twenty years of keyboard habits in its status column.
>
> Every screen of the [Entities](../entities.md), [Views](views.md), [Processes](processes.md)
> and [Workflows](workflows.md) pages reads them.

It ships with the repo, under `docs/apps/demo/`. Nothing is generated: every fused row, every
disagreement and every undeclared status below was **counted by hand**.

| File | Rows | What it is for |
|---|---|---|
| `data/acme-erp.csv` | 12 | the ERP's customers — the source of record. Two cells are empty on purpose: P-1005 has no city, P-1011 no segment |
| `data/acme-crm.csv` | 8 | the CRM's customers, under its own column names. Five overlap the ERP — three of them disagreeing with it — two are known to the CRM only, and one has no customer code at all |
| `data/acme-orders.csv` | 12 | the orders, with `status` spelled `En cours`, `encours`, `fini`, `TERMINE ` (trailing space), `BROUILLON`, `Terminé`, `EN LITIGE` and, once, nothing |
| `data/acme-orders-day2.csv` | 13 | the same export the next morning, with one new order in dispute — what makes a process fire |
| `data/acme-orders-day3.csv` | 14 | the third morning: one more, in dispute **and** above 1 000 € — what makes two processes fire on one row |
| `data/acme-deliveries.csv` | 4 | the carrier's deliveries, each with the date it was promised for — what a due date watches |

The customers are the same ACME partners the [REST](../connectors/rest.md) and
[Kafka](../connectors/kafka.md) demos serve; a partner has the same name and city on every page.

## Landing the sources

The manifests read `acme-desk/` in your tenant's folder of the hub's local root, **on the hub's
filesystem**. The self-host stack mounts nothing, so copy the files into the container first. With
`LUMNIK_CSV_LOCAL_ROOT=/tmp/drop` in `.env` ([CSV & files](../connectors/csv.md#quickstart-one-local-file-three-commands-and-a-look-at-the-ledger)),
tenant `1` reads `/tmp/drop/1/`:

```bash
docker compose -f docker-compose.selfhost.yml exec hub mkdir -p /tmp/drop/1/acme-desk
docker compose -f docker-compose.selfhost.yml cp docs/apps/demo/data/. hub:/tmp/drop/1/acme-desk
for n in 10-erp 20-crm 30-orders; do lm apply -f docs/apps/demo/sources/$n.yaml; done
for n in erp crm orders; do lm connector run acme-desk-$n; done
```

```
$ lm run list
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
7b972d2b  acme-desk-orders  default   Completed  12  12   0        0             2026-10-19 01:38  2026-10-19 01:38
8869459c  acme-desk-crm     default   Completed  8   8    0        0             2026-10-19 01:38  2026-10-19 01:38
bfb8a55e  acme-desk-erp     default   Completed  12  12   0        0             2026-10-19 01:38  2026-10-19 01:38
```

Three tables in the `connector` schema, every column `text` (an empty CSV cell lands as SQL
`NULL`), and one scope card, `desk`, that lists all three:

```
$ lm source schema --scope desk
scope: desk

connector.t_acme_desk_crm
  customer                 TEXT
  company                  TEXT
  town                     TEXT
  email                    TEXT

connector.t_acme_desk_erp
  code                     TEXT
  name                     TEXT
  city                     TEXT
  segment                  TEXT
  credit_limit             TEXT

connector.t_acme_desk_orders
  order_no                 TEXT
  code                     TEXT
  status                   TEXT
  amount                   TEXT
  ordered_on               TEXT
```

The fused customer comes next — `lm entity apply -f docs/apps/demo/entities/10-customer.yaml`,
see [Entities](../entities.md). Everything on the four pages is applied on top of this.

### Day 2 and day 3

Two pages need the next mornings' exports. Load one over the day-1 file, then re-run the source:

```bash
docker compose -f docker-compose.selfhost.yml cp docs/apps/demo/data/acme-orders-day2.csv hub:/tmp/drop/1/acme-desk/acme-orders.csv   # or -day3
lm connector run acme-desk-orders
```

Each page says which day a capture needs. Copying `acme-orders.csv` back returns to day 1: the
next run removes the newer orders, and `lm run get <id>` says so (from day 3:
`• 2 row(s) removed — the source no longer holds them`).

## The manifests

| Folder | Files | The page |
|---|---|---|
| `entities/` | `10-customer` fuses the ERP and the CRM; `20-crm-first` flips the priority; `30-product-with-category` joins a lookup table; `40-customer-crm-city` prefers the CRM for the city; `50-order-stage` and `55-order-due` read the extra sources below; `60`–`65` must each be refused; `66-partner-by-amount` applies, and is what `processes/63` is refused on | [Entities](../entities.md), [Joins](../entities-joins.md) |
| `sources/` | `10-erp`, `20-crm`, `30-orders` land the desk, `80-deliveries` its deliveries; `40-products` and `50-product-categories` land the join example; `55`–`56-stage-*` (`conflicts: false`) and `70`–`72-due-*` (dates and numbers) land the extra examples, same `docker cp` | [Entities](../entities.md), [Joins](../entities-joins.md), [Dates and numbers](../entities-types.md), [Workflows](workflows.md) |
| `views/` | `10-customers` over the fused entity, `20-orders` straight over the raw order book; `40-customers-links` adds cells that open the orders view or the ERP; `60`–`62` must be refused | [Views](views.md) |
| `processes/` | `10-litige` emits a decision when an order lands in dispute, `20-grosse-commande` when it is above 1 000 €; `30-lyon-customer` listens on the fused entity; `40-rest-over-limit` on a REST connector's table; `60`–`64` must each be refused | [Processes](processes.md) |
| `workflows/` | `10-order-lifecycle` declares what the status column means, with a dormancy threshold; `20-delivery-lifecycle` watches a due date; `40-rest-review` watches a REST connector's table; `60`–`68` must be refused | [Workflows](workflows.md) |
| `apps/` | `20-desk` makes the desk a business app, the default one | [The PWA](pwa.md) |

The `6x` files are **not** broken files to fix: each one is meant to be refused, with the
message its page shows.

Two files belong to no page. `views/30-categories-links` reads a catalogue the desk does not
have. `apps/10-lumilait` is the template `lm ui` offers for a new app.

## What the fusion must give

The hand count for `entities/10-customer.yaml`, ERP first:

| Code | name | city | segment | email | `_conflicts` | Why |
|---|---|---|---|---|---|---|
| P-1000 | Hydraulique Lyon 1000 | Lyon | GOLD | contact@… | **name** | the CRM says `HYDRAULIQUE LYON` — the ERP wins, the CRM is kept |
| P-1002 | Fixations Milano 1002 | Milano | BRONZE | ventes@… | **city** | the CRM says `Milan` |
| P-1005 | Transmission Aarhus 1005 | **Aarhus** | GOLD | salg@… | `{}` | the ERP had no city; the CRM filled the gap — a gap is not a disagreement |
| P-1008 | Filtration Lyon 1008 | Lyon | GOLD | contact@… | **city** | the CRM says `Villeurbanne` |
| P-1003 | — | — | — | geral@… | `{}` | both sources agree verbatim |
| P-1001, P-1004, P-1006, P-1007, P-1009, P-1010, P-1011 | — | — | — | `NULL` | `{}` | ERP only |
| P-1012, P-1013 | — | — | `NULL` | — | `{}` | CRM only |
| *(blank code)* | — | — | — | — | — | **absent**: a NULL match key can be matched to nothing |

**14 rows, three in dispute.** Flip the source order (`20-crm-first.yaml`) and the count and the
disputed set stay identical; only the representatives change.

## What the order book must report

For `workflows/10-order-lifecycle.yaml`, which declares `BROUILLON`, `EN_COURS` (aliases
`en cours`, `encours`), `TERMINE` (alias `fini`) and `ANNULE`:

| Status as written | Rows | Resolves to |
|---|---|---|
| `En cours`, `encours` | 4 | `EN_COURS` — trim and case-insensitive alias matching |
| `fini`, `TERMINE ` | 2 | `TERMINE` |
| `BROUILLON` | 2 | `BROUILLON` |
| `EN LITIGE` | 2 (3 after day 2, 4 after day 3) | **undeclared** — real, and off the declared map |
| `Terminé` | 1 | **undeclared** — the accent is not an alias anyone declared |
| *(empty)* | 1 | `nullOrBlank` |
| `ANNULE` | 0 | declared, **unobserved** |

## Resetting

The desk is small enough to rebuild in a minute:

```bash
for n in acme-customers acme-orders acme-customers-linked; do lm view rm $n; done
for n in litige-ouvert grosse-commande lyon-customer; do lm process rm $n; done
for n in acme-order-lifecycle acme-delivery-lifecycle; do lm workflow rm $n; done
for n in AcmeCustomer AcmeCustomerCrmFirst AcmeCustomerCrmCity; do lm entity delete $n --force; done
for n in erp crm orders deliveries; do lm connector delete acme-desk-$n --force; done
```

The extra examples, if you applied them:

```bash
lm process rm lab-over-limit-c; lm workflow rm lab-result-review
for n in ProductWithCategory OrderStage OrderDue PartnerByAmount; do lm entity delete $n --force; done
for n in products product-categories stage-orders stage-deliveries due-erp due-partner; do
  lm connector delete $n --force
done
```

- `--force` skips the `delete` confirmation both commands ask for.
- An entity delete drops its view **for every tenant**, and every search index over it.
- A connector delete drops its table, its run history and its RAG chunks. Its stored credential
  goes too when no other connector uses it.
- Entities go before connectors: a connector whose table feeds an entity is refused with a `409`
  naming the entity.
- An `rm` of something you never applied answers `404`; the next command still runs.
- The `desk` app stays: the default app is not removed
  ([The PWA](pwa.md#declare-a-business-app)).

!!! note "One scope for the whole desk"
    Every source connector carries `scopes: [desk]`, and so does the entity. That is what lets a
    view over the raw order book and a process on it share a card with the fused customers. A
    scope-bound user needs the `scope:desk` realm role to see any of it — the same boundary as
    chat, see [Scopes](../rag/scopes.md).
