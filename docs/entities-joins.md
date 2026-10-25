# Entities — joins

> A product references its category. A join brings the category's fields onto each product,
> without merging products and without adding a row.

## Fuse or join

| | **Fuse** (`match` + `sources[]`) | **Join** (`joins[]`) |
|---|---|---|
| The question | several systems describe the *same* object | one object *references* another |
| Example | the ERP's customer and the CRM's customer | a product and its category |
| Rows | merged into one per `match` key | never merged, never multiplied |
| Disagreement | kept in `_conflicts` | none: a lookup is not an opinion |

`match: [category_id]` is **not** a join: it would merge every product of a category into one row.

## The case

Four products, and a category table with a gap and a duplicate:

```csv
--8<-- "docs/apps/demo/data/m_product.csv"
```

```csv
--8<-- "docs/apps/demo/data/m_product_category.csv"
```

The entity declares the category table as a lookup:

```yaml
--8<-- "docs/apps/demo/entities/30-product-with-category.yaml"
```

Land and apply (the manifests read `/tmp/acme-catalogue/` on the hub's disk):

```bash
docker compose -f docker-compose.selfhost.yml cp docs/apps/demo/data/. hub:/tmp/acme-catalogue
lm apply -f docs/apps/demo/sources/40-products.yaml
lm apply -f docs/apps/demo/sources/50-product-categories.yaml
lm connector run products && lm connector run product-categories
lm entity apply -f docs/apps/demo/entities/30-product-with-category.yaml
```

Wait for both runs to read `Completed` in `lm run list` before the entity apply.

## The result

```
$ lm entity get ProductWithCategory
PRODUCT_ID  CATEGORY_ID  PRODUCT_NAME  CATEGORY_NAME  CATEGORY_DESCRIPTION        _CONFLICTS
P1          C1           Pump 50       Pumps          Centrifugal and gear pumps  {}
P2          C1           Pump 80       Pumps          Centrifugal and gear pumps  {}
P3          C9           Spare gasket                                             {}
P4          C2           Valve DN15                                               {"category_name": [{"src": "t_product_categories", "value": "Valves"}, {"src": "t_product_categories", "value": "Valves (old)"}], "category_description": [{"src": "t_product_categories", "value": "Ball valves"}, {"src": "t_product_categories", "value": "Superseded listing"}]}
```

| The lookup has, for the key | The joined columns | `_conflicts` |
|---|---|---|
| no row (P3, category `C9`) | empty; the product is **kept** | nothing: an absence is not an opinion |
| exactly one row (P1, P2) | that row's values | nothing |
| **several rows** (P4, category `C2` listed twice) | empty, even when the rows are identical | every row, per column: the ambiguity is shown, never guessed |

`lm view` shows a lookup ambiguity under *Ambiguïtés et divergences*, and `lm conflicts list`
lists it with the source conflicts.

## The manifest's `joins[]`

| Field | Meaning |
|---|---|
| `table` | the lookup's table in the `connector` schema; not also a source |
| `from` | a column of the fused entity: the value looked up |
| `to` | a column of the lookup table: the key it is matched on |
| `map` | `lookup column: new column`; a new name must not collide with another column |

- Joined columns are readable, filterable, askable and exportable like any other.
- A join reads only the fused entity's columns: joins cannot chain.
- On a [`resolution:`](entities-resolution.md) entity, joins read the resolved rows.

| `lm entity apply` refuses | When |
|---|---|
| `400` | `from` is not a column of the entity, a joined name collides, or the lookup is also a source |
| `400` naming the table and column | `to` or a `map` key is not in the lookup table |
| `409` *run the source connectors first* | the lookup table does not exist yet |

??? note "How keys are compared"
    Keys match as written. A plain number matches by value: `1000021` = `1000021.0`, `1.5` = `1.50`.
    Nothing else is folded: `01` ≠ `1`, `C1` ≠ `c1`. When both sides are dates or times, a date
    matches every time of its day.

## Deliver it

A joined entity exports like any other: [Exports](apps/exports.md). The demo ships one,
`docs/apps/exports/30-product-catalogue-s3.yaml`.
