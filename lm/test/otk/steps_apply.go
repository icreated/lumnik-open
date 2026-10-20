//go:build otk

package otk

import (
	"fmt"
	"os"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

const (
	stepManifest       = `^the generated manifest for seed (\d+)$`
	stepApply          = `^I run "lm apply" on it$`
	stepValidate       = `^I run "lm validate" on it$`
	stepRobustness     = `^lm is probed for robustness$`
	stepText           = `^lm output is probed for text glitches$`
	stepLeaks          = `^lm output is probed for leaks$`
	stepApplyOutcome   = `^the hub state matches the apply outcome$`
	stepCleanup        = `^the connector is cleaned up$`
	stepSchemaIntact   = `^the hub schema is intact$`
	stepNoNewConnector = `^the hub gains no connector$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepManifest, stepApply, stepValidate, stepRobustness, stepText, stepLeaks,
		stepApplyOutcome, stepCleanup, stepSchemaIntact, stepNoNewConnector)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepManifest, func(seed int) error { return st.givenManifestForSeed(int64(seed)) })
		ctx.Step(stepApply, st.whenApply)
		ctx.Step(stepValidate, st.whenValidate)
		ctx.Step(stepRobustness, st.probeRobustness)
		ctx.Step(stepText, st.probeText)
		ctx.Step(stepLeaks, st.probeLeaks)
		ctx.Step(stepApplyOutcome, st.probeApplyOutcome)
		ctx.Step(stepCleanup, st.cleanup)
		ctx.Step(stepSchemaIntact, st.probeSchemaIntact)
		ctx.Step(stepNoNewConnector, st.probeNoNewConnector)
	})
}

func (s *scenarioState) givenManifestForSeed(seed int64) error {
	s.scen = generator.Generate(seed)
	f, err := os.CreateTemp("", fmt.Sprintf("otk-%d-*.yaml", seed))
	if err != nil {
		return err
	}
	s.manifest = f.Name()
	if _, werr := f.WriteString(s.scen.ManifestYAML); werr != nil {
		_ = f.Close()
		return werr
	}
	return f.Close()
}

// whenApply snapshots the hub (API + optional DB), runs lm apply, then snapshots
// the post-apply state for the atomicity and divergence probes. Observation
// failures are hard preconditions; apply's own non-zero exit is a graded outcome.
func (s *scenarioState) whenApply() error {
	before, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.beforeAPI = before

	db, live, err := hubSnapshot(os.Getenv("OTK_DB_DSN"))
	if err != nil {
		return err
	}
	s.beforeDB, s.dbLive = db, live

	s.result = runner.Run(lmEnv(), lmBinary(), "apply", "-f", s.manifest)
	if !s.result.Started {
		return fmt.Errorf("could not launch lm at %q: %s", lmBinary(), s.result.Stderr)
	}

	after, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.afterAPI = after

	if s.dbLive {
		post, live, perr := hubSnapshot(os.Getenv("OTK_DB_DSN"))
		if perr != nil {
			return perr
		}
		if !live {
			return fmt.Errorf("hub DB snapshot became unavailable mid-scenario")
		}
		s.postRows = post.ConnectorRows
	}
	return nil
}

// whenValidate backs the validate smoke scenario: snapshot, run validate, snapshot.
func (s *scenarioState) whenValidate() error {
	before, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.beforeAPI = before
	s.result = runner.Run(lmEnv(), lmBinary(), "validate", "-f", s.manifest)
	if !s.result.Started {
		return fmt.Errorf("could not launch lm at %q: %s", lmBinary(), s.result.Stderr)
	}
	after, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.afterAPI = after
	return nil
}

func (s *scenarioState) probeRobustness() error {
	s.findings = append(s.findings, checks.Robustness(s.result, s.scen.CaseID, s.scen.Seed)...)
	return nil
}

func (s *scenarioState) probeText() error {
	s.findings = append(s.findings,
		checks.TextGlitch(s.result.Stdout, s.result.Stderr, s.scen.CaseID, s.scen.Seed)...)
	return nil
}

func (s *scenarioState) probeLeaks() error {
	out := s.result.Stdout + s.result.Stderr
	s.findings = append(s.findings, checks.SecretEcho(out, otkSentinel, s.scen.CaseID, s.scen.Seed)...)
	s.findings = append(s.findings, checks.FileDisclosure(out, s.scen.CaseID, s.scen.Seed)...)
	return nil
}

// probeApplyOutcome runs the two-sided atomicity check (always, via the API) and
// the API/DB divergence check (only when the DB snapshot is live).
func (s *scenarioState) probeApplyOutcome() error {
	s.findings = append(s.findings,
		checks.AtomicityApply(s.beforeAPI, s.afterAPI, s.result.Exit, s.scen.CaseID, s.scen.Seed)...)
	if s.dbLive {
		apiDelta := countNew(s.beforeAPI, s.afterAPI)
		dbDelta := s.postRows - s.beforeDB.ConnectorRows
		s.findings = append(s.findings, checks.Divergence(apiDelta, dbDelta, s.scen.CaseID, s.scen.Seed)...)
	}
	return nil
}

// probeNoNewConnector backs the validate smoke "the hub gains no connector" step,
// using the v1 set-differential (any new connector from a non-mutating call -> HIGH).
func (s *scenarioState) probeNoNewConnector() error {
	s.findings = append(s.findings,
		checks.AtomicitySetDiff(s.beforeAPI, s.afterAPI, s.scen.CaseID, s.scen.Seed)...)
	return nil
}

// cleanup deletes the connectors apply created (the actual diff set, immune to name
// sanitisation), then verifies they are gone. A delete that fails to remove is a
// hard precondition error — the next scenario's baseline must be trustworthy.
func (s *scenarioState) cleanup() error {
	created := newNames(s.beforeAPI, s.afterAPI)
	if len(created) == 0 {
		s.cleaned = true
		return nil
	}
	for _, name := range created {
		r := runner.Run(lmEnv(), lmBinary(), "connector", "delete", "--force", name)
		if !r.Started {
			return fmt.Errorf("could not launch lm to delete %q: %s", name, r.Stderr)
		}
		if r.Exit != 0 {
			return fmt.Errorf("lm connector delete %q exited %d: %s", name, r.Exit, r.Stderr)
		}
	}
	after, err := snapshotConnectors()
	if err != nil {
		return err
	}
	for _, name := range created {
		if after[name] {
			return fmt.Errorf("teardown failed: connector %q still present after delete", name)
		}
	}
	s.cleaned = true
	return nil
}

// safeguardCleanup is the After-hook net for scenarios that errored after apply
// created a connector but before the cleanup step ran. It re-lists the current
// connectors and deletes anything that appeared since the baseline, so a leak does
// not pollute later scenarios. Best-effort: the scenario has already failed (the
// erroring step turned the suite red), so this only protects the next scenario;
// failures are logged, never raised.
func (s *scenarioState) safeguardCleanup() {
	current, err := snapshotConnectors()
	if err != nil {
		fmt.Printf("  OTK safeguard cleanup: cannot list connectors: %v\n", err)
		return
	}
	for _, name := range newNames(s.beforeAPI, current) {
		r := runner.Run(lmEnv(), lmBinary(), "connector", "delete", "--force", name)
		if !r.Started || r.Exit != 0 {
			fmt.Printf("  OTK safeguard cleanup: failed to delete leaked connector %q (started=%v exit=%d): %s\n",
				name, r.Started, r.Exit, r.Stderr)
			continue
		}
		fmt.Printf("  OTK safeguard cleanup: removed leaked connector %q\n", name)
	}
}

// probeSchemaIntact compares the pre-apply DB snapshot against a post-teardown
// snapshot: any table-set DDL or residual connector row is CRITICAL. Dormant when
// the DB snapshot is inactive.
func (s *scenarioState) probeSchemaIntact() error {
	if !s.dbLive {
		return nil
	}
	after, live, err := hubSnapshot(os.Getenv("OTK_DB_DSN"))
	if err != nil {
		return err
	}
	if !live {
		return fmt.Errorf("hub DB snapshot became unavailable before intactness check")
	}
	s.findings = append(s.findings, checks.SchemaDiff(s.beforeDB, after, s.scen.CaseID, s.scen.Seed)...)
	return nil
}
