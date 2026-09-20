# Integration as code

An integrator who defrosts one ERP writes its universe once: a directory of YAML manifests,
one per connector, entity, view, app, process and workflow. That directory *is* the installation.
It goes in git, it is reviewed in a pull request, and it is replayed against another tenant
by a loop.

This page is the operating manual for that. Almost nothing here is a new feature: `lm` is a
kubectl-shaped CLI, and four of its habits are what make the loop possible — a manifest
arrives through `-f` (and `-f -` reads it from stdin), listings print `-o json|yaml`, notes
and warnings go to **stderr** so stdout stays parseable, and the exit code carries a verdict.
The point is to put those four together.

## What belongs in the repository

| In git | Never in git | Rebuilt, not stored |
|---|---|---|
| `kind: Connector`, `Entity`, `View`, `App`, `Process`, `Workflow` manifests | secret **values** — manifests carry the *name* (`password_env`, `token_env`, `key_env`), never the credential | the dynamic tables under schema `connector` (ingestion writes them) |
| hook scripts (`META-INF/hooks.yaml`, deployed with the extension — not applied through `lm`) and mapping overrides (`lm mapping apply`) | the OIDC token in `~/.lm/config.yaml` | the RAG corpus (re-indexed from what was ingested) |
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

    `lm validate` and `lm apply` name both now — `WARN spec.connection.password`,
    `WARN spec.auth.token_value` — but a warning exits 0 by design, so it informs a human and
    gates nothing. Close them in CI, before the validator ever runs:

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
lm apply    -f 10-connectors/ad-user.yaml   # upsert — re-applying an unchanged manifest is a no-op
lm run list --connector ad_user -o json     # verify: the ledger, as data
```

`apply` is an **upsert**, so replaying the whole directory is the normal way to converge a
tenant — there is no separate "create" and "update".

!!! warning "`lm validate` covers `kind: Connector` only"
    The validator behind it is the connector validator: hand it an `Entity`, `View`, `App`,
    `Process` or `Workflow` manifest and it fails on the envelope. Those five kinds are
    checked **at apply**, which refuses rather than half-writes. A CI gate must therefore
    route by kind — validate the connectors, apply the rest against a non-production tenant.

## The pipe, both ways

`lm` reads manifests from stdin and writes results as data:

- **In** — `-f -` reads the manifest from stdin, at every `-f` door (`apply`, `validate`,
  `entity apply`, `view apply`, `app apply`, `process apply`, `workflow apply`,
  `connector apply`, `mapping apply`).
- **Out** — `-o json` (or `yaml`) on every listing, plus the two discovery surfaces a
  generator reads: `lm source schema` and `lm source jdbc list-tables`. Notes such as
  *"result hit the limit"* go to **stderr**, so stdout stays parseable. (The one listing
  without `-o` is `lm connector scope ls`, which prints one scope per line — it pipes as it
  is.)

That pair is what turns two hundred tables into a loop. `lm source schema` hands back the
discovered tables and columns as data; a generator turns each into a manifest; `-f -` applies
it without a temporary file:

```bash
lm source schema --scope atelier -o json \
  | jq -r '.tables[].name' \
  | while read -r table; do
      ./generate-entity.sh "$table" | lm entity apply -f -
    done
```

```
$ lm source schema --scope atelier -o json | jq -c '.tables[] | {name, cols: [.columns[].name]}'
{"name":"connector.t_c_order","cols":["documentno","custcol_47"]}
{"name":"connector.t_c_invoice","cols":["grandtotal"]}
```

The generator is yours — it is the place where your knowledge of *that* ERP lives, and it is
the artefact that makes the second client cheaper than the first.

The two ends also meet directly. `lm view get` writes the saved view's manifest to stdout, so
promoting a view from one environment to another is one line with no file in between:

```bash
lm view get clients --context staging | lm view apply -f - --context prod
```

That works because a `kind: View` manifest is self-contained. Do **not** generalise it to
`lm connector get | lm connector apply`: that pair carries the stored flat fields, `id`
included, and the id belongs to the tenant it came from — use the `kind: Connector` manifest
in your repository instead.

!!! note "A manifest piped in has no file name"
    Refusals name it `stdin` and point upstream (*"Fix the errors above in what produced this
    manifest"*) rather than telling you to re-run a pipe that would now wait on your keyboard.

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

**The wait is not optional.** `lm connector run` returns as soon as the run is *scheduled* —
it prints `run scheduled: <id>` and exits 0. Asserting on the ledger immediately would read
`Created` or `Running` and gate on nothing. Runs are listed newest first, so `.[0]` is the
one just scheduled.

Statuses are `Created`, `Running`, `Completed`, `Partial`, `Failed`, `Cancelled`. Decide
deliberately whether `Partial` fails your pipeline: it means the run ended inside a declared
bound, not that something broke.

A pre-flight worth one line — the secrets the manifests reference must exist on the target,
and `secret list` never returns a value:

```bash
lm secret list -o json | jq -r '.[].name' | sort > present.txt
comm -23 <(sort secrets.list) present.txt   # prints what is missing, empty means ready
```

!!! warning "Validation is server-side — CI needs a reachable hub"
    There is no offline validator: `lm validate` posts the manifest to the hub, which is what
    makes its answer the real one rather than a second implementation that drifts. The
    consequence is operational — a PR gate needs a URL and a token for a **non-production**
    tenant. Air-gapped CI cannot validate manifests, only lint the YAML.

## Exit codes

| Command | 0 | 1 |
|---|---|---|
| `lm validate` | no error (**warnings still exit 0**) | at least one error |
| `lm apply` and every `… apply` | the manifest was applied | rejected, unreachable, unauthorised |
| `lm connector run` | the run was **scheduled** (`run scheduled: <id>`) | it could not be scheduled |
| any command | — | any error, printed as `Error: …` on stderr |

`lm connector run` returning 0 means the run was accepted, not that it succeeded — and not
even that it started. The verdict is in the ledger: wait on `lm run list -o json`, as above.

## What a green pipeline does not prove

Stated plainly, because a reproducible install is not a restored one:

- **Secrets are not in git**, so a fresh tenant has none until an operator sets them.
- **Data is not in git.** Applying the universe creates the pipes; the rows arrive when the
  connectors run.
- **Scope roles live in the identity provider** (`scope:<tag>` realm roles), not in a
  manifest — see [Tenants, users & scopes](security/identity-admin.md).
- **The ERP is untouched by lumnik either way.** Ingestion is read-only: nothing in this
  repository makes lumnik write to the source system.
- **But a replay switches the roads out back on.** `core.process_manifest.enabled` defaults
  to `true`, so a `kind: Process` fires from the moment it is applied — webhooks and mail
  leave the platform, and what your automation does with them is outside lumnik. Apply the
  app layer to a *fresh* tenant with that in mind, or `lm process disable` first.

Reversing all of it is its own page: [Reversibility & exit](reversibility.md).
