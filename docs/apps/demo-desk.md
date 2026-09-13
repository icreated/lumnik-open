# The demo desk

> Three small exports of the same fictional company — an ERP, a CRM that contradicts it, and
> an order book with twenty years of keyboard habits in its status column — and one manifest
> per rung of the ladder over them. The source every screen of the
> [Entities](../entities.md), [Views](views.md), [Processes](processes.md) and
> [Workflows](workflows.md) pages reads.

It ships with the repo, under `docs/apps/demo/`. Nothing is generated: the three files are small
enough that every fused row, every disagreement and every undeclared status was **counted by
hand** before a single manifest was applied — which is the only way an expectation on this page
can be honest.

| File | Rows | What it is for |
|---|---|---|
| `data/acme-erp.csv` | 12 | the ERP's customers — the source of record. Two cells are empty on purpose: P-1005 has no city, P-1011 no segment |
| `data/acme-crm.csv` | 8 | the CRM's customers, under its own column names. Five overlap the ERP — three of them disagreeing with it — two are known to the CRM only, and one has no customer code at all |
| `data/acme-orders.csv` | 12 | the orders, with `status` spelled `En cours`, `encours`, `fini`, `TERMINE ` (trailing space), `BROUILLON`, `Terminé`, `EN LITIGE` and, once, nothing |
| `data/acme-orders-day2.csv` | 13 | the same export the next morning, with one new order in dispute — what makes a process fire |
| `data/acme-orders-day3.csv` | 14 | the third morning: one more, in dispute **and** above 1 000 € — what makes two processes fire on one row |

The customers are the same ACME partners the [REST](../connectors/rest.md) and
[Kafka](../connectors/kafka.md) demos serve; a partner has the same name and city on every page.

## Landing the sources

The manifests read the files from `/tmp/acme-desk/` **on the hub's filesystem**. The self-host
stack mounts nothing, so copy them into the container first — no restart needed:

```bash
docker exec lumnik-hub-1 mkdir -p /tmp/acme-desk
for f in docs/apps/demo/data/*.csv; do docker cp "$f" lumnik-hub-1:/tmp/acme-desk/; done
for n in 10-erp 20-crm 30-orders; do lm apply -f docs/apps/demo/sources/$n.yaml; done
for n in erp crm orders; do lm connector run acme-desk-$n; done
```

```
$ lm run list --connector acme-desk-erp
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
89f873b3  acme-desk-erp  default   Completed  12  12   0        0             2026-09-13 02:38  2026-09-13 02:38
```

Three tables in the `connector` schema — `t_acme_desk_erp`, `t_acme_desk_crm`,
`t_acme_desk_orders` — every column `text`, an empty CSV cell landed as SQL `NULL`, and one scope
card, `desk`, that lists all three:

```
$ lm source schema --scope desk
scope: desk

connector.t_acme_desk_erp
  city                     TEXT
  code                     TEXT
  name                     TEXT
  segment                  TEXT
  credit_limit             TEXT

connector.t_acme_desk_crm
  town                     TEXT
  email                    TEXT
  company                  TEXT
  customer                 TEXT

connector.t_acme_desk_orders
  code                     TEXT
  amount                   TEXT
  status                   TEXT
  order_no                 TEXT
  ordered_on               TEXT
```

Everything on the four pages is applied on top of this: one scope, three raw tables.

## The manifests

| Folder | Files | The page |
|---|---|---|
| `entities/` | `10-customer` fuses the ERP and the CRM; `20-crm-first` is the same fusion with the priority flipped; `60`–`65` must each be refused | [Entities](../entities.md) |
| `views/` | `10-customers` over the fused entity, `20-orders` straight over the raw order book; `60`–`62` must be refused | [Views](views.md) |
| `processes/` | `10-litige` emits a decision when an order lands in dispute, `20-grosse-commande` when it is above 1 000 €; `60`–`63` must be refused — and one of them is not, see the page | [Processes](processes.md) |
| `workflows/` | `10-order-lifecycle` declares what the status column means, with a dormancy threshold; `60`–`67` must be refused | [Workflows](workflows.md) |

The `6x` files are **not** broken files to fix. Each pins one refusal the hub produces, and a
clean apply on any of them is the defect.

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
lm entity delete AcmeCustomer; lm entity delete AcmeCustomerCrmFirst
for n in erp crm orders; do lm connector delete acme-desk-$n; done   # refused while an entity still fuses the table
```

Entities first: a connector whose table feeds an entity is refused with a `409` naming the entity.
Saved views, processes and workflows stay where they are — a process cannot be deleted from `lm`
today, and a workflow is updated by re-applying under its name.

!!! note "One scope for the whole desk"
    Every source connector carries `scopes: [desk]`, and so does the entity. That is what lets a
    view over the raw order book and a process on it share a card with the fused customers. A
    scope-bound user needs the `scope:desk` realm role to see any of it — the same boundary as
    chat, see [Scopes](../rag/scopes.md).
