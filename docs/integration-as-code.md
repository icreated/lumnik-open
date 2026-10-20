# Integration as code

An integrator who defrosts one ERP writes its universe once: a directory of YAML manifests,
one per connector, entity, view, app, process and workflow. That directory *is* the installation.
It goes in git, it is reviewed in a pull request, and it is replayed against another tenant
by a loop.

This page is the operating manual for that. Four habits of `lm` make the loop possible:

- a manifest arrives through `-f`, and `-f -` reads it from stdin;
- listings print `-o json|yaml`;
- notes and warnings go to **stderr**, so stdout stays parseable;
- the exit code carries the verdict.

## What belongs in the repository

| In git | Never in git | Rebuilt, not stored |
|---|---|---|
| `kind: Connector`, `Entity`, `View`, `App`, `Process`, `Workflow`, `Export`, `SearchIndex` manifests | secret **values** — manifests carry the *name* (`password_env`, `token_env`, `key_env`), never the credential | the dynamic tables under schema `connector` (ingestion writes them) |
| hook scripts (`META-INF/hooks.yaml`, deployed with the extension — not applied through `lm`) | the OIDC token in `~/.lm/config.yaml` | the RAG corpus (re-indexed from what was ingested) |
| the apply order (below) — a `Makefile` or a numbered prefix | ingested rows, dead letters, run history | discovered schema cards (`lm source rediscover`) |

Credentials are referenced by name and resolved where the **hub** runs — its environment or
its secret store, depending on the connector; each connector page states which. A manifest is
therefore safe to commit, and the same manifest works against dev, staging and production
because only the name travels.

!!! danger "Two fields break that, and a repository should ban them"
    Two manifest fields take a secret **inline**, both declared *dev/test only*:

    | Field | Kind | What it does |
    |---|---|---|
    | `spec.connection.password` | `jdbc-generic` | **Wins over `password_env`** when both are present — a manifest that looks safe because it names an env var is still overridden by the plain-text line above it. |
    | `spec.auth.token_value` | `rest-generic` | A literal bearer token. `token_env` is read first, so nothing is overridden — but the secret itself is in the file. |

    `lm validate` and `lm apply` name both — `WARN spec.connection.password`,
    `WARN spec.auth.token_value` — but a warning exits 0, so it gates nothing. Close them in CI,
    before the validator runs:

    ```bash
    ! grep -rnE '^[[:space:]]*(password|token_value):' 10-connectors/
    ```

```text
acme-si/
├── 10-connectors/      # applied first — an entity fuses sources that must exist
│   ├── ad-user.yaml
│   └── c-order.yaml
├── 20-entities/
│   └── customer.yaml
├── 30-apps/            # views, processes, workflows — they read entities
│   ├── clients-view.yaml
│   └── commande-workflow.yaml
└── secrets.list        # the NAMES the manifests reference, so an operator can check them
```

Order matters in one direction only: a manifest that *references* something applies after it.
Connectors, then entities, then the app ladder. Within a layer, order is free.

## The three gestures

```bash
lm validate -f 10-connectors/ad-user.yaml   # dry run, per-field errors, exit 1 on error
lm apply    -f 10-connectors/ad-user.yaml   # upsert by metadata.name
lm run list --connector ad_user -o json     # verify: the ledger, as data
```

`apply` is an **upsert**: replaying the whole directory converges a tenant. There is no separate
"create" and "update":

```
$ lm apply -f docs/apps/demo/sources/10-erp.yaml
{"name":"acme-desk-erp","connectorId":"6c8d0ce5-8686-493d-a05d-bdc950281707","endpointsApplied":1}
```

!!! warning "`lm validate` covers `kind: Connector` only"
    Any other kind fails on the envelope (`kind must be "Connector"`). The other kinds are
    checked **at apply**, which refuses rather than half-writes. A CI gate routes by kind:
    validate the connectors, apply the rest against a non-production tenant.

## The pipe, both ways

`lm` reads manifests from stdin and writes results as data:

- **In** — `-f -` reads the manifest from stdin, at every `-f` door (`apply`, `validate`,
  `entity apply`, `view apply`, `app apply`, `process apply`, `workflow apply`,
  `export apply`, `search apply`, `connector apply`).
- **Out** — `-o json` (or `yaml`) on every listing, plus the two discovery surfaces a
  generator reads: `lm source schema` and `lm source jdbc list-tables`. Notes such as
  *"result hit the limit"* go to **stderr**.
- `lm connector scope ls` has no `-o`: it prints one scope per line, ready to pipe.

That pair turns two hundred tables into a loop: `lm source schema` hands back the tables and
columns as data, a generator turns each into a manifest, `-f -` applies it:

```bash
lm source schema --scope atelier -o json \
  | jq -r '.tables[].name' \
  | while read -r table; do
      ./generate-entity.sh "$table" | lm entity apply -f -
    done
```

On the [demo desk](apps/demo-desk.md):

```
$ lm source schema --scope desk -o json | jq -c '.tables[] | {name, cols: [.columns[].name]}'
{"name":"connector.t_acme_desk_erp","cols":["code","name","city","segment","credit_limit"]}
{"name":"connector.t_acme_desk_crm","cols":["customer","company","town","email"]}
{"name":"connector.t_acme_desk_orders","cols":["order_no","code","status","amount","ordered_on"]}
{"name":"connector.v_acmecustomer","cols":["code","name","city","segment","credit_limit","email"]}
```

The generator is yours: it holds your knowledge of *that* ERP.

The two ends also meet directly. `lm view get` writes the saved view's manifest to stdout, so
promoting a view from one environment to another is one line with no file in between:

```bash
lm view get clients --context staging | lm view apply -f - --context prod
```

Do **not** do the same with `lm connector get | lm connector apply`: the flat fields carry the
connector's `id`, which belongs to the tenant it came from. Use the `kind: Connector` manifest in
your repository instead.

A manifest piped in has no file name: refusals name it `stdin` and point upstream.

```
$ cat bad-connector.yaml | lm validate -f -
✗ stdin — 2 error(s), 1 warning(s)

  ERROR    spec.transport
          required field missing

  ERROR    spec.after_process
          required field missing
          → archive
          → delete
          → move
          → none

  WARN     metadata.scopes
          no scopes declared — the connector's tables will not be askable in any métier
          → Add: scopes: [compta]  (the métier a user must hold as scope:compta)

Fix the errors above in what produced this manifest.
Error: validation failed: 2 error(s)
$ echo $?
1
```

## In CI

`lm validate` exits **1** when the manifest carries at least one error, **0** otherwise — so
it is a gate with no wrapper:

```yaml
# .github/workflows/manifests.yml
- name: Validate every connector manifest
  env:
    LUMNIK_URL: ${{ secrets.LUMNIK_STAGING_URL }}
    LUMNIK_TOKEN: ${{ secrets.LUMNIK_STAGING_TOKEN }}
  run: |
    for f in 10-connectors/*.yaml; do
      lm validate -f "$f"
    done
```

```yaml
# on merge — converge the tenant, then wait for the ledger to settle
- run: |
    for f in 10-connectors/*.yaml 20-entities/*.yaml 30-apps/*.yaml; do lm apply -f "$f"; done
    lm connector run ad_user
    for _ in $(seq 1 60); do
      status=$(lm run list --connector ad_user -o json | jq -r '.[0].status')
      case "$status" in
        Completed|Partial) echo "run ended: $status"; break ;;
        Failed|Cancelled)  echo "run ended: $status"; exit 1 ;;
      esac
      sleep 5
    done
```

**The wait is not optional.** `lm connector run` exits 0 as soon as the run is *scheduled*:

```
$ lm connector run acme-desk-erp
run scheduled: 2cff18e6-6774-44f5-bcad-e3640e776f51
```

Asserting on the ledger at once would read `Queued` or `Running`. Runs are listed newest first,
so `.[0]` is the one just scheduled.

Statuses are `Queued`, `Running`, `Completed`, `Partial`, `Failed`, `Cancelled`. Decide whether
`Partial` fails your pipeline: the run read less than its target, nothing broke.

A pre-flight worth one line — the secrets the manifests reference must exist on the target,
and `secret list` never returns a value:

```bash
lm secret list -o json | jq -r '.[].name' | sort > present.txt
comm -23 <(sort secrets.list) present.txt   # prints what is missing, empty means ready
```

!!! warning "Validation is server-side — CI needs a reachable hub"
    `lm validate` posts the manifest to the hub; there is no offline validator. A PR gate needs a
    URL and a token for a **non-production** tenant. Air-gapped CI can only lint the YAML.

## Exit codes

| Command | 0 | 1 |
|---|---|---|
| `lm validate` | no error (**warnings still exit 0**) | at least one error |
| `lm apply` and every `… apply` | the manifest was applied | rejected, unreachable, unauthorised |
| `lm connector run` | the run was **scheduled** (`run scheduled: <id>`, one line per endpoint) | it could not be scheduled |
| any command | — | any error, printed as `Error: …` on stderr |

`lm connector run` returning 0 means the run was accepted, not that it started. The verdict is
in the ledger: wait on `lm run list -o json`, as above.

## What a green pipeline does not prove

- **Secrets are not in git**, so a fresh tenant has none until an operator sets them.
- **Data is not in git.** Applying the universe creates the pipes; the rows arrive when the
  connectors run.
- **Scope roles live in the identity provider** (`scope:<tag>` realm roles), not in a
  manifest — see [Tenants, users & scopes](security/identity-admin.md).
- **The ERP is untouched by lumnik either way.** Ingestion is read-only: nothing in this
  repository makes lumnik write to the source system.
- **A replay switches the outputs back on.** A `kind: Process` is enabled when applied and fires
  at once: webhooks and mail leave the platform. Apply the app layer to a *fresh* tenant with
  that in mind, or run `lm process disable NAME` right after the apply.

Reversing all of it is its own page: [Reversibility & exit](reversibility.md).
