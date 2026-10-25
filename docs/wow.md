# The WOW — two legacy systems, one customer

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Show, don't explain</p>
<p class="lm-lead" markdown="span">A Siebel CRM over SOAP and a CSV export become one **Customer**. Then the data starts working: a view, a decision, a lifecycle.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Time</strong>

About 30 minutes, on the self-host stack.
</div>
<div class="lm-meta-item" markdown>
<strong>Files</strong>

All in `docs/demo/wow-customer/`. Each command below runs from the root of the clone.
</div>
<div class="lm-meta-item" markdown>
<strong>Edition</strong>

Every step runs on lumnik Open, except the three marked **(Pro)**.
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>No Siebel at hand</strong>

`fake-siebel.py` answers the SOAP call. Everything else is real lumnik.
</div>
</div>
</section>

```
┌─ fake-siebel.py (SOAP) ──→ siebel-customers ─→ connector.cust_siebel ┐
│                                                                      ├─→ Customer (fused on customer_code)
└─ customer.csv (export) ──→ cust-csv ─────────→ connector.t_cust_csv ─┘
```

## Before you start

Check each ✔ line. A missing piece fails silently later.

**1. The stack, with the demo folder mounted.** The hub must see the demo files under `/demo`:

```bash
./deploy/selfhost/up.sh --build
docker compose -f docker-compose.selfhost.yml \
               -f docs/demo/wow-customer/docker-compose.wow.yml up -d hub
```

- Wait one minute: the hub restarts. An `lm` call before that answers `401`.
- ✔ `docker compose -f docker-compose.selfhost.yml exec hub ls /demo` lists `customer.csv`.
- A later `up.sh` keeps this mount.
- The steps marked (Pro) need Ollama and its models: [Prerequisites](quickstart/prerequisites.md#the-ai-layer-ollama).

**2. The fake Siebel**, left running in its own terminal:

```bash
python3 docs/demo/wow-customer/fake-siebel.py
```

- ✔ `curl -s -X POST http://localhost:8077/eai | head -c 60` prints `<soap:Envelope xmlns:soap=…`.
- `address already in use` means it already runs.

**3. `lm`, logged in** as `integrator` ([Quickstart, steps 1 and 2](quickstart/installer.md#1-install-lm)).

## Act 1 — two sources, one customer

### 1. Land both sources

```bash
lm apply -f docs/demo/wow-customer/siebel-connector.yaml
lm connector run siebel-customers
lm apply -f docs/demo/wow-customer/csv-connector.yaml
lm connector run cust-csv
```

??? note "The two manifests and the CSV"

    ```yaml
    --8<-- "docs/demo/wow-customer/siebel-connector.yaml"
    ```

    ```yaml
    --8<-- "docs/demo/wow-customer/csv-connector.yaml"
    ```

    ```csv
    --8<-- "docs/demo/wow-customer/customer.csv"
    ```

`run scheduled` is not `run succeeded`. ✔ Both runs must read `Completed`:

```
$ lm run list
ID        CONNECTOR         ENDPOINT   STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
3e9f353a  cust-csv          default    Completed  8   8    0        0             2026-10-24 21:57  2026-10-24 21:57
9d1792ef  siebel-customers  customers  Completed  2   2    0        0             2026-10-24 21:57  2026-10-24 21:57
```

| You see | Fix |
|---|---|
| `siebel-customers` `Failed` | Start the fake Siebel, then run the connector again. |
| `cust-csv` `Completed` with `0` / `0` | The `/demo` mount is missing. Mount it, then run the connector again. |

### 2. Fuse them

```
$ lm entity apply -f docs/demo/wow-customer/customer-entity.yaml
Applied entity "Customer" → connector."v_customer"
```

An error about a relation that "does not exist" means a run above did not land.

??? note "The entity manifest"

    ```yaml
    --8<-- "docs/demo/wow-customer/customer-entity.yaml"
    ```

### 3. Read the fused customer

```
$ lm entity get customer
CUSTOMER_CODE  NAME              SEGMENT  EMAIL           CITY   _CONFLICTS
C-100          Jean Dupont       GOLD     jd@x.fr         Paris  {}
C-200          Marie             SILVER   m@x.fr          Lyon   {"name": [{"src": "cust_siebel", "value": "Marie"}, {"src": "t_cust_csv", "value": "MARIE A."}]}
C-300          Solo                       solo@x.fr       Nice   {}
C-500          Claire Martin              claire@x.fr     Lyon   {}
C-600          Pierre Corneille           corneille@x.fr  Paris  {}
C-700          Maurice Druon              druon@x.fr      Paris  {}
C-800          Albert Camus               camus@x.fr      Lyon   {}
C-900          Saint Exupery              exupery@x.fr    Lyon   {}
```

- **C-100:** both systems agree. The email and the city come from the CSV.
- **C-200:** the systems disagree on the name. Siebel is listed first in the manifest, so its
  value shows. Both values are kept in `_conflicts`.
- **The others** exist only in the CSV: no segment, no conflict.

### 4. Ask it (Pro)

The entity declares `scopes: [clients]`. Applying it made the customer askable:

```
$ lm ask --scope clients "combien de clients à Lyon ?"
Il y a 4 clients à Lyon.

  COUNT
  4
  1 row returned

  SQL: SELECT COUNT(*) FROM "connector"."v_customer" WHERE "city" = 'Lyon'
LIMIT 500
```

The answer reads the fused customer, and shows the SQL it ran.

### 5. It never answers a value that does not exist

The entity declares `segment` with three values: `GOLD`, `SILVER`, `BRONZE`. A question about
another value gets no number:

```
$ lm ask --scope clients "combien de clients du segment PLATINUM ?"
Je ne peux pas répondre à partir des données disponibles pour ce périmètre — la question ne correspond pas au schéma déclaré.
```

A view filter on that value is refused too, on every edition:

```
$ lm view -f docs/demo/wow-customer/clients-view.yaml --filter segment=PLATINUM
Error: API error 400: 'PLATINUM' is not an allowed value for segment (allowed: GOLD, SILVER, BRONZE)
```

`PLATINUM` is real? Add it to the entity manifest.

## Act 2 — the data works for you

### 6. See it (`kind: View`)

`clients-view.yaml` declares a list over the fused customer:

```
$ lm view -f docs/demo/wow-customer/clients-view.yaml --filter city=Lyon
Clients · city=Lyon

Code client  Nom            Ville  Segment
C-200        Marie          Lyon   SILVER
C-500        Claire Martin  Lyon   
C-800        Albert Camus   Lyon   
C-900        Saint Exupery  Lyon   
$ lm view C-200 -f docs/demo/wow-customer/clients-view.yaml
Clients / C-200

  Code client   C-200
  Nom           Marie
  Ville         Lyon
  Segment       SILVER

  (!) Ambiguïtés et divergences
  Nom   cust_siebel «Marie» · t_cust_csv «MARIE A.»
```

- The detail shows the disagreement, with each system's value.
- Quote a threshold filter: `--filter "montant>1000"`. Unquoted, `>` is a shell redirect.

??? note "The view manifest"

    ```yaml
    --8<-- "docs/demo/wow-customer/clients-view.yaml"
    ```

**Let the AI write a view (Pro).** Describe it in a sentence:

```
$ lm view generate clients "les clients gold avec leur ville et segment" -o gold.yaml
manifest written to gold.yaml — review it, then: lm view -f gold.yaml
$ head -3 gold.yaml
# asked for: les clients gold avec leur ville et segment
# kept: segment=GOLD — a condition of that description not listed here is NOT enforced
# lm view -f gold.yaml --filter 'segment=GOLD'
```

- `# kept:` lists the conditions the view applies. Check it against what you asked.
- The third line is the command that renders your file.
- A request the scope cannot answer is declined:

    ```
    $ lm view generate clients "les commandes du mois de mars"
    Error: API error 400: generation abstained: The provided tables and columns do not include any information about orders or sales data.
    ```

### 7. React to it (`kind: Process`)

A rule watches the CSV. When a customer from Lyon arrives, it raises a decision. Your webhook
receives it; here, a fake n8n that prints what it gets.

```bash
python3 docs/demo/wow-customer/fake-n8n.py      # in its own terminal, left running
echo -n "demo-secret" | lm integration add fake-n8n \
    --url http://host.docker.internal:9099/hook --events 'decision.*' --secret-stdin
lm process apply -f docs/demo/wow-customer/lyon-process.yaml
```

```
registered: fake-n8n (id 1000) — forwards subscribed events from now on
{"name":"lyon-client-arrive","table":"connector.t_cust_csv","emit":"decision.lyon-client-arrive"}
```

??? note "The process manifest"

    ```yaml
    --8<-- "docs/demo/wow-customer/lyon-process.yaml"
    ```

A process fires on **new rows only**. Add a Lyon customer the file does not have, then run the
connector again:

```bash
echo 'C-400,Camille Roux,camille@x.fr,Lyon' >> docs/demo/wow-customer/customer.csv
lm connector run cust-csv
```

A few seconds later, the fake n8n prints the decision (`entity_key` shortened here):

```
[fake-n8n] POST /hook received:
{
  "entity": "connector.t_cust_csv",
  "fields": {
    "cname": "Camille Roux",
    "ville": "Lyon"
  },
  "message": "Nouveau client Camille Roux à Lyon",
  "process": "lyon-client-arrive",
  "entity_key": "ff1027320a1a…"
}
```

`fields` carries the columns the message names: n8n reads values, it does not parse text.

### 8. Decide (`lm inbox`)

The same decision waits in the inbox. A person treats it:

```
$ lm inbox
ID  WHEN      PROCESS             MESSAGE                             ENTITY_KEY
8   just now  lyon-client-arrive  Nouveau client Camille Roux à Lyon  ff1027320a1acbe2646ce1b2d1e4a42111868a75bb30825dc4609d785e6aaa7f
$ lm inbox ack 8
acked.
```

- `lm inbox --all` also shows the treated ones, marked `[acked]`.
- The `echo` changed a file of the clone. `git checkout docs/demo/wow-customer/customer.csv`
  restores it.

## Act 3 — the lifecycle (`kind: Workflow`)

### 9. Declare what a status means

`commandes.csv` holds dirty statuses: `En cours`, `fini`, `TERMINE ` with a trailing space,
`encours`. Twice it holds `EN LITIGE`, which nobody declared. Land it first, then declare the
lifecycle:

```bash
lm apply -f docs/demo/wow-customer/commandes-connector.yaml
lm connector run commandes-csv
lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
```

```
$ lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
Applied workflow "commande-lifecycle" → connector.t_commandes_csv.statut, scope demo
  in the data, not declared: EN LITIGE (2 rows)
```

- `EN LITIGE`, 2 rows: it is in the data and in no declared state. The apply reports it and
  succeeds.
- No `declared, not in the data` line: every declared state was seen in the data.
- Applied before the run, the report says `the table is not there yet`.

??? note "The connector and the workflow"

    ```yaml
    --8<-- "docs/demo/wow-customer/commandes-connector.yaml"
    ```

    ```yaml
    --8<-- "docs/demo/wow-customer/commande-workflow.yaml"
    ```

### 10. Ask the lifecycle (Pro)

The chat answers from the declared workflow:

```
$ lm chat
lm chat — type :help for commands, :exit to quit

[corpus=* scope=default topK=10] > :scope demo
scope → demo

[corpus=* scope=demo topK=10] > quels sont les états d une commande et quelles transitions sont autorisées ?
Les états d'une commande sont les suivants :

* BROUILLON (initial)
* EN_COURS (« En préparation », alias : en cours, encours)
* TERMINE (terminal, alias : fini)

 Les transitions autorisées entre ces états sont :
 * BROUILLON -> EN_COURS
 * EN_COURS -> TERMINE
```

The states, aliases and transitions are the manifest's, word for word. The wording changes
from one run to the next; the facts must not.

### 11. Silence becomes a signal

The workflow also declares `dormancy.after: 30d`. An order left in the same non-terminal state
for 30 days raises an alert in `lm inbox`.

- The 30 days count from the day lumnik first saw the order.
- To see it now, set `after: 1m` in a copy of the manifest and apply it. The check runs every
  15 minutes: allow up to 30 minutes.

With `after: 1m`:

```
$ lm inbox
ID  WHEN      PROCESS                      MESSAGE                                                                                                                              ENTITY_KEY
23  just now  workflow:commande-lifecycle  CMD-007: observed unmoved in "EN LITIGE" since 2026-10-18 (first observed 2026-10-18) — declared threshold 1m in commande-lifecycle  CMD-007
22  just now  workflow:commande-lifecycle  CMD-006: observed unmoved in "EN_COURS" since 2026-10-18 (first observed 2026-10-18) — declared threshold 1m in commande-lifecycle   CMD-006
21  just now  workflow:commande-lifecycle  CMD-005: observed unmoved in "EN LITIGE" since 2026-10-18 (first observed 2026-10-18) — declared threshold 1m in commande-lifecycle  CMD-005
20  just now  workflow:commande-lifecycle  CMD-004: observed unmoved in "BROUILLON" since 2026-10-18 (first observed 2026-10-18) — declared threshold 1m in commande-lifecycle  CMD-004
19  just now  workflow:commande-lifecycle  CMD-001: observed unmoved in "EN_COURS" since 2026-10-18 (first observed 2026-10-18) — declared threshold 1m in commande-lifecycle   CMD-001
```

- `CMD-002` and `CMD-003` never alert: `TERMINE` is a terminal state.
- `EN LITIGE` is undeclared, so never terminal: its orders alert like the others.

More: [Dormancy](apps/workflows.md#dormancy-the-memory-of-the-breathing).

## After the demo — the customer's own data

1. **Point at the real database.** `lm source jdbc test`, then `discover`, then `add`. A
   `SELECT`-only account is enough. [The guided flow](connectors/jdbc.md#guided-flow-lm-source-jdbc)
2. **Repair at the door.** On a file export, [transformers](connectors/transformers.md) fix
   broken accents and legacy dates. A database table is read as it is: rename a column in its
   manifest, or read a view of the source.
3. **Name a scope, run, ask (Pro).** [What makes a scope answerable](quickstart/installer.md#what-makes-a-scope-answerable)

What takes calendar time is outside lumnik: the `SELECT` account, the network path.
