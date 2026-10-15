//go:build otk

package otk

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

// otkKey is the controlled signing key the %otkauth hub trusts. Nil under
// dev-mode runs (LUMNIK_TOKEN is then a plain string the dev-bypass hub ignores).
// Declared here (a non-test file) rather than in otk_test.go so the lmEnv reference
// also compiles under `go build -tags otk`; TestOTK populates it.
var otkKey *auth.ControlledKey

// otkSentinel is the high-entropy secret injected via OTK_SENTINEL_SECRET. If it
// ever appears in lm output, that is a CRITICAL secret leak (see checks.SecretEcho).
const otkSentinel = "OTK-SENTINEL-do-not-echo-7Yq3kP"

// scenarioState holds per-scenario data shared between steps.
type scenarioState struct {
	scen      generator.Scenario
	result    runner.Result
	beforeAPI map[string]bool       // connector names before the command (API view)
	afterAPI  map[string]bool       // connector names after the command (API view)
	beforeDB  checks.SchemaSnapshot // hub DB fingerprint before apply
	dbLive    bool                  // whether the DB snapshot is active (OTK_DB_DSN + psql)
	postRows  int                   // connector.connector rowcount after apply (for divergence)
	cleaned   bool                  // whether the cleanup step completed (gates the After-hook safeguard)
	findings  []oracle.Finding
	manifest  string // temp file path

	// run lane (v3)
	runScen      generator.RunScenario
	runDormant   bool            // run scenario skipped: requires dbLive (DSN + psql)
	sourcePath   string          // where the adversarial CSV was written
	createdName  string          // the connector apply created (name-agnostic)
	runID        string          // the scheduled run id
	runRec       types.Run       // the terminal run record
	beforeTables map[string]bool // dynamic tables before the run
	newTables    []string        // dynamic tables the run created (the diff)
	runResult    runner.Result   // output of `lm connector run`

	// entity lane
	entityScen        generator.EntityScenario
	entityApplyStatus int
	entityReadStatus  int
	entityReadBody    string

	// entity lane, cli sub-type (#647): lm entity resolve/ambiguous/assert-same/assert-distinct/
	// history end-to-end over two genuine ambiguities, driven via the lm binary (runner.Run), not
	// HTTP — distinct from steps_entity_resolution.go's REST-door adversarial battery (#643).
	entityCliDormant    bool
	entityCliTargetID   string // pair 1's fixture row — cleared by assert-same
	entityCliGhostID    string // pair 1: asserted same as the target
	entityCliTarget2ID  string // pair 2's fixture row — cleared by assert-distinct
	entityCliGhostDave  string // pair 2: asserted distinct from target2 (excluded)
	entityCliGhostCarol string // pair 2: target2's expected surviving candidate

	// process lane (kind:Process apply governance + inbox attention surface)
	processScen        generator.ProcessScenario
	processApplyStatus int
	inboxStatus        int
	inboxBody          string

	// view lane (kind:View render honesty + RLS)
	viewScen   generator.ViewScenario
	viewStatus int
	viewBody   string

	// entity-resolution lane (#643): ambiguity/assertion/swap-refusal over #622's REST doors
	resDormant bool
	res        resFixtureState

	// export lane (#684): managed kind:Export doors + the one-shot entity download, through lm
	exp exportState

	// view-store lane (#140 apply/list/get round-trip + uniform scope-403 pin)
	storeVariant      string
	storeCaseID       generator.CaseID // synthetic, non-catalogue identity; see storeGiven
	storeManifest     string
	storeApplyStatus  int
	storeApplyBody    string
	storeRenderStatus int
	storeRenderBody   string

	// auth lane: kept off s.scen because seedTenantOneBaseline (cross-tenant) drives
	// givenRunForSeed/whenApply as a side effect, which overwrite s.scen/s.runScen.
	authCaseID generator.CaseID
}

// caseID returns whichever lane this scenario belongs to has set its identity.
// Exactly one of these is non-empty for any scenario that reached a Given step;
// used by the After hook to record what was attempted, dormant or not.
func (st *scenarioState) caseID() generator.CaseID {
	switch {
	case st.authCaseID != "":
		return st.authCaseID
	case st.scen.CaseID != "":
		return st.scen.CaseID
	case st.runScen.CaseID != "":
		return st.runScen.CaseID
	case st.entityScen.CaseID != "":
		return st.entityScen.CaseID
	case st.processScen.CaseID != "":
		return st.processScen.CaseID
	case st.viewScen.CaseID != "":
		return st.viewScen.CaseID
	case st.exp.c.CaseID != "":
		return st.exp.c.CaseID
	case st.storeCaseID != "":
		return st.storeCaseID
	default:
		return ""
	}
}

func lmEnv() []string {
	token := envOr("LUMNIK_TOKEN", "dev-token")
	if otkKey != nil {
		// Real-auth: functional lanes drive lm with a valid integrator JWT. A mint
		// failure here would silently fall back to dev-token, which the %otkauth hub
		// rejects on every lane — a confusing mass-401 cascade. Fail loud instead.
		t, err := otkKey.Integrator(otkTenant, otkTenant)
		if err != nil {
			panic("otk: real-auth lane could not mint integrator token: " + err.Error())
		}
		token = t
	}
	return []string{
		"LUMNIK_URL=" + os.Getenv("LUMNIK_URL"),
		"LUMNIK_TOKEN=" + token,
		"OTK_SENTINEL_SECRET=" + otkSentinel,
	}
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// observeConnectors lists connectors via lm. A launch failure or non-zero exit
// means we could not observe hub state — a broken precondition (hard error).
func observeConnectors() (string, error) {
	r := runner.Run(lmEnv(), lmBinary(), "connector", "list", "-o", "json")
	if !r.Started {
		return "", fmt.Errorf("could not launch lm at %q: %s", lmBinary(), r.Stderr)
	}
	if r.Exit != 0 {
		return "", fmt.Errorf("could not observe hub state via 'lm connector list' (exit %d): %s", r.Exit, r.Stderr)
	}
	return r.Stdout, nil
}

// snapshotConnectors returns the current connector name set. A launch failure,
// non-zero exit, OR unparseable JSON is an observation failure (hard error).
func snapshotConnectors() (map[string]bool, error) {
	list, err := observeConnectors()
	if err != nil {
		return nil, err
	}
	names, perr := checks.ConnectorNames(list)
	if perr != nil {
		return nil, fmt.Errorf("could not parse hub connector list: %w", perr)
	}
	return names, nil
}

// needsSafeguardCleanup reports whether the After hook should attempt a best-effort
// teardown. It runs only when the normal cleanup step did NOT complete AND a
// pre-command baseline was captured. The nil-baseline guard is critical: a nil
// beforeAPI means the pre-apply snapshot never succeeded (apply never ran), so the
// safeguard must skip — otherwise it would treat every pre-existing connector as a
// leak and mass-delete the hub.
func needsSafeguardCleanup(cleaned bool, beforeAPI map[string]bool) bool {
	return !cleaned && beforeAPI != nil
}

// needsTableSafeguard mirrors needsSafeguardCleanup for dynamic tables: run only
// when cleanup did not complete AND a baseline was captured (nil = run never ran).
func needsTableSafeguard(cleaned bool, beforeTables map[string]bool) bool {
	return !cleaned && beforeTables != nil
}

func countNew(before, after map[string]bool) int {
	return len(newNames(before, after))
}

func newNames(before, after map[string]bool) []string {
	var out []string
	for name := range after {
		if !before[name] {
			out = append(out, name)
		}
	}
	sort.Strings(out)
	return out
}

// parseRunID extracts the run id from `lm connector run` output ("run scheduled: <id>").
func parseRunID(stdout string) (string, error) {
	for _, line := range strings.Split(stdout, "\n") {
		if rest, ok := strings.CutPrefix(strings.TrimSpace(line), "run scheduled:"); ok {
			if id := strings.TrimSpace(rest); id != "" {
				return id, nil
			}
		}
	}
	return "", fmt.Errorf("no runId in lm output: %q", stdout)
}

// parseRunGet parses `lm run get -o json` (a one-element array). ok reports whether
// the run is terminal (EndedAt set).
func parseRunGet(jsonOut string) (types.Run, bool, error) {
	var runs []types.Run
	if err := json.Unmarshal([]byte(jsonOut), &runs); err != nil {
		return types.Run{}, false, fmt.Errorf("could not parse 'lm run get' output: %w", err)
	}
	if len(runs) == 0 {
		return types.Run{}, false, fmt.Errorf("'lm run get' returned no run")
	}
	r := runs[0]
	return r, r.EndedAt != nil, nil
}

func lmBinary() string {
	// `make build` (lm/Makefile: `go build -o lm ./cmd/lm`) puts the binary at
	// lm/lm; these tests run from lm/test/otk, so it sits two directories up.
	const rel = "../../lm"
	abs, err := filepath.Abs(rel)
	if err != nil {
		return rel
	}
	return abs
}

// laneRegistrars is the Open/Closed seam: each lane file appends its registrar in
// init(); InitializeScenario iterates them and never changes when a lane is added.
var laneRegistrars []func(ctx *godog.ScenarioContext, st *scenarioState)

// stepPatterns aggregates each lane's patterns for the bind-guard. Lane files
// append their patterns in init().
var stepPatterns []string

// allFindings collects findings across all scenarios for the suite verdict.
var allFindings []oracle.Finding

// scenariosAttempted counts the scenarios godog STARTED, not the ones that passed.
// The calibration floor is about whether the battery actually ran: a faulted hub is
// EXPECTED to fail steps, and counting passes would make the floor unreachable by
// construction in the one mode that needs it.
var scenariosAttempted int

// attemptedCaseIDs records every scenario's case identity once the After hook sees
// it — dormant or not. buildSummary derives "distinct cases executed" as this set
// minus dormancyLog's set, both deduplicated by CaseID.
var attemptedCaseIDs []generator.CaseID

// dormancyEntry is one recorded reason a case did not actually exercise its lane.
type dormancyEntry struct {
	CaseID generator.CaseID
	Reason string
}

// dormancyLog collects every dormancy this battery recorded. A CaseID can appear
// more than once (a scenario can go dormant for more than one reason across its
// steps) — buildSummary keeps every reason, never collapsing the count.
var dormancyLog []dormancyEntry

// recordDormant is the single place a lane declares "this case's checks did not
// run, and here is why" — replacing the ad hoc fmt.Printf each lane used to own.
// Still prints (so a live run stays readable without waiting for the summary), and
// also feeds the run summary's dormancy accounting.
func recordDormant(caseID generator.CaseID, reason string) {
	fmt.Printf("  OTK %s lane DORMANT: %s\n", caseID.Lane(), reason)
	dormancyLog = append(dormancyLog, dormancyEntry{CaseID: caseID, Reason: reason})
}

func InitializeScenario(ctx *godog.ScenarioContext) {
	st := &scenarioState{}
	for _, reg := range laneRegistrars {
		reg(ctx, st)
	}
	// Reset per-scenario state up front so the safeguard-gating fields (cleaned,
	// beforeAPI/beforeTables baselines) can never leak across scenarios. godog
	// v0.14.1 re-runs this initializer per scenario, so st is already fresh today —
	// this makes the per-scenario-clean invariant explicit and robust to any change
	// in how/when the initializer is invoked.
	ctx.Before(func(ctx context.Context, sc *godog.Scenario) (context.Context, error) {
		scenariosAttempted++
		*st = scenarioState{}
		return ctx, nil
	})
	ctx.After(func(ctx context.Context, sc *godog.Scenario, err error) (context.Context, error) {
		for _, f := range st.findings {
			// The single choke point every scenario's findings pass through: a Finding
			// missing its CaseID is a harness defect (a direct literal that forgot to
			// set it), and must be as loud as the false green it would otherwise cause.
			if verr := f.Validate(); verr != nil {
				panic("otk: " + verr.Error())
			}
			fmt.Printf("  OTK finding: %s\n", f)
		}
		allFindings = append(allFindings, st.findings...)
		if id := st.caseID(); id != "" {
			attemptedCaseIDs = append(attemptedCaseIDs, id)
		}
		if needsSafeguardCleanup(st.cleaned, st.beforeAPI) {
			st.safeguardCleanup()
		}
		if needsTableSafeguard(st.cleaned, st.beforeTables) {
			st.safeguardCleanupTables()
		}
		if st.sourcePath != "" {
			_ = os.Remove(st.sourcePath)
		}
		if st.manifest != "" {
			_ = os.Remove(st.manifest)
		}
		return ctx, nil
	})
}
