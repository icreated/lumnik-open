package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

func TestRobustness_CleanTypedErrorIsNoFinding(t *testing.T) {
	// A clean validation error: non-zero exit, typed message, no stack trace.
	r := runner.Result{Exit: 1, Stderr: "error: field 'name' invalid\n"}
	if got := Robustness(r, testCaseID, 7); len(got) != 0 {
		t.Errorf("clean typed error must yield no finding, got %v", got)
	}
}

func TestRobustness_PanicIsHigh(t *testing.T) {
	r := runner.Result{Exit: 2, Panicked: true, Stderr: "panic: nil deref\ngoroutine 1 ..."}
	got := Robustness(r, testCaseID, 7)
	if len(got) != 1 || got[0].Severity != oracle.High || got[0].Family != oracle.Robustness || got[0].CaseID != testCaseID {
		t.Fatalf("panic must be HIGH/robustness, got %v", got)
	}
}

func TestRobustness_TimeoutIsHigh(t *testing.T) {
	r := runner.Result{TimedOut: true}
	got := Robustness(r, testCaseID, 7)
	if len(got) != 1 || got[0].Severity != oracle.High {
		t.Fatalf("timeout must be HIGH, got %v", got)
	}
}

func TestRobustness_LeakedStackNoPanicIsMedium(t *testing.T) {
	r := runner.Result{Exit: 1, Stderr: "java.lang.NullPointerException\n\tat io.lumnik..."}
	got := Robustness(r, testCaseID, 7)
	if len(got) != 1 || got[0].Severity != oracle.Medium {
		t.Fatalf("leaked server stack must be MEDIUM, got %v", got)
	}
}
