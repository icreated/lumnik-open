//go:build otk

package otk

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

const testRunCaseID generator.CaseID = "run/textual/rtl-in-data-cell"

func TestParseRunID(t *testing.T) {
	id, err := parseRunID("run scheduled: run-7\n")
	if err != nil || id != "run-7" {
		t.Fatalf("got %q err %v", id, err)
	}
	if _, err := parseRunID("nothing here"); err == nil {
		t.Fatal("expected error when no runId present")
	}
}

func TestParseRunGet_terminalWhenEnded(t *testing.T) {
	json := `[{"id":"run-7","status":"Completed","recordsIn":3,"recordsOut":2,"endedAt":"2026-06-15T10:00:00Z"}]`
	run, ok, err := parseRunGet(json)
	if err != nil || !ok {
		t.Fatalf("ended run must be terminal: ok=%v err=%v", ok, err)
	}
	if run.RecordsIn != 3 || run.RecordsOut != 2 {
		t.Fatalf("got %+v", run)
	}
}

func TestParseRunGet_notTerminalWhenNoEndedAt(t *testing.T) {
	_, ok, err := parseRunGet(`[{"id":"run-7","status":"RUNNING","recordsIn":0,"recordsOut":0}]`)
	if err != nil || ok {
		t.Fatalf("running run must not be terminal: ok=%v err=%v", ok, err)
	}
}

func TestParseRunGet_emptyIsError(t *testing.T) {
	if _, _, err := parseRunGet(`[]`); err == nil {
		t.Fatal("expected error on empty run array")
	}
}

func TestApplyMustSucceedOnRunLane(t *testing.T) {
	cases := []struct {
		name    string
		started bool
		exit    int
		wantErr bool
	}{
		{"not started", false, 0, true},
		{"exit nonzero", true, 1, true},
		{"success", true, 0, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := applyMustSucceed(runner.Result{Started: tc.started, Exit: tc.exit}, "/lm")
			if (err != nil) != tc.wantErr {
				t.Fatalf("applyMustSucceed() = %v, wantErr %v", err, tc.wantErr)
			}
		})
	}
}

// TestRowsAccountedUsesAuthoritativeCount is the RED/TDD anchor for Task 2,
// carried forward: reconciliation MUST source its counts from the run
// record's own fields (runRec.RecordsSkipped for Law 1, runRec.DeadLetterCount
// for Law 1b) — both are authoritative scalars, never derived from an
// `lm dlq list` page walk. A run with all rows dead-lettered and none benign
// (in=out+skipped, dlq=skipped) must produce no finding; a run with a genuine
// vanish must produce a HIGH finding.
func TestRowsAccountedUsesAuthoritativeCount(t *testing.T) {
	// Balanced: all 250 rows dead-lettered (none benign) — no vanish, no phantom.
	balanced, _, err := parseRunGet(`[{"id":"run-auth","status":"Completed","recordsIn":250,"recordsOut":0,"recordsSkipped":250,"deadLetterCount":250,"endedAt":"2026-06-28T10:00:00Z"}]`)
	if err != nil {
		t.Fatalf("parseRunGet: %v", err)
	}
	if balanced.DeadLetterCount != 250 {
		t.Fatalf("deadLetterCount not parsed: got %d, want 250", balanced.DeadLetterCount)
	}
	if f := checks.ReconcileCounts(int(balanced.RecordsIn), int(balanced.RecordsOut), int(balanced.RecordsSkipped), int(balanced.DeadLetterCount), testRunCaseID, 42); len(f) != 0 {
		t.Fatalf("balanced run must produce no finding; got %v", f)
	}

	// Genuine vanish: 50 rows unaccounted for — must be exactly one HIGH finding.
	vanish := types.Run{RecordsIn: 250, RecordsOut: 0, RecordsSkipped: 200, DeadLetterCount: 200}
	fv := checks.ReconcileCounts(int(vanish.RecordsIn), int(vanish.RecordsOut), int(vanish.RecordsSkipped), int(vanish.DeadLetterCount), testRunCaseID, 42)
	if len(fv) != 1 {
		t.Fatalf("genuine vanish must produce exactly 1 finding; got %v", fv)
	}
	if fv[0].Severity != oracle.High {
		t.Fatalf("genuine vanish finding must be Severity=High; got %v", fv[0].Severity)
	}
}

func TestNeedsTableSafeguard(t *testing.T) {
	if needsTableSafeguard(true, map[string]bool{"a": true}) {
		t.Error("cleaned scenarios need no table safeguard")
	}
	if !needsTableSafeguard(false, map[string]bool{}) {
		t.Error("uncleaned scenario with a captured baseline should run the safeguard")
	}
	if needsTableSafeguard(false, nil) {
		t.Error("nil baseline must skip the safeguard (avoid mass-drop)")
	}
}
