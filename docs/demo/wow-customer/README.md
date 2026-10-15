# WOW demo — one Customer, fused from two legacy systems, alive over REST

The 60-second integrator story:

> Take **Customer** (the hero entity), fuse it from **two realistic legacy sources** — a
> **SOAP/Siebel CRM** and a nightly **CSV export** — and serve **one canonical, conflict-aware
> Customer** over REST. No ETL job, no warehouse. A declarative manifest over the live hub.

You don't have a Siebel to point at — so this folder ships a tiny **`fake-siebel.py`** (stdlib only) that
speaks just enough of the `CustomerQueryPage` dialect. Everything else is real lumnik.

```
┌─ fake-siebel.py  (SOAP QueryPage) ─→ siebel-customers ─→ connector.cust_siebel ┐
│                                                          (tenant_id + RLS)      │
│                                                                                 ├─→ Customer entity
└─ customer.csv    (flat-file export) ─→ csv-customers ──→ connector.t_cust_csv ──┘   (fuse on customer_code)
                                                          (tenant_id + RLS)                   │
                                                                                              ▼
                                              GET /api/entities/customer  →  fused JSON + _conflicts ledger
```

## What's in this folder

| File | Role |
|------|------|
| `fake-siebel.py` | Stand-in Siebel — POST → a `CustomerQueryPage` SOAP envelope (stdlib only). |
| `customer.csv` | The second legacy source: a flat-file customer export. |
| `siebel-connector.yaml` | `kind: soap` connector → lands `connector.cust_siebel`. |
| `csv-connector.yaml` | `csv-file` connector → lands `connector.t_cust_csv`. |
| `customer-entity.yaml` | The `kind: Entity` manifest — fuses both on `customer_code`. |
| `clients-view.yaml` | `kind: View` — the first app surface: a list over the fused Customer. |
| `lyon-process.yaml` | `kind: Process` — emits a decision when a Lyon customer lands (Act 2). |
| `fake-n8n.py` | Stand-in webhook receiver for Act 2 (stdlib only). |
| `commandes.csv` | Act 3's source: a dirty statuses export, `EN LITIGE` lurking undeclared. |
| `commandes-connector.yaml` | `csv-file` connector → lands `connector.t_commandes_csv` (Act 3). |
| `commande-workflow.yaml` | `kind: Workflow` — the declared commande lifecycle (Act 3). |
| `docker-compose.wow.yml` | Overlay for the self-host stack: mounts this folder at `/demo`, adds host-gateway. |

## The proof (headless, nightly-verified)

The exact same fil runs as an integration test (`CustomerWowFusionIT`) — start there if you
just want certainty. Scope of that guard: no Java test runs per PR; the suite runs in
the **nightly** `mvn verify` (non-blocking, so a red can sit for up to a day):

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -pl lumnik-hub verify -Dit.test='CustomerWowFusionIT' \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NoSuchUnitTest
```

`CustomerWowFusionIT` stands up a WireMock fake-Siebel, runs both connectors for real, applies the
manifest, and asserts the fused result over REST — agreement, gap-fill, and a cross-source conflict.

## Replay it by hand (on the self-host stack)

This replays the WOW against the **deployed** stack — real OIDC, no dev-bypass — the one
`deploy/selfhost/up.sh` brings up. **Every command runs from the repo root** (all paths are
relative to it; on Windows, run them in the **Ubuntu** window — see
[Deploy on Windows](../../deploy-windows.md), and spell `lm` as `.\lm.exe` if you drive
from PowerShell instead).

Three prerequisites — **the first two are the ones everyone skips**, and each
failure they cause is silent, so run their ✔ checks:

- The self-host stack running with the demo folder mounted and the host Ollama reachable:
  ```bash
  ./deploy/selfhost/up.sh --build     # hub + postgres + keycloak (real OIDC)
  # recreate the hub with the WOW overlay so it can read /demo/customer.csv:
  docker compose -f docker-compose.selfhost.yml \
                 -f docs/demo/wow-customer/docker-compose.wow.yml up -d hub
  # recreating RESTARTS the hub — wait until healthy before any lm call (an early
  # call gets 401 while the hub is still establishing OIDC with Keycloak):
  until [ "$(docker inspect -f '{{.State.Health.Status}}' \
        "$(docker compose -f docker-compose.selfhost.yml ps -q hub)")" = healthy ]; do sleep 3; done
  ```
  ✔ **Check the mount** — the hub must see the demo files:
  ```bash
  docker compose -f docker-compose.selfhost.yml exec hub ls /demo
  ```
  You should see `customer.csv` in the list. If you get *No such file or
  directory*, the overlay `up -d hub` above didn't run — without it the CSV
  connector later "succeeds" with **0 rows** and the fusion has nothing to fuse.

  (Ollama + models: see `deploy/selfhost/README.md` — the "ask" step needs them.)
- The fake Siebel, on the host: `python3 docs/demo/wow-customer/fake-siebel.py` (leave it
  running; if it says "address already in use", it's already up — skip this).
  ✔ **Check it answers** (from another terminal):
  ```bash
  curl -s -X POST http://localhost:8077/eai | head -c 60
  ```
  You should see the start of a SOAP envelope — `<soap:Envelope xmlns:soap=…`.
  (It's a fake *Siebel*: it speaks XML, as the real one would.) Without it the
  Siebel run below fails — and only `lm run list` will tell you.
- The `lm` binary on PATH (download it from a release, or build it — Go 1.26+, the floor is `lm/go.mod`):
  `make -C lm install` — builds and copies it to `~/.local/bin`, no `sudo`
  — or, on Windows, `lm.exe` ([Deploy on Windows, step 6](../../deploy-windows.md)).

**Point `lm` at the stack and log in.** The bundled realm ships a demo integrator
(`integrator`, tenant 1) with **no password baked in** — `up.sh` mints one per install
through the Keycloak admin API, written to `.env` as `INTEGRATOR_PASSWORD`. Change it for
anything real.

```bash
lm config set-context selfhost --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context selfhost
lm login                                     # prints a URL + device code — open it, sign in as integrator (password: INTEGRATOR_PASSWORD in .env)
```

Then run the full flow — every call is a real Bearer-authenticated request:

```bash
# 1. Source 1 — the SOAP/Siebel CRM
lm apply -f docs/demo/wow-customer/siebel-connector.yaml
lm connector run siebel-customers           # → run scheduled: <runId>
                                             #   lands connector.cust_siebel  (C-100, C-200)

# 2. Source 2 — the CSV export (read from /demo inside the hub container)
lm apply -f docs/demo/wow-customer/csv-connector.yaml
lm connector run cust-csv                    # → run scheduled: <runId>
                                             #   lands connector.t_cust_csv   (C-100, C-200, C-300)

# ✔ CHECKPOINT — "run scheduled" is NOT "run succeeded". Look at the ledger:
lm run list
#   expected: siebel-customers  Completed  IN=2 OUT=2   ·   cust-csv  Completed  IN=8 OUT=8
#   Failed on the siebel run?      → fake-siebel isn't running (prerequisite ✔ above).
#   Completed but 0/0 on the csv?  → the /demo mount is missing (prerequisite ✔ above);
#                                    fix it, then re-run the connector.

# 3. Fuse them into the hero entity — only once BOTH runs above landed their rows
lm entity apply -f docs/demo/wow-customer/customer-entity.yaml
# → Applied entity "customer" → connector.v_customer
#   (an API error naming a relation that "does not exist" means a source run above
#    didn't land — go back to the checkpoint)

# 4. Ask the hub for the fused Customer over REST (read-only, tenant-scoped by your JWT).
#    curl can't share lm's login session, so grab a password-grant token from Keycloak —
#    it is the SAME Bearer identity (integrator, tenant 1) by another road (password from
#    .env, INTEGRATOR_PASSWORD — up.sh set it per install, there is no static one):
PW=$(grep '^INTEGRATOR_PASSWORD=' .env | cut -d= -f2-)
TOKEN=$(printf '%s' "$PW" | curl -s -X POST http://localhost:8180/realms/lumnik/protocol/openid-connect/token \
     -d grant_type=password -d client_id=lm-cli \
     -d username=integrator --data-urlencode password@- | jq -r .access_token)
curl -s 'http://localhost:8080/api/entities/customer' -H "Authorization: Bearer $TOKEN" | jq

# 5. Ask the fused Customer a question in natural language (per-métier text-to-SQL)
lm ask --scope clients "combien de clients à Lyon ?"
```

## What you see

Three customers, fused from two systems that never agreed:

- **C-100** — Siebel and the CSV agree on the name; `email`/`city` are **gap-filled** from the CSV.
  `_conflicts` is empty.
- **C-200** — the systems **disagree** on the name (Siebel `Marie` vs CSV `MARIE A.`). The hub keeps
  Siebel as the representative (it's listed first) **and** preserves both in the `_conflicts` ledger —
  nothing is silently dropped.
- **C-300** — exists **only** in the CSV. Still fused, no conflict.

```json
[
  { "customer_code": "C-100", "name": "Jean Dupont", "email": "jd@x.fr", "city": "Paris", "_conflicts": "{}" },
  { "customer_code": "C-200", "name": "Marie", "email": "m@x.fr", "city": "Lyon",
    "_conflicts": "{\"name\":[{\"src\":\"cust_siebel\",\"value\":\"Marie\"},{\"src\":\"t_cust_csv\",\"value\":\"MARIE A.\"}]}" },
  { "customer_code": "C-300", "name": "Solo", "email": "solo@x.fr", "city": "Nice", "_conflicts": "{}" }
]
```

The fused view is `security_invoker`, so it inherits each base table's row-level security for free —
each tenant sees only its own customers through the fusion (pinned by an RLS integration test).

## Ask it (the last 20 seconds of the WOW)

Because `customer-entity.yaml` declares `scopes: [clients]`, applying it makes the fused Customer
**discoverable** to the per-métier chat — `lm entity apply` registers the view and rebuilds the
`clients` schema card, so the ask-to-SQL engine can translate a natural-language question into a
read-only `SELECT` over `connector.v_customer`:

```bash
lm ask --scope clients "combien de clients à Lyon ?"
# → an answer computed over a fused truth that lived in neither Siebel nor the CSV alone.
```

The chat reads through the same `security_invoker` view, so the answer is tenant-scoped for free.

## Zero-row honesty: it refuses the deceptive "none"

The cardinal sin of an enterprise data chat isn't a wrong query — it's a confident *"there are none"*
that gets the user blamed by their boss. Because `customer-entity.yaml` declares
`segment.values: [GOLD, SILVER, BRONZE]`, the chat **knows the domain**. Ask for a value that isn't in
it and the system **refuses before running anything** — it never returns 0 rows phrased as a fact:

```bash
lm ask --scope clients "combien de clients Platinum ?"
# → I won't run this: 'PLATINUM' is not a declared value of segment (known: GOLD, SILVER, BRONZE).
#   If it should exist, add it to the entity manifest.
```

The refusal holds in both cases: if `PLATINUM` is a hallucination, it's caught; if it's a real
segment the model just doesn't know about, the message tells you exactly how to teach it (declare it).
Abstention over a confident-wrong answer — always.

## See it — the first app surface (kind:View)

Before reacting to the data, look at it: `clients-view.yaml` declares a read-only list over the
fused Customer — no code, one YAML ([reference](../../apps/views.md)).

    lm view -f docs/demo/wow-customer/clients-view.yaml --filter city=Lyon
    # Clients — city=Lyon
    # CUSTOMER_CODE  NAME   CITY  SEGMENT
    # C-200          Marie  Lyon  SILVER

    lm view C-200 -f docs/demo/wow-customer/clients-view.yaml
    # the record's detail — including the _conflicts ledger (Siebel 'Marie' vs CSV 'MARIE A.')

A filter is refused with the reason named, never silently ignored: an unknown column, a malformed comparison,
or a `segment=PLATINUM` (out of the declared domain) each name the problem instead of returning
a lying empty table.

Thresholds work too — `--filter "montant>1000"`, `--filter "echeance<2026-08-01"`
(`>` `<` `>=` `<=`; the value must be a number or an ISO date, refused loudly otherwise).

And you don't have to write the YAML yourself — **describe the view, the AI writes the
manifest**, the same card-backed gate validates every column and filter before anything
is suggested ([reference](../../apps/views.md#generate-a-view-with-the-ai)):

    lm view generate clients "les clients gold avec leur ville et segment" -o gold.yaml
    cat gold.yaml
    # first line = the ready-to-run render command, e.g.:
    # lm view -f gold.yaml --filter 'segment=GOLD'

    lm view generate clients "les commandes du mois de mars"
    # → generation abstained: no order data in this scope — the model refuses
    #   to invent columns rather than fake an answer.
**Quote the filter** — an unquoted `>` is a shell redirect that truncates a file named `1000`.


## Act 2 — the process (kind:Process, notify)

The dragon's first rung — **proven live 2026-07-05**: a declared rule watches the CSV and emits a
decision when a new customer from Lyon arrives (Marie's city).

> **Why `ville` on scope `demo`.** A process triggers on `data.row.changed`, born at the **raw**
> write path — so `on.table` is the raw ingest table `connector.t_cust_csv`, whose columns are the
> CSV's own (`code, cname, email, ville`). `segment` is a Siebel field, absent from the CSV. The raw
> table lives in the **`demo`** scope card (the csv connector is tagged `demo`); the fused `clients`
> card names only `connector.v_customer`, so a process's `on.table` must name a raw table that its
> `scope:` card actually carries — otherwise apply 400s, naming the reason.

    # a fake n8n on the host (stdlib only)
    python3 docs/demo/wow-customer/fake-n8n.py

    # subscribe deliveries to decision.* — the real admin API is
    # POST /api/tenants/{tid}/integrations (IntegrationResource, @RequiresPermission("lm_admin")).
    # There is no `lm` CLI wrapper for it yet, so call it straight, with the same Bearer
    # token used for the fused-Customer call above (the `1` in the path = the demo
    # integrator's tenant — use your own tid otherwise):
    curl -s -X POST http://localhost:8080/api/tenants/1/integrations \
         -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
         -d '{"name":"fake-n8n","targetUrl":"http://host.docker.internal:9099/hook",
              "authMode":"NONE","eventTypes":["decision.*"],"enabled":true}'

    # declare the process (this also gates data.row.changed ON for t_cust_csv):
    lm process apply -f docs/demo/wow-customer/lyon-process.yaml
    lm process list

    # a process fires only on a GENUINELY-NEW row (content-hash dedup: re-running the same CSV
    # emits nothing). Add a new Lyon customer, then re-run the connector. Pick a `code` the file
    # does not already carry — customer.csv ships C-100/200/300/500/600/700/800/900, and
    # docs/wow.md's narrated replay spends C-400; an already-present line hashes identically,
    # the dedup swallows it, and the inbox stays empty:
    echo 'C-1000,Colette Vidal,colette@x.fr,Lyon' >> docs/demo/wow-customer/customer.csv
    lm connector run cust-csv

    # ~10-15s later (two 5s consumer ticks), fake-n8n prints the decision payload — this shape,
    # over your row:
    #   {"message":"Nouveau client Colette Vidal à Lyon","process":"lyon-client-arrive",
    #    "entity":"connector.t_cust_csv","entity_key":"<row hash>",
    #    "fields":{"cname":"Colette Vidal","ville":"Lyon"}}
    # the {cname}/{ville} placeholders rendered from the row — and `fields` carries the
    # referenced columns as data, so n8n consumes values, not text-parsing.

`core.webhook_endpoint` normally takes an HMAC `secret` for signed delivery (`authMode: HMAC`); the
demo passes `authMode: NONE` so `fake-n8n.py` doesn't have to verify a signature — real n8n/Make/Zapier
subscriptions should use `HMAC` and check it.

And the same decision, on the human side — no n8n required. The human disposes; the ack is
itself an event (`annotation.decision.acked`), so a webhook subscribed to `annotation.*`
reacts when someone marks it done — and app state stays a fold over the log, never a state row:

    lm inbox
    # the shape, not a transcript — ID and ENTITY_KEY are whatever your own run produced, and
    # the inbox prints ENTITY_KEY whole: its 64 hex characters, unabridged.
    # ID    QUAND        PROCESS             MESSAGE                              ENTITY_KEY
    # <id>  à l'instant  lyon-client-arrive  Nouveau client Colette Vidal à Lyon  <row hash, 64 hex>

    lm inbox ack <id>
    # acked.

    lm inbox            # the fold: only what still needs you
    lm inbox --all      # everything, disposed entries marked [traité]

The ack is idempotent: re-acking the same ID writes no second event and answers the same 200.
The inbox reads the event log, so history is bounded by the outbox retention purge
(`lumnik.events.retention-days`, default 30 days) — it shows what retention keeps, and an
ack expires together with its decision.

## Act 3 — the lifecycle (kind:Workflow)

Replayed live 2026-08-04. **Not** on the self-host stack: on an isolated `quarkus:dev` hub
against a throwaway pgvector and a local Ollama (`llama3.2`), with dev-bypass headers over
`curl` instead of `lm`. So the OIDC path and the CLI itself are still unexercised here — the
hub behaviour below is captured output, the two `lm` invocations are the equivalent calls.

The first replay is also what caught the bug fixed in #200: applying a workflow answered 200
while the chunk never reached the corpus, so the chat stayed ungrounded and only a WARN said
so. Every output below is from the run after that fix.

Twenty years of Excel don't produce clean statuses. `commandes.csv` carries `En cours`,
`fini`, `TERMINE ` (trailing space), `BROUILLON`, `encours` — and twice, `EN LITIGE`, a
value nobody ever told the system about. `commande-workflow.yaml` declares the lifecycle
Madame Martin kept in her head — three states, two transitions, `provenance: declared` —
and applying it doesn't hide the mess: it names it.

    # land the raw commandes table
    lm apply -f docs/demo/wow-customer/commandes-connector.yaml
    lm connector run commandes-csv               # → run scheduled: <runId>
                                                  #   lands connector.t_commandes_csv — the run
                                                  #   recorded 7 in / 7 out / 0 skipped, dirty
                                                  #   statuses and all

    # declare the lifecycle
    lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml

`lm workflow apply` prints the hub's response verbatim — the data-truth confrontation
between the declared graph and what's actually in the column. Captured from the run:

```json
{"name":"commande-lifecycle","scope":"demo","table":"connector.t_commandes_csv","column":"statut",
 "dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2}],"unobserved":[],"nullOrBlank":0,
              "tableMissing":false,"keyColumnMissing":false,"keyNullOrBlank":0,
              "checkUnavailable":false,"unavailableReason":null}}
```

**200, not blocked.** `EN LITIGE` is reported, never refused — it's reality, and reality
doesn't ask the manifest's permission. `unobserved` is empty (every declared state was
seen at least once); had the CSV never carried a `TERMINE`/`fini` row, `TERMINE` would
show up there instead — a declared code that's never actually reached.

The manifest applied above also declares two fields this walkthrough hasn't touched yet:
`on.key: num` and `dormancy.after: 30d`. Together they turn the lifecycle from a static
taxonomy into a **watched** one — a scheduled sweep reads every commande's current state
and alerts when it has sat, unmoved, in a non-terminal state past the threshold. Expect
nothing on the sweep tick right after this `apply`: the clock starts
at lumnik's own first observation of each commande, never at whatever happened in the ERP
before lumnik looked. Alerts show up in `lm inbox` only once `30d` has actually elapsed
from that first observation, tagged `workflow:commande-lifecycle`. Full vocabulary, the
alert's exact wording, and the rules behind it live in
[Dormancy](../../apps/workflows.md#dormancy-the-memory-of-the-breathing) — not repeated
here.

Applying the workflow also indexes it into the `demo`-scope corpus as one chunk — states,
aliases, terminal marks and the transition graph, spelled out with `declared by the
integrator` so the chat can cite *how* this lifecycle is known, never as observed fact.
Now the magasinier can ask the obvious question instead of guessing:

    lm ask --scope demo "pourquoi je ne peux pas clôturer la commande CMD-001 ?"

Captured, `llama3.2`, abridged:

> « D'après le workflow, il est possible que la commande CMD-001 soit en cours de traitement
> (EN_COURS) et qu'elle nécessite encore certaines étapes pour être clôturée. Vous pouvez
> vérifier son statut actuel en consultant la table `connector.t_commandes_csv`. Vous pouvez
> également vérifier si les transitions autorisées sont correctement configurées entre les
> états BROUILLON, EN_COURS et TERMINE. »

Worth reading closely, because it is **less** than the aspirational answer this act used to
show — and that is the correct result. The chunk carries the *lifecycle*, not the rows, so the
model grounds itself in the declared states and transitions and then **abstains on CMD-001's
actual status** rather than guessing it. Naming the table to look in is the correct move when
you know the shape of the answer but not the value.

The contrast is the whole point. Asked the same question with the chunk missing — the state
this demo was in before #200 — the same model had nothing to stand on and read *commande* as
a shell command:

> « je n'ai pas accès aux informations spécifiques sur la commande CMD-001 […] Quel est
> l'environnement dans lequel vous utilisez la commande (par exemple, un système Windows,
> Linux ou macOS) ? »

The exact wording is the model's, run to run — what's fixed is the ground truth it must agree
with: the states, aliases and transitions declared in `commande-workflow.yaml`, never an
invented lifecycle.
