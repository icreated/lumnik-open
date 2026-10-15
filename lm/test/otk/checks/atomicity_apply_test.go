package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

const testCaseID generator.CaseID = "apply/hack/sqli#0"

func nameSet(names ...string) map[string]bool {
	m := make(map[string]bool, len(names))
	for _, n := range names {
		m[n] = true
	}
	return m
}

func TestAtomicityApply_Exit0_ExactlyOne_IsClean(t *testing.T) {
	before := nameSet("a", "b")
	after := nameSet("a", "b", "c")
	if f := AtomicityApply(before, after, 0, testCaseID, 7); len(f) != 0 {
		t.Errorf("exit 0 with exactly one new connector must be clean, got %v", f)
	}
}

func TestAtomicityApply_Exit0_None_IsHigh(t *testing.T) {
	before := nameSet("a", "b")
	after := nameSet("a", "b")
	f := AtomicityApply(before, after, 0, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity || f[0].CaseID != testCaseID {
		t.Errorf("exit 0 that persisted nothing must be one HIGH atomicity finding, got %v", f)
	}
}

func TestAtomicityApply_Exit0_TwoNew_IsHigh(t *testing.T) {
	before := nameSet("a")
	after := nameSet("a", "b", "c")
	f := AtomicityApply(before, after, 0, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity {
		t.Errorf("exit 0 that created two connectors must be one HIGH finding, got %v", f)
	}
}

func TestAtomicityApply_ExitNonZero_None_IsClean(t *testing.T) {
	before := nameSet("a", "b")
	after := nameSet("a", "b")
	if f := AtomicityApply(before, after, 1, testCaseID, 7); len(f) != 0 {
		t.Errorf("rejected apply that created nothing must be clean, got %v", f)
	}
}

func TestAtomicityApply_ExitNonZero_Leaked_IsHigh(t *testing.T) {
	before := nameSet("a")
	after := nameSet("a", "x")
	f := AtomicityApply(before, after, 1, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity {
		t.Errorf("rejected apply that leaked a connector must be one HIGH atomicity finding, got %v", f)
	}
}

func TestAtomicityApply_ExitNonZero_TwoLeaked_IsHigh(t *testing.T) {
	before := nameSet("a")
	after := nameSet("a", "x", "y")
	f := AtomicityApply(before, after, 1, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity {
		t.Errorf("rejected apply that leaked two connectors must be one HIGH atomicity finding, got %v", f)
	}
}
