package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestConnectorNames_ParsesSet(t *testing.T) {
	set, err := ConnectorNames(`[{"name":"a"},{"name":"b"}]`)
	if err != nil || !set["a"] || !set["b"] || len(set) != 2 {
		t.Fatalf("expected {a,b}, got %v err=%v", set, err)
	}
}

func TestConnectorNames_ErrorsOnGarbage(t *testing.T) {
	if _, err := ConnectorNames("not json"); err == nil {
		t.Error("garbage input must return an error")
	}
}

func TestAtomicitySetDiff_NewConnectorIsHigh(t *testing.T) {
	before := map[string]bool{"a": true}
	after := map[string]bool{"a": true, "otk-adv-5": true}
	got := AtomicitySetDiff(before, after, testCaseID, 5)
	if len(got) != 1 || got[0].Severity != oracle.High || got[0].Family != oracle.Atomicity || got[0].CaseID != testCaseID {
		t.Fatalf("new connector must be HIGH/atomicity, got %v", got)
	}
}

func TestAtomicitySetDiff_NoChangeIsClean(t *testing.T) {
	s := map[string]bool{"a": true, "b": true}
	if got := AtomicitySetDiff(s, s, testCaseID, 1); len(got) != 0 {
		t.Errorf("identical sets must yield no finding, got %v", got)
	}
}

func TestAtomicitySetDiff_LeftoverIsNotFlagged(t *testing.T) {
	// A connector present BEFORE and after (dirty-hub leftover) is not a finding:
	// this is exactly why the differential replaces the single-name check.
	before := map[string]bool{"otk-adv-9": true}
	after := map[string]bool{"otk-adv-9": true}
	if got := AtomicitySetDiff(before, after, testCaseID, 9); len(got) != 0 {
		t.Errorf("pre-existing connector must not be flagged, got %v", got)
	}
}
