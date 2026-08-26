//go:build otk

package otk

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

const (
	stepRunManifest   = `^the generated manifest and adversarial source for seed (\d+)$`
	stepApplyAndRun   = `^I apply and run the connector$`
	stepRunTerminal   = `^the run reaches a terminal status$`
	stepRowsAccounted = `^every source row is accounted for$`
	stepLandedMatches = `^the landed rows match the run's reported output$`
	stepRunProbe      = `^lm output is probed for robustness, text glitches, and leaks$`
	stepRunCleanup    = `^the ingested data is cleaned up$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepRunManifest, stepApplyAndRun, stepRunTerminal, stepRowsAccounted,
		stepLandedMatches, stepRunProbe, stepRunCleanup)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepRunManifest, func(seed int) error { return st.givenRunForSeed(int64(seed)) })
		ctx.Step(stepApplyAndRun, st.whenApplyAndRun)
		ctx.Step(stepRunTerminal, st.thenRunTerminal)
		ctx.Step(stepRowsAccounted, st.thenRowsAccounted)
		ctx.Step(stepLandedMatches, st.thenLandedMatches)
		ctx.Step(stepRunProbe, st.probeRunOutput)
		ctx.Step(stepRunCleanup, st.cleanupRun)
	})
}

// runPollTimeout / runPollInterval bound the wait for a run to reach terminal state.
var (
	runPollTimeout  = 90 * time.Second
	runPollInterval = 1 * time.Second
)

// givenRunForSeed generates the run scenario, writes the manifest to a temp file,
// and writes the adversarial source to the per-seed path the manifest points at.
func (s *scenarioState) givenRunForSeed(seed int64) error {
	s.runScen = generator.GenerateRun(seed)
	s.scen = generator.Scenario{Seed: seed} // carry the seed for findings
	f, err := os.CreateTemp("", fmt.Sprintf("otk-run-%d-*.yaml", seed))
	if err != nil {
		return err
	}
	s.manifest = f.Name()
	if _, werr := f.WriteString(s.runScen.ManifestYAML); werr != nil {
		_ = f.Close()
		return werr
	}
	if err := f.Close(); err != nil {
		return err
	}
	s.sourcePath = s.runScen.SourcePath
	if err := os.MkdirAll(filepath.Dir(s.sourcePath), 0o755); err != nil {
		return err
	}
	return os.WriteFile(s.sourcePath, []byte(s.runScen.SourceCSV), 0o644)
}

// applyMustSucceed checks that apply both started and exited cleanly. Where a
// benign baseline apply is a precondition (the run lane, and the auth lane's
// cross-tenant baseline), a non-zero exit is a broken precondition rather than a
// graded finding.
func applyMustSucceed(r runner.Result, binary string) error {
	if !r.Started {
		return fmt.Errorf("could not launch lm at %q: %s", binary, r.Stderr)
	}
	if r.Exit != 0 {
		return fmt.Errorf("apply precondition: 'lm apply' exited %d: %s", r.Exit, r.Stderr)
	}
	return nil
}

// whenApplyAndRun is the run-lane front door. The run lane requires dbLive; when it
// is dormant the scenario is skipped (logged loud), distinct from a failure.
func (s *scenarioState) whenApplyAndRun() error {
	db, live, err := hubSnapshot(os.Getenv("OTK_DB_DSN"))
	if err != nil {
		return err
	}
	if !live {
		s.runDormant = true
		fmt.Printf("  OTK run scenario DORMANT (seed=%d): set OTK_DB_DSN + psql to exercise the ingestion data path\n", s.scen.Seed)
		return nil
	}
	s.beforeDB, s.dbLive = db, true

	before, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.beforeAPI = before
	tablesBefore, err := dataTableSet()
	if err != nil {
		return err
	}
	s.beforeTables = tablesBefore

	s.result = runner.Run(lmEnv(), lmBinary(), "apply", "-f", s.manifest)
	if err := applyMustSucceed(s.result, lmBinary()); err != nil {
		return err
	}
	after, err := snapshotConnectors()
	if err != nil {
		return err
	}
	s.afterAPI = after

	created := newNames(s.beforeAPI, s.afterAPI)
	if len(created) != 1 {
		return fmt.Errorf("run-lane precondition: benign manifest did not create exactly one connector "+
			"(created=%v, apply exit=%d): %s", created, s.result.Exit, s.result.Stderr)
	}
	s.createdName = created[0]

	s.runResult = runner.Run(lmEnv(), lmBinary(), "connector", "run", s.createdName)
	if !s.runResult.Started {
		return fmt.Errorf("could not launch lm to run %q: %s", s.createdName, s.runResult.Stderr)
	}
	if s.runResult.Exit != 0 {
		return fmt.Errorf("run-lane precondition: 'lm connector run %q' exited %d: %s",
			s.createdName, s.runResult.Exit, s.runResult.Stderr)
	}
	id, err := parseRunID(s.runResult.Stdout)
	if err != nil {
		return err
	}
	s.runID = id
	return nil
}

// thenRunTerminal polls until the run ends or the timeout elapses. A run that never
// ends is a broken precondition (hard error), never a graded finding.
func (s *scenarioState) thenRunTerminal() error {
	if s.runDormant {
		return nil
	}
	deadline := time.Now().Add(runPollTimeout)
	var diary pollDiary
	for {
		r := runner.Run(lmEnv(), lmBinary(), "run", "get", s.runID, "-o", "json")
		if !r.Started {
			return fmt.Errorf("could not launch lm to poll run %q: %s", s.runID, r.Stderr)
		}
		if r.Exit == 0 {
			run, terminal, perr := parseRunGet(r.Stdout)
			if perr != nil {
				return perr
			}
			diary.observed(run)
			if terminal {
				s.runRec = run
				return nil
			}
		} else {
			diary.refused(r.Exit, r.Stderr)
		}
		if time.Now().After(deadline) {
			// The diary, not just the timeout: four otk-gold nights died here saying only THAT a
			// run never finished, and the hub log later showed it had. Whichever side is wrong,
			// the next occurrence names it.
			return fmt.Errorf("run %q did not reach a terminal status within %s — %s",
				s.runID, runPollTimeout, diary)
		}
		time.Sleep(runPollInterval)
	}
}

// thenRowsAccounted is Law 1: every source row written or dead-lettered.
// The dead-letter count is read from runRec.DeadLetterCount (the authoritative
// field on the run record, never subject to server-side page truncation).
func (s *scenarioState) thenRowsAccounted() error {
	if s.runDormant {
		return nil
	}
	s.findings = append(s.findings,
		checks.ReconcileCounts(int(s.runRec.RecordsIn), int(s.runRec.RecordsOut), int(s.runRec.RecordsSkipped), int(s.runRec.DeadLetterCount), s.scen.Seed)...)
	return nil
}

// thenLandedMatches is Law 2: the DB rowcount of the run's new table(s) equals the
// hub's reported recordsOut. Computes the data-table set-diff (the run's tables).
func (s *scenarioState) thenLandedMatches() error {
	if s.runDormant {
		return nil
	}
	tablesAfter, err := dataTableSet()
	if err != nil {
		return err
	}
	for t := range tablesAfter {
		if !s.beforeTables[t] {
			s.newTables = append(s.newTables, t)
		}
	}
	sort.Strings(s.newTables)

	total := 0
	dsn := os.Getenv("OTK_DB_DSN")
	for _, t := range s.newTables {
		n, live, perr := tableRowCount(dsn, t)
		if perr != nil {
			return perr
		}
		if !live {
			return fmt.Errorf("hub DB became unavailable counting %s", t)
		}
		total += n
	}
	s.findings = append(s.findings,
		checks.ReconcileLanded(int(s.runRec.RecordsOut), total, s.scen.Seed)...)
	return nil
}

// probeRunOutput probes the combined apply + run client output for robustness, text
// glitches, and leaks.
func (s *scenarioState) probeRunOutput() error {
	if s.runDormant {
		return nil
	}
	s.findings = append(s.findings, checks.Robustness(s.runResult, s.scen.Seed)...)
	out := s.result.Stdout + s.result.Stderr + s.runResult.Stdout + s.runResult.Stderr
	errOut := s.result.Stderr + s.runResult.Stderr
	s.findings = append(s.findings, checks.TextGlitch(out, errOut, s.scen.Seed)...)
	s.findings = append(s.findings, checks.SecretEcho(out, otkSentinel, s.scen.Seed)...)
	s.findings = append(s.findings, checks.FileDisclosure(out, s.scen.Seed)...)
	return nil
}

// cleanupRun deletes the connector (the hub now purges its dynamic tables on
// delete), then marks the scenario cleaned. A failure to remove is a hard
// precondition error.
func (s *scenarioState) cleanupRun() error {
	if s.runDormant {
		s.cleaned = true
		return nil
	}
	r := runner.Run(lmEnv(), lmBinary(), "connector", "delete", s.createdName)
	if !r.Started {
		return fmt.Errorf("could not launch lm to delete %q: %s", s.createdName, r.Stderr)
	}
	if r.Exit != 0 {
		return fmt.Errorf("lm connector delete %q exited %d: %s", s.createdName, r.Exit, r.Stderr)
	}
	after, err := snapshotConnectors()
	if err != nil {
		return err
	}
	if after[s.createdName] {
		return fmt.Errorf("teardown failed: connector %q still present after delete", s.createdName)
	}
	tablesAfter, err := dataTableSet()
	if err != nil {
		return err
	}
	for _, t := range s.newTables {
		if tablesAfter[t] {
			return fmt.Errorf("teardown failed: dynamic table %q still present after drop", t)
		}
	}
	s.cleaned = true
	return nil
}

// safeguardCleanupTables is the After-hook net for run scenarios that errored after
// creating a connector but before cleanupRun ran. Best-effort: delete the connector
// via lm so the hub purges its dynamic tables; failures are logged, not fatal.
func (s *scenarioState) safeguardCleanupTables() {
	if s.cleaned || s.runDormant || s.createdName == "" {
		return
	}
	r := runner.Run(lmEnv(), lmBinary(), "connector", "delete", s.createdName)
	if !r.Started || r.Exit != 0 {
		fmt.Printf("  OTK safeguard cleanup: best-effort delete of %q failed: %s\n", s.createdName, r.Stderr)
	}
}
