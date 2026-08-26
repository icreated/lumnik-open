# OTK — Generative Adversarial Acceptance for `lm`

OTK ("Отдел Технического Контроля", Soviet technical acceptance control) is a pure-Go adversarial test harness for the `lm` CLI. A seeded generator produces adversarial Gherkin scenarios; godog replays them by driving `lm` (and, for the auth lane, raw HTTP) against a live hub; an invariant oracle grades the outcomes.

**Mantra:** `OTK : lm scenarios :: Sonar : code smells` — a demoable trust artifact: *"0 CRITICAL over 81 generated scenarios."*

---

## Quick start

```bash
cd lm/

# 1. Hub-free unit tests only (no hub needed)
make otk-unit

# 2. Full Bronze battery against a running hub
export LUMNIK_URL=http://localhost:8080
make otk
```

`make otk` builds the binary, runs the hub-free units, then fires the declared-tier battery (all 81 corpus scenarios).

---

## Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `LUMNIK_URL` | **Yes** (battery) | — | Hub base URL. Without it the godog suite is skipped. |
| `LUMNIK_TOKEN` | No | `dev-token` | Bearer token for the functional lanes when the hub is in **dev-mode**. Ignored under real-auth (the harness mints its own JWT). |
| `OTK_DB_DSN` | No | — | PostgreSQL DSN for the non-owner app role. Enables the schema-diff, API/DB-divergence, and run-conservation checks. Without it those families are **DORMANT** (logged, not failed). |
| `OTK_DB_ADMIN_DSN` | No | `OTK_DB_DSN` | DSN for the BYPASSRLS admin role (teardown of dynamic tables). |
| `OTK_OIDC_ISSUER` | For the **auth lane** | — | When set, the harness runs in **real-auth** mode: it mints a valid integrator JWT for the functional lanes and adversarial tokens for the auth lane. The hub must boot on the `%otkauth` profile trusting the matching public key. Without it the auth lane is **DORMANT**. |
| `OTK_OIDC_AUDIENCE` | No | `lumnik-backend` | The `aud` the minted tokens carry (must match `%otkauth`). |
| `OTK_KEY_FILE` | With `OTK_OIDC_ISSUER` | — | Path to the **shared** private-key PEM (written by `cmd/otkkey`) so the harness signs with the exact key the hub verifies. Absent → an ephemeral key is generated **and a warning is logged** (it will not match an external hub). |

> **Important:** the hub must run under the **non-owner app DB role** (the same role that `RlsEnforcedProfile` enforces in ITs). Owner-mode DevServices defeats the RLS checks.

`psql` must be on `PATH` when `OTK_DB_DSN` is set.

---

## The corpus

```
test/otk/corpus/
  smoke_validate.feature   # hand-written smoke: non-mutating validate lane
  seed_0001.feature … seed_0020.feature   # apply lane (adversarial manifest)
  run_0001.feature  … run_0020.feature    # run lane  (adversarial source rows)
  rest_0001.feature … rest_0020.feature   # rest lane (adversarial REST manifest)
  auth_0001.feature … auth_0020.feature   # auth lane (adversarial JWT)
```

81 scenarios in total. The corpus is **locked** — seeds are deterministic, so a break in CI is always replayable.

### Surface lanes

| Lane | Adversary | Needs | Key oracle checks |
|---|---|---|---|
| `validate` (smoke) | manifest fields | — | Robustness, text glitches, no connector created |
| `apply` (seed_*) | manifest fields | `OTK_DB_DSN` for schema-diff + divergence | `AtomicityApply`, `Divergence` (RLS), `SchemaDiff` |
| `rest` (rest_*) | REST manifest fields | as `apply` | reuses the apply probes |
| `run` (run_*) | source CSV rows | `OTK_DB_DSN` (DORMANT otherwise) | `ReconcileCounts` (Law 1, Law 1b), `ReconcileLanded` (Law 2) |
| `auth` (auth_*) | adversarial JWT | `OTK_OIDC_ISSUER` + `%otkauth` hub (DORMANT otherwise) | `AuthOutcome`, `CrossTenantLeak` |

The **auth lane** probes the platform API over raw HTTP (it needs the exact status code — 401 vs 403 vs 200 — which `lm` collapses into a generic exit). Each seed forges one defect (wrong-role → expect 403; expired / bad-audience / bad-issuer / tampered-sig / garbage → expect 401; cross-tenant → expect an empty result). An adversarial token that **lands** (200 with data, or foreign rows) is CRITICAL; a clean rejection or correctly-empty result is a PASS.

---

## Token sourcing (the `TokenSource` seam)

`test/otk/auth` defines one interface with two implementations (Open/Closed):

- **`ControlledKey`** — self-mints an RSA-signed integrator token and forges the adversarial variants, deterministically. Drives the in-suite battery; the hub trusts its public key via `%otkauth`.
- **`Keycloak`** — obtains a real token via the OAuth2 direct-grant. Drives **one** on-demand end-to-end smoke (below); never forges invalid tokens.

`cmd/otkkey` emits a shared keypair so the hub (public key, in its env) and the harness (private key, `OTK_KEY_FILE`) agree across processes.

---

## Severity & certification tiers

### SIEM severities

| Level | Meaning | Example |
|---|---|---|
| **CRITICAL** | Security breach | Secret echoed, SQLi landed, RLS crossed, **auth bypass landed** |
| **HIGH** | Corrupt/lost state | `in ≠ out + skipped`, `dlq > skipped`, landed rows ≠ reported, partial write |
| **MEDIUM** | Untyped error / leaked stack | Panic output, forged-token 500, unhandled exception |
| **LOW** | Cosmetic glitch | Encoding artefact in user-visible text |

Severity attaches to the **consequence**, not the attack — a hack cleanly rejected is a PASS.

### Certification tiers (ratcheting gate)

| Tier | Gate | Edition bar |
|---|---|---|
| 🥉 **Bronze** | 0 CRITICAL | Community minimum |
| 🥈 **Silver** | 0 CRITICAL + 0 HIGH | Production bar |
| 🥇 **Gold** | 0 of any severity, N ≥ 500 | Grand-compte badge (deferred) |

The declared tier lives in `lm/test/otk/tier` (`OTK_TIER` overrides it). The current tier is **🥈 Silver** (0 CRITICAL + 0 HIGH), live-proven over the 81-scenario real-auth battery.

> **Reaching Silver:** the run lane originally surfaced a HIGH — a read-time *malformed* source row was dead-lettered and counted in `recordsSkipped` but not in the hub's `recordsIn`, so conservation (`recordsIn == recordsOut + recordsSkipped`) read `recordsIn=0, recordsOut=0, recordsSkipped=1`. Fixed in the hub (`ChunkExecutor` now counts read-time-malformed rows as input); the battery is green at Silver. Remaining findings are 3 LOW text-glitches (bidi/NUL reflected), which don't gate Silver. **Gold** (0 of any severity over N≥500) stays deferred.

---

## Running the real-auth battery

The battery hub boots on the `%otkauth` profile (dev-bypass off, keyless OIDC trusting the OTK public key). `quarkus:dev` is locked to the `dev` profile, so use the **packaged jar**:

```bash
cd lm/
mkdir -p /tmp/otk
export OTK_KEY_FILE=/tmp/otk/otkkey.pem
export OTK_OIDC_ISSUER="https://otk.local/issuer"
export OTK_OIDC_AUDIENCE="lumnik-backend"
PUB=$(go run -tags otk ./test/otk/cmd/otkkey)   # writes the private key to OTK_KEY_FILE

# boot the hub trusting that public key (needs a postgres initialised from infra/postgres/init)
cd ..
QUARKUS_PROFILE=otkauth \
  OTK_OIDC_PUBLIC_KEY="$PUB" OTK_OIDC_ISSUER="$OTK_OIDC_ISSUER" OTK_OIDC_AUDIENCE="$OTK_OIDC_AUDIENCE" \
  DB_URL=jdbc:postgresql://localhost:5433/lumnik \
  LUMNIK_SECRET_MASTER_KEY="$(openssl rand -base64 32)" \
  LUMNIK_CRYPTO_SECRET_KEY="$(openssl rand -base64 32)" \
  QUARKUS_OTEL_SDK_DISABLED=true \
  java -jar lumnik-hub/target/quarkus-app/quarkus-run.jar &

# run the battery (auth lane active because OTK_OIDC_ISSUER + OTK_KEY_FILE are set)
cd lm/
export LUMNIK_URL=http://localhost:8080
export OTK_DB_DSN="postgresql://lumnik_app:lumnik_app_dev_password@localhost:5433/lumnik"
export OTK_DB_ADMIN_DSN="postgresql://lumnik_admin:lumnik_admin_dev_password@localhost:5433/lumnik"
make otk
```

> `%otkauth` (dev-bypass off) hard-fails without `LUMNIK_SECRET_MASTER_KEY` + `LUMNIK_CRYPTO_SECRET_KEY`. The `otk-battery` GitHub workflow does all of the above automatically. The gate enforces **Silver** (`lm/test/otk/tier`); `OTK_TIER` overrides locally.

---

## On-demand real-Keycloak E2E smoke

A single end-to-end scenario against a live **selfhost** stack (real Keycloak), gated behind a build tag so it never runs in the fast CI:

```bash
OTK_E2E_TOKEN_URL=http://localhost:8180/realms/lumnik/protocol/openid-connect/token \
OTK_E2E_CLIENT=lm-cli OTK_E2E_USER=integrator OTK_E2E_PASS=... \
LUMNIK_URL=http://localhost:8080 \
go test -tags otk_e2e -run TestOTKAuthE2E ./test/otk/ -v
```

Proves the real token path end-to-end (real login → 200; no header → 401). The adversarial matrix lives in the deterministic battery.

---

## Replaying a specific seed

Every finding prints `seed=N`. To replay seed 7 in isolation:

```bash
go test -tags otk ./test/otk/ -run TestOTK -v -- \
  -godog.filter="seed=7"
```

Or run the generator directly to inspect the scenario:

```go
import "github.com/icreated/lumnik-open/lm/test/otk/generator"

sc := generator.Generate(7)      // apply lane
rs := generator.GenerateRun(7)   // run lane
au := generator.GenerateAuth(7)  // auth lane (au.DefectName)
```

---

## Regenerating the corpus

The corpus is locked to seeds 1–20 per lane. To regenerate (e.g. after changing the generator):

```bash
cd lm/
OTK_REGEN=1 go test -run TestRegenerateCorpus ./test/otk/generator/      # seed_*, run_*, auth_*
OTK_REGEN=1 go test -run TestRegenerateRestCorpus ./test/otk/generator/  # rest_*
```

Commit the diff — the golden lock (`TestCorpusMatchesGenerator`) and the bind-guard (`TestEveryCorpusStepIsBound`) will catch any drift or step that lost its registration.

---

## Adding a new lane or invariant check

**A new check:** add a pure function to `test/otk/checks/` returning `[]oracle.Finding` (TDD: failing unit test → RED → implement → GREEN), then wire it into the relevant lane's step handler.

**A new lane:** create `steps_<lane>.go` that registers itself in `init()` by appending its registrar to `laneRegistrars` and its patterns to `stepPatterns` — `InitializeScenario` is never edited (Open/Closed). Add the generator + corpus files; the bind-guard fails until every corpus step matches a registered pattern.

---

## Package layout

```
test/otk/
  oracle/         — Finding, Severity, Family (incl. Auth), Tier, Verdict (pure types)
  checks/         — pure invariant functions (Robustness, Atomicity, Security, Text, Reconcile, Auth…)
  generator/      — deterministic adversarial scenario generator (splitmix64)
  auth/           — TokenSource seam: ControlledKey (self-mint) + Keycloak (real, E2E)
  runner/         — thin exec wrapper producing runner.Result
  cmd/otkkey/     — emits the shared hub/harness keypair
  corpus/         — locked .feature files (the CI gate)
  steps_core.go   — scenarioState, env, the laneRegistrars/stepPatterns seam, InitializeScenario
  steps_apply.go / steps_run.go / steps_rest.go / steps_auth.go — self-registering lanes
  hubsnap.go      — psql DB-truth seam (schema snapshot, table rowcount, drop)
  otk_test.go     — TestOTK entry point (tier from lm/test/otk/tier or OTK_TIER env)
  otk_e2e_test.go — on-demand real-Keycloak smoke (build tag otk_e2e)
  binding_test.go — bind-guard: every corpus step must match a registered pattern
```
