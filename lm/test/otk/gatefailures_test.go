package otk

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// The gold gate used to `t.Fatalf` on a non-zero godog status BEFORE computing the verdict, so a
// single hard step error discarded every graded finding. On the four red nights of 2026-07-15 →
// 07-26 that is exactly what happened: one run failed to reach a terminal status, and seven HIGH
// atomicity findings were never evaluated, never reported, never weighed against the badge.

func TestGateFailures_aHardStepErrorDoesNotHideTheFindings(t *testing.T) {
	v := oracle.Verdict{
		Tier:     oracle.Gold,
		Passed:   false,
		Findings: []oracle.Finding{{Severity: oracle.High, Family: oracle.Atomicity, Seed: 13, Detail: "boom"}},
	}

	reasons := gateFailures(v, 1)

	if len(reasons) != 2 {
		t.Fatalf("both the verdict and the step failure must be reported, got %d: %v", len(reasons), reasons)
	}
	if !strings.Contains(strings.Join(reasons, " | "), "finding") {
		t.Errorf("the findings must survive a hard step error: %v", reasons)
	}
	if !strings.Contains(strings.Join(reasons, " | "), "godog") {
		t.Errorf("the step failure must still be reported: %v", reasons)
	}
}

func TestGateFailures_silentWhenEverythingPasses(t *testing.T) {
	v := oracle.Verdict{Tier: oracle.Gold, Passed: true}

	if reasons := gateFailures(v, 0); len(reasons) != 0 {
		t.Errorf("a clean run must produce no failure reason, got: %v", reasons)
	}
}

func TestGateFailures_findingsAloneStillFailTheGate(t *testing.T) {
	v := oracle.Verdict{
		Tier:     oracle.Gold,
		Passed:   false,
		Findings: []oracle.Finding{{Severity: oracle.High, Family: oracle.Atomicity, Seed: 7}},
	}

	reasons := gateFailures(v, 0)

	if len(reasons) != 1 || !strings.Contains(reasons[0], "GOLD") {
		t.Errorf("a failing verdict alone must fail the gate and name the tier, got: %v", reasons)
	}
}
