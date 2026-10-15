package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestDivergence_Agree_IsClean(t *testing.T) {
	if f := Divergence(1, 1, testCaseID, 7); len(f) != 0 {
		t.Errorf("api==db must be clean, got %v", f)
	}
	if f := Divergence(0, 0, testCaseID, 7); len(f) != 0 {
		t.Errorf("api==db==0 must be clean, got %v", f)
	}
}

func TestDivergence_HiddenWrite_IsCritical(t *testing.T) {
	f := Divergence(0, 1, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical || f[0].Family != oracle.Security || f[0].CaseID != testCaseID {
		t.Errorf("API says 0 but DB gained a row must be one CRITICAL security finding, got %v", f)
	}
}

func TestDivergence_PhantomApi_IsCritical(t *testing.T) {
	f := Divergence(1, 0, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical || f[0].Family != oracle.Security {
		t.Errorf("API says 1 but DB unchanged must be one CRITICAL security finding, got %v", f)
	}
}

func TestRunTerminalDivergence_DBTerminal_IsCritical(t *testing.T) {
	f := RunTerminalDivergence(true, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical || f[0].Family != oracle.Security || f[0].CaseID != testCaseID {
		t.Errorf("DB truth terminal but the API never reported it must be one CRITICAL security finding, got %v", f)
	}
}

func TestRunTerminalDivergence_DBNotTerminal_IsClean(t *testing.T) {
	// The run genuinely never finished — thenRunTerminal's hard error already says so; this
	// probe must not pile a second, misleading finding on top of a true broken precondition.
	if f := RunTerminalDivergence(false, testCaseID, 7); len(f) != 0 {
		t.Errorf("DB truth not yet terminal must be clean, got %v", f)
	}
}
