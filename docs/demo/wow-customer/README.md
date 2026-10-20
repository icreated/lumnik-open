# WOW demo — one Customer, fused from two legacy systems, alive over REST

> Take **Customer**, fuse it from **two legacy sources** — a **SOAP/Siebel CRM** and a nightly
> **CSV export** — and serve **one Customer**, conflicts kept, over REST. One manifest over the
> live hub; no ETL job, no warehouse.

You have no Siebel at hand, so this folder ships **`fake-siebel.py`** (Python standard library
only), which answers the `CustomerQueryPage` SOAP call. Everything else is real lumnik.

```
┌─ fake-siebel.py  (SOAP QueryPage) ─→ siebel-customers ─→ connector.cust_siebel ┐
│                                                                                 ├─→ Customer entity
└─ customer.csv    (flat-file export) ─→ cust-csv ────────→ connector.t_cust_csv ──┘   (fuse on customer_code)
                                                                                              │
                                                                                              ▼
                                                        lm entity get customer  →  fused rows + _conflicts
```

## What's in this folder

| File | Role |
|------|------|
| `fake-siebel.py` | Stand-in Siebel — answers a `CustomerQueryPage` SOAP call. |
| `customer.csv` | The second source: a flat-file customer export. |
| `siebel-connector.yaml` | `rest-generic` connector with a `kind: soap` endpoint → lands `connector.cust_siebel`. |
| `csv-connector.yaml` | `csv-file` connector → lands `connector.t_cust_csv`. |
| `customer-entity.yaml` | The `kind: Entity` manifest — fuses both on `customer_code`. |
| `clients-view.yaml` | `kind: View` — a list over the fused Customer. |
| `lyon-process.yaml` | `kind: Process` — emits a decision when a Lyon customer lands (Act 2). |
| `fake-n8n.py` | Stand-in webhook receiver for Act 2. |
| `commandes.csv` | Act 3's source: commandes with dirty statuses, `EN LITIGE` undeclared. |
| `commandes-connector.yaml` | `csv-file` connector → lands `connector.t_commandes_csv` (Act 3). |
| `commande-workflow.yaml` | `kind: Workflow` — the declared commande lifecycle (Act 3). |
| `docker-compose.wow.yml` | Overlay for the self-host stack: mounts this folder at `/demo`, adds `host.docker.internal`. |

## Replay it by hand (on the self-host stack)

On the stack `deploy/selfhost/up.sh` brings up. **Run every command from the repo root.** On
Windows, run them in the **Ubuntu** window ([Deploy on Windows](../../deploy-windows.md)), or spell
`lm` as `.\lm.exe` in PowerShell.

Three prerequisites — run each ✔ check: a missing one fails silently later.

- **The stack, with the demo folder mounted:**
  ```bash
  ./deploy/selfhost/up.sh --build
  docker compose -f docker-compose.selfhost.yml \
                 -f docs/demo/wow-customer/docker-compose.wow.yml up -d hub
  # wait until the restarted hub is healthy — an early lm call answers 401
  until [ "$(docker inspect -f '{{.State.Health.Status}}' \
        "$(docker compose -f docker-compose.selfhost.yml ps -q hub)")" = healthy ]; do sleep 3; done
  ```

    - ✔ **Check the mount:** `docker compose -f docker-compose.selfhost.yml exec hub ls /demo`
      lists `customer.csv`. Without it the CSV run reports `Completed` with **0 rows**.
    - A later `up.sh` run removes the overlay: run the `up -d hub` line again after it.
    - The ask step (Pro) also needs Ollama and its models: [Prerequisites](../../quickstart/prerequisites.md).

- **The fake Siebel**, on the host, left running: `python3 docs/demo/wow-customer/fake-siebel.py`
  ("address already in use" means it is already up).
  ✔ **Check it answers:** `curl -s -X POST http://localhost:8077/eai | head -c 60` prints
  `<soap:Envelope xmlns:soap=…`. Without it the Siebel run fails.
- **`lm` on your PATH** — [Install `lm`](../../quickstart/installer.md#install-lm).

**Log in.** The demo user `integrator` (tenant 1) gets a per-install password from `up.sh`,
written to `.env` as `INTEGRATOR_PASSWORD`. Change it for anything real.

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login        # open the URL it prints, sign in as integrator
```

### 1. Land both sources

```bash
lm apply -f docs/demo/wow-customer/siebel-connector.yaml
lm connector run siebel-customers
lm apply -f docs/demo/wow-customer/csv-connector.yaml
lm connector run cust-csv
```

```
$ lm apply -f docs/demo/wow-customer/siebel-connector.yaml
{"name":"siebel-customers","connectorId":"eab3cb2e-0b84-4b78-b805-6027b36d3c2c","endpointsApplied":1}
$ lm connector run siebel-customers
run scheduled: 4197d406-fdf1-4904-a9a6-e435ef1f57ef
```

✔ **Checkpoint** — "run scheduled" is not "run succeeded". Both runs must read `Completed`:

```
$ lm run list
ID        CONNECTOR         ENDPOINT   STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
9ef96149  cust-csv          default    Completed  8   8    0        0             2026-10-19 01:38  2026-10-19 01:38
4197d406  siebel-customers  customers  Completed  2   2    0        0             2026-10-19 01:38  2026-10-19 01:38
```

| You see | Fix |
|---|---|
| `siebel-customers` `Failed` | the fake Siebel is not running |
| `cust-csv` `Completed` with `0` / `0` | the `/demo` mount is missing — fix it, re-run the connector |

### 2. Fuse them

Only once both runs above landed their rows:

```
$ lm entity apply -f docs/demo/wow-customer/customer-entity.yaml
Applied entity "Customer" → connector."v_customer"
```

An error naming a relation that "does not exist" means a run above did not land.

### 3. Read the fused Customer

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

- **C-100** — both systems agree on the name; `email` and `city` come from the CSV.
- **C-200** — the systems **disagree** on the name (Siebel `Marie`, CSV `MARIE A.`). Siebel is
  listed first in the manifest, so its value is shown; both are kept in `_conflicts`.
- **C-300** and the others — only in the CSV. Fused, no conflict, no segment.

`lm entity get customer -o json` prints the same rows as JSON. Each tenant sees only its own customers.

### 4. Ask it (Pro)

The entity declares `scopes: [clients]`, so applying it makes the fused Customer askable in that scope:

```bash
lm ask --scope clients "combien de clients à Lyon ?"
# → the answer, computed over the fused Customer, with the SQL that ran
```

## It refuses an unknown value

`customer-entity.yaml` declares `segment.values: [GOLD, SILVER, BRONZE]`. A question about any
other value is refused before anything runs — never answered with "0":

```bash
lm ask --scope clients "combien de clients Platinum ?"     # Pro
# → I won't run this: 'PLATINUM' is not a declared value of segment (known: GOLD, SILVER, BRONZE).
#   If it should exist, add it to the entity manifest.
```

A view filter on the same value is refused the same way, on every edition:

```
$ lm view -f docs/demo/wow-customer/clients-view.yaml --filter segment=PLATINUM
Error: API error 400: 'PLATINUM' is not an allowed value for segment (allowed: GOLD, SILVER, BRONZE)
```

If `PLATINUM` is real, declare it in the entity manifest.

## See it — the first app surface (kind:View)

`clients-view.yaml` declares a read-only list over the fused Customer ([reference](../../apps/views.md)):

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

- A bad filter is refused with the reason: an unknown column, a malformed comparison, a value
  out of the declared domain.
- Thresholds: `--filter "montant>1000"`, `--filter "echeance<2026-08-01"` (`>` `<` `>=` `<=`; a
  number or an ISO date). **Quote the filter** — an unquoted `>` is a shell redirect.

**Let the AI write the view** (Pro, [reference](../../apps/views.md#generate-a-view-with-the-ai)):

```bash
lm view generate clients "les clients gold avec leur ville et segment" -o gold.yaml
cat gold.yaml        # the first line is the render command: lm view -f gold.yaml --filter 'segment=GOLD'
lm view generate clients "les commandes du mois de mars"
# → generation abstained: no order data in this scope
```

On a hub without a chat model, `lm view generate` answers:

```
Error: API error 400: generation abstained: no chat model is available on this hub — view generation needs one
```

## Act 2 — the process (kind:Process, notify)

A declared rule watches the CSV and emits a decision when a new customer from Lyon arrives.

> **Why `ville` and scope `demo`.** A process watches the **raw** ingest table,
> `connector.t_cust_csv`, whose columns are the CSV's own (`code, cname, email, ville`). That
> table is in the `demo` scope (the CSV connector's tag), so the process declares `scope: demo`.
> A process whose `on.table` is not in its scope is refused at apply, with the reason.

```bash
# a fake n8n on the host, left running — prints every delivery it receives
python3 docs/demo/wow-customer/fake-n8n.py

# subscribe it to decision.* (the secret is required; fake-n8n does not check it)
echo -n "demo-secret" | lm integration add fake-n8n \
    --url http://host.docker.internal:9099/hook --events 'decision.*' --secret-stdin

lm process apply -f docs/demo/wow-customer/lyon-process.yaml
lm process list
```

```
$ lm process apply -f docs/demo/wow-customer/lyon-process.yaml
{"name":"lyon-client-arrive","table":"connector.t_cust_csv","emit":"decision.lyon-client-arrive"}
$ lm process list
NAME                TABLE                 EMIT                         ENABLED
lyon-client-arrive  connector.t_cust_csv  decision.lyon-client-arrive  true
```

A process fires only on a **new** row. Add a Lyon customer with a `code` the file does not carry
(the file ships C-100 to C-900 without C-400, which [the WOW](../../wow.md) uses), then re-run:

```bash
echo 'C-1000,Colette Vidal,colette@x.fr,Lyon' >> docs/demo/wow-customer/customer.csv
lm connector run cust-csv
```

About ten seconds later, fake-n8n prints the decision (`entity_key` shortened):

```
[fake-n8n] POST /hook received:
{
  "entity": "connector.t_cust_csv",
  "fields": {
    "cname": "Colette Vidal",
    "ville": "Lyon"
  },
  "message": "Nouveau client Colette Vidal à Lyon",
  "process": "lyon-client-arrive",
  "entity_key": "23a25a92a997…"
}
```

`fields` carries the columns the message names, so n8n reads values instead of parsing text.

The same decision waits in the inbox (here next to the WOW's C-400). Acking it is itself an
event (`annotation.decision.acked`), which a webhook subscribed to `annotation.*` receives:

```
$ lm inbox
ID  WHEN         PROCESS             MESSAGE                              ENTITY_KEY
12  à l'instant  lyon-client-arrive  Nouveau client Colette Vidal à Lyon  23a25a92a99755a85b5b050f9c3d510d48769ce9aa22141fe4a3ff9db0a0a004
8   à l'instant  lyon-client-arrive  Nouveau client Camille Roux à Lyon   ff1027320a1acbe2646ce1b2d1e4a42111868a75bb30825dc4609d785e6aaa7f
$ lm inbox ack 12
acked.
$ lm inbox ack 12
acked.
$ lm inbox --all
ID  WHEN         PROCESS             MESSAGE                                       ENTITY_KEY
12  à l'instant  lyon-client-arrive  Nouveau client Colette Vidal à Lyon [traité]  23a25a92a99755a85b5b050f9c3d510d48769ce9aa22141fe4a3ff9db0a0a004
8   à l'instant  lyon-client-arrive  Nouveau client Camille Roux à Lyon            ff1027320a1acbe2646ce1b2d1e4a42111868a75bb30825dc4609d785e6aaa7f
```

- `lm inbox` shows what still needs you; `lm inbox --all` shows everything, treated ones marked `[traité]`.
- Acking twice records one ack.
- Decisions and acks are kept 30 days (`LUMNIK_EVENTS_RETENTION_DAYS`).

## Act 3 — the lifecycle (kind:Workflow)

`commandes.csv` carries dirty statuses: `En cours`, `fini`, `TERMINE ` (trailing space),
`BROUILLON`, `encours` — and twice `EN LITIGE`, which nobody declared. `commande-workflow.yaml`
declares three states, two transitions, `provenance: declared`.

```bash
lm apply -f docs/demo/wow-customer/commandes-connector.yaml
lm connector run commandes-csv
lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
```

```
$ lm run list --connector commandes-csv
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2af6db4c  commandes-csv  default   Completed  7   7    0        0             2026-10-19 01:40  2026-10-19 01:40
$ lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
{"name":"commande-lifecycle","scope":"demo","table":"connector.t_commandes_csv","column":"statut","dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2}],"unobserved":[],"nullOrBlank":0,"tableMissing":false,"keyColumnMissing":false,"keyNullOrBlank":0,"checkUnavailable":false,"unavailableReason":null}}
```

| Field | Here | Means |
|---|---|---|
| `undeclared` | `EN LITIGE`, 2 rows | a value in the column that no state declares — reported, not refused |
| `unobserved` | empty | every declared state was seen at least once |

The manifest also declares `on.key: num` and `dormancy.after: 30d`: a commande left unmoved in a
non-terminal state for 30 days — counted from lumnik's first observation — raises an alert in
`lm inbox`, under `workflow:commande-lifecycle`. Wording and rules:
[Dormancy](../../apps/workflows.md#dormancy-the-memory-of-the-breathing).

On Pro, the chat answers lifecycle questions from the declared graph:

```bash
lm ask --scope demo "pourquoi je ne peux pas clôturer la commande CMD-001 ?"
```

Captured with `llama3.2`, abridged:

> « D'après le workflow, il est possible que la commande CMD-001 soit en cours de traitement
> (EN_COURS) et qu'elle nécessite encore certaines étapes pour être clôturée. Vous pouvez
> vérifier son statut actuel en consultant la table `connector.t_commandes_csv`. Vous pouvez
> également vérifier si les transitions autorisées sont correctement configurées entre les
> états BROUILLON, EN_COURS et TERMINE. »

The workflow carries the lifecycle, not the rows: the model answers from the declared states and
transitions, and points at the table for CMD-001's actual status instead of guessing it. The
wording changes from run to run; the states and transitions it cites must match `commande-workflow.yaml`.
