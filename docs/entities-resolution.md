# kind: Entity — resolve

> When two systems share no key, `resolution:` gives each real customer one identity. It matches
> rows rule by rule, keeps what it cannot decide for you to review, and takes your correction
> over any rule.

| | |
|---|---|
| **Kind** | `Entity`, with a `resolution:` block instead of `match:` |
| **Apply** | `lm entity apply` — `lm_admin` |
| **Resolve, correct** | `lm entity resolve`, `lm entity assert-same`, `lm entity assert-distinct` — `lm_admin` |
| **Review** | `lm entity ambiguous`, `lm entity history` — `lm_user`, scope-bound |
| **Edition** | Open |

Use [`match:`](entities.md) when every system carries the same customer code. Use `resolution:`
when they don't: the ERP knows `C-003`, the CRM knows contact `9003`, and only the email
address ties them together.

## Quickstart — land, apply, resolve

Two small exports. The ERP has four customers. Two of them share one mailbox
(`compta@groupe-rhone.example`). The CRM has five contacts with no ERP code, and one of them has no
email at all.

```text
--8<-- "docs/entities-resolution/erp-customers.csv"
```

```text
--8<-- "docs/entities-resolution/crm-contacts.csv"
```

The manifests read the files from `/tmp/acme-resolution/` on the hub's filesystem. Copy them in,
then land both sources:

```bash
docker exec lumnik-hub-1 mkdir -p /tmp/acme-resolution
for f in docs/entities-resolution/*.csv; do docker cp "$f" lumnik-hub-1:/tmp/acme-resolution/; done
lm apply -f docs/entities-resolution/10-erp.yaml
lm apply -f docs/entities-resolution/20-crm.yaml
lm connector run res-erp
lm connector run res-crm
```

```
$ lm run list --connector res-crm
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
65e53f0d  res-crm    default   Completed  5   5    0        0             2026-10-15 15:01  2026-10-15 15:01
```

The entity declares one rule: same email address, compared in lower case.

```yaml
--8<-- "docs/entities-resolution/30-customer.yaml"
```

Apply it, then resolve it once. From now on, every run of a source that ends `Completed` or
`Partial` resolves the rows it wrote, on its own. A `Failed` or `Cancelled` run is not resolved
automatically: the next `lm entity resolve` covers it.

```
$ lm entity apply -f docs/entities-resolution/30-customer.yaml
Applied entity "Customer" → connector."v_customer"
```

```
$ lm entity resolve Customer
rows seen: 9
resolved: 7
ambiguous: 1
unresolved (no rule): 1
unresolved (provider): 0
newly minted: 5
provider calls: 0
provider failures: 0
rule hits:
  customer:email: 8
```

```
$ lm entity get Customer
CODE   NAME                 EMAIL                             CRM_ID  _CONFLICTS
C-001  Hydraulique Lyon     contact@hydraulique-lyon.example  9001    {"name": [{"src": "t_res_erp", "value": "Hydraulique Lyon"}, {"src": "t_res_crm", "value": "HYDRAULIQUE LYON"}], "email": [{"src": "t_res_erp", "value": "contact@hydraulique-lyon.example"}, {"src": "t_res_crm", "value": "Contact@Hydraulique-Lyon.example"}]}
C-002  Fixations Milano     ventes@fixations-milano.example   9002    {}
C-003  Filtration Lyon      compta@groupe-rhone.example               {}
C-004  Levage Lyon          compta@groupe-rhone.example               {}
       Groupe Rhône         compta@groupe-rhone.example       9003    {}
       Pneumatique Porto    geral@pneumatique-porto.example   9005    {}
       Transmission Aarhus                                    9004    {}
```

- **`9001` joined `C-001`** even though the CRM writes the address in capitals: the rule compares in
  lower case. Where the two systems still disagree on a value, the row says so in `_conflicts`,
  exactly as with [`match:`](entities.md#the-_conflicts-ledger).
- **`9003` stayed on its own.** Its address belongs to two ERP customers, and the rule cannot
  choose between them. It is *ambiguous*, and an ambiguous row is never merged.
- **`9004` stayed on its own.** It has no email, so no rule could even try. Nothing is lost: the row
  is still there, waiting for a better rule or a correction.
- **`9005` is a new customer** known only to the CRM. It gets an identity of its own.

**A row already resolved is never moved silently.** If a later pass would join it to a different
customer (the data changed, or so did a rule), the row becomes *ambiguous* and waits for you. Only
a [correction](#correct-it) moves it.

## Review what it could not decide

```
$ lm entity ambiguous Customer
IDENTITY_REF                          SOURCE     SOURCE_ID  CANDIDATES                                                                 DECIDED_BY  OCCURRED_AT
32e62f82-e688-4fd7-b999-a2e9af46418d  t_res_crm  9003       6d9256ad-2760-47c1-9670-7257e9d5de46,6f705d52-3c97-4b4c-bccc-16b7ce9818d9  automatic   2026-10-15 15:01
```

`lm entity history NAME SOURCE ID` explains any decision, one line per pass:

```
$ lm entity history Customer t_res_crm 9003
OCCURRED_AT          OUTCOME    RULE   PROVIDER  CANDIDATES                                                                 SET_ASIDE  DECIDED_BY  RUN_ID
2026-10-15 15:01:53  AMBIGUOUS  email            6d9256ad-2760-47c1-9670-7257e9d5de46,6f705d52-3c97-4b4c-bccc-16b7ce9818d9             automatic
```

`C-004` shares `C-003`'s mailbox but comes from the same ERP. One customer never holds two rows of
the same source, so `C-004` got its own identity, and its history shows the one it was set aside
from:

```
$ lm entity history Customer t_res_erp C-004
OCCURRED_AT          OUTCOME   RULE  PROVIDER  CANDIDATES                            SET_ASIDE                             DECIDED_BY  RUN_ID
2026-10-15 15:01:53  RESOLVED                  6f705d52-3c97-4b4c-bccc-16b7ce9818d9  6d9256ad-2760-47c1-9670-7257e9d5de46  automatic
```

## Correct it

You know that *Groupe Rhône* is the billing name of *Filtration Lyon*. Say so, naming each row as
`SOURCE:ID`, then resolve:

```
$ lm entity assert-same Customer --left t_res_crm:9003 --right t_res_erp:C-003 --reason "Groupe Rhône is Filtration Lyon's billing name"
recorded: t_res_crm:9003 and t_res_erp:C-003 are same
```

```
$ lm entity resolve Customer
rows seen: 9
resolved: 8
ambiguous: 0
unresolved (no rule): 1
unresolved (provider): 0
newly minted: 0
provider calls: 0
provider failures: 0
rule hits:
  customer:email: 7
```

```
$ lm entity get Customer --filter crm_id=9003
CODE   NAME             EMAIL                        CRM_ID  _CONFLICTS
C-003  Filtration Lyon  compta@groupe-rhone.example  9003    {"name": [{"src": "t_res_erp", "value": "Filtration Lyon"}, {"src": "t_res_crm", "value": "Groupe Rhône"}]}
```

- **A correction outranks every rule**, and it survives every later run and every `lm entity
  resolve`. The history records it as `asserted`.
- **A correction takes effect at the next resolve**, either a run of one of the sources or `lm
  entity resolve`.
- **To undo it**, assert the opposite:
  `lm entity assert-distinct Customer --left t_res_crm:9003 --right t_res_erp:C-003`, then resolve.
  `C-003` stops being a candidate for `9003`. Here only `C-004` is left, so the next pass joins
  `9003` to `C-004`. If that is wrong too, assert it distinct as well.
- **Two rows of the same source can't be the same customer**: such an assertion is refused (`400`).
- **A row is named as the history names it.** A mistyped one is refused, and the message says what
  to check:

```
$ lm entity assert-same Customer --left t_res_crm:9030 --right t_res_erp:C-003
Error: API error 404: entity 'Customer' has no row t_res_crm:9030 — check it with lm entity history Customer t_res_crm 9030, or run lm entity resolve Customer if the row is new
```

## Every run resolves on its own

Add `C-005,Pneumatique Porto,geral@pneumatique-porto.example` to the ERP file and run the ERP
again. The new row joins the CRM's `9005` with no other gesture:

```
$ lm connector run res-erp
run scheduled: 5c499557-1f82-4904-ac04-842707435712
```

```
$ lm entity get Customer --filter crm_id=9005
CODE   NAME               EMAIL                            CRM_ID  _CONFLICTS
C-005  Pneumatique Porto  geral@pneumatique-porto.example  9005    {}
```

```
$ lm entity history Customer t_res_erp C-005
OCCURRED_AT          OUTCOME   RULE   PROVIDER  CANDIDATES                            SET_ASIDE  DECIDED_BY  RUN_ID
2026-10-15 15:02:30  RESOLVED  email            3309e600-2682-4aeb-a215-3d06cb6fd989             automatic   5c499557-1f82-4904-ac04-842707435712
```

Run `lm entity resolve NAME` yourself after applying the entity, after changing its rules, and after
a correction.

## In the TUI

Type `:ambiguous`, then the entity name and `Enter`: the screen lists the same rows as
`lm entity ambiguous`.
- `Enter` on a row loads its history.
- `Ctrl-R` resolves the entity again.
- `r` refreshes the list.

Corrections are made with `lm entity assert-same` / `assert-distinct`, not from this screen.

## With joins and Search

- **[`joins[]`](entities.md#fuse-or-join) work on a `resolution:` entity too.** They are read on top
  of the resolved customers: each row gets the lookup's value. A key that the lookup holds more than once
  leaves the joined column empty and lists those rows in `_conflicts`; it never picks one.
- **A [SearchIndex](search/search-indexes.md#columns-joined-from-a-lookup-table) indexes one
  document per resolved customer.**
  - A row nothing has decided yet (ambiguous, or with no rule) is not a document until it is
    decided.
  - A change in a lookup table re-indexes every customer whose rows hold the changed key.
- **A document's freshness (`asOf`) waits for the resolution.** It moves only once the resolution
  pass has covered every run that wrote the entity's sources, and once the lookup tables have been
  read.
  - A `Failed` or `Cancelled` run holds `asOf` back until the next `lm entity resolve`.
  - After upgrading the hub, run `lm entity resolve NAME` once: it covers the runs made before the
    upgrade, and `asOf` can move again.

## The manifest

| Key | What it says |
|---|---|
| `resolution.identity` | for each source table, the column that identifies one of its rows (`t_res_crm: crm_id`) — one column per source, every source listed |
| `resolution.cascade` | the rules, tried in order — at least one |
| `cascade[].id` | the rule's name, shown in the history |
| `cascade[].exact.fields` | canonical columns that must be equal — several fields must all be equal |
| `cascade[].exact.normalize` | how values are compared: `lower`, `upper`, `remove-diacritics`, `strip-invisible`, `fix-mojibake`, `replace-smart-quotes`, `decode-html-entities`, `titlecase`, `clean`. Here `clean` only removes a leading byte-order mark: it does not trim spaces |
| `cascade[].propose` | asks an external candidate provider (`provider`, `acceptAbove`, `reviewAbove`). No provider ships today, so such a rule leaves its rows *unresolved (provider)* |

Rules run in order, and a later rule can settle what an earlier one left ambiguous. The same entity
with a second rule on the name, applied as `Customer2`: a CRM contact *Levage Lyon* (`9007`) at the
shared mailbox is ambiguous by email, then resolved to `C-004` by name:

```yaml
cascade:
  - id: email
    exact: { fields: [email], normalize: [lower] }
  - id: name
    exact: { fields: [name], normalize: [lower] }
```

```
$ lm entity history Customer2 t_res_crm 9007
OCCURRED_AT          OUTCOME    RULE   PROVIDER  CANDIDATES                                                                 SET_ASIDE  DECIDED_BY  RUN_ID
2026-10-15 15:04:03  AMBIGUOUS  email            b82d893a-9ff5-4de0-8f9f-a8893a375d04,f969ba42-8c76-403e-8a93-d5998bff3d4e             automatic
2026-10-15 15:04:03  RESOLVED   name             b82d893a-9ff5-4de0-8f9f-a8893a375d04                                                  automatic
```

- `match:` and `resolution:` are two ways to fuse; an entity declares one or the other.
- `_entity_id`, `_resolution`, `_source_id` and `_handle` are reserved column names.
- A resolved entity is read like any other: [`lm entity get`](entities.md#read-api), `lm view`,
  `lm ask`, an [export](apps/exports.md), a [SearchIndex](search/search-indexes.md).

## Operating it

- **`LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET` is required**: the hub does not start without it.
  `deploy/selfhost/up.sh` generates it; with Helm, set `secrets.entityResolutionHmacSecret`.
  Changing it later invalidates every resolution fingerprint (the next `lm entity resolve`
  recomputes them) and every open view page link. Back it up with your other secrets.
- **After upgrading the hub**, apply each `resolution:` entity again with the same manifest, then run
  `lm entity resolve NAME`.
- `LUMNIK_ENTITY_RESOLUTION_BATCH_SIZE` (default `500`) sets how many rows a pass reads at a time.

## See also

- [kind: Entity — fuse](entities.md) — `match:`, the `_conflicts` ledger, `joins[]`
- [Querying an entity](entities.md#read-api)
- [The two editions](editions.md)
