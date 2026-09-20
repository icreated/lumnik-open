package calibration

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func firstFault(t *testing.T) Fault {
	t.Helper()
	f, err := ByName("atomicity-underreport-landed")
	if err != nil {
		t.Fatalf("ByName: %v", err)
	}
	return f
}

// full is an attempted/total pair comfortably above the floor, so these rows test
// the finding logic and nothing else.
const fullTotal, fullAttempted = 100, 100

// The run passes if and only if a finding matches family AND severity floor AND
// detail pattern. Red is not the bar; the right red is.
func TestFaultModeRequiresTheDeclaredFinding(t *testing.T) {
	f := firstFault(t)
	hit := oracle.Finding{Severity: oracle.High, Family: oracle.Atomicity,
		Detail: "hub reported 2 row(s) written but the DB holds 3"}

	for _, c := range []struct {
		name     string
		findings []oracle.Finding
		wantPass bool
	}{
		{"the declared finding", []oracle.Finding{hit}, true},
		{"the declared finding above the floor severity", []oracle.Finding{
			{Severity: oracle.Critical, Family: oracle.Atomicity, Detail: hit.Detail}}, true},
		{"the declared finding among others", []oracle.Finding{
			{Severity: oracle.High, Family: oracle.Atomicity, Detail: decoys[0]}, hit}, true},
		{"no findings at all", nil, false},
		{"another family", []oracle.Finding{
			{Severity: oracle.High, Family: oracle.Security, Detail: hit.Detail}}, false},
		{"below the declared severity", []oracle.Finding{
			{Severity: oracle.Medium, Family: oracle.Atomicity, Detail: hit.Detail}}, false},
		{"a detail the pattern does not match", []oracle.Finding{
			{Severity: oracle.High, Family: oracle.Atomicity, Detail: decoys[0]}}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			err := Judge(Injected, f, c.findings, fullAttempted, fullTotal)
			if c.wantPass && err != nil {
				t.Fatalf("must pass, got: %v", err)
			}
			if !c.wantPass && err == nil {
				t.Fatal("must fail")
			}
		})
	}
}

// A fault rarely produces exactly one finding: under-reporting a written row breaks
// both conservation and the landed-rows law at once. The expectation is "this
// finding is present", never "this finding alone" — pinning the detail is what keeps
// that honest.
func TestFaultModeAcceptsCollateralFindings(t *testing.T) {
	f := firstFault(t)
	findings := []oracle.Finding{
		{Severity: oracle.High, Family: oracle.Atomicity, Detail: decoys[0]},
		{Severity: oracle.Low, Family: oracle.Text, Detail: decoys[9]},
		{Severity: oracle.High, Family: oracle.Atomicity, Detail: "hub reported 0 row(s) written but the DB holds 1"},
	}
	if err := Judge(Injected, f, findings, fullAttempted, fullTotal); err != nil {
		t.Fatalf("collateral findings must not fail the calibration, got: %v", err)
	}
}

// The floor is the guard against the obvious false green: a harness that crashed
// early and happened to emit the right finding is not proof. Both sides of the
// boundary, because one side alone decorates the number instead of meaning it.
func TestFaultModeRefusesAnEmptyRun(t *testing.T) {
	f := firstFault(t)
	hit := []oracle.Finding{{Severity: oracle.High, Family: oracle.Atomicity,
		Detail: "hub reported 0 row(s) written but the DB holds 1"}}

	if got := MinAttempted(10); got != 9 {
		t.Fatalf("MinAttempted(10) = %d, want 9 (90%% of 10)", got)
	}
	if got := MinAttempted(136); got != 123 {
		t.Fatalf("MinAttempted(136) = %d, want 123 (90%% of 136, rounded up)", got)
	}

	if err := Judge(Injected, f, hit, 9, 10); err != nil {
		t.Fatalf("exactly at the floor must pass, got: %v", err)
	}
	err := Judge(Injected, f, hit, 8, 10)
	if err == nil {
		t.Fatal("one below the floor must fail even with the right finding present")
	}
	if !strings.Contains(err.Error(), "8") || !strings.Contains(err.Error(), "10") {
		t.Errorf("the refusal must carry the count, got: %v", err)
	}

	// The control phase needs the floor MORE, not less: a control that ran nothing
	// trivially observes the finding absent and goes green.
	if err := Judge(Control, f, nil, 1, 10); err == nil {
		t.Fatal("a control phase below the floor must fail, not pass by emptiness")
	}
}

// An empty catalogue (e.g. catalogueFeatures() returning no error but no scenarios
// either) must refuse rather than pass by vacuity: zero attempted of zero total
// clears the 90% floor trivially, so the floor check alone would not catch it.
func TestFaultModeRefusesAnEmptyCatalogue(t *testing.T) {
	f := firstFault(t)
	if err := Judge(Injected, f, nil, 0, 0); err == nil {
		t.Fatal("total<=0 must refuse, not pass because there was nothing to fail")
	}
}

// The godog status is collected, never acted on: the hub is deliberately broken, so
// steps WILL fail, and treating that as a verdict would make every calibration
// trivially successful. Judge never receives the status — that is the contract, and
// this test pins it by signature.
func TestFaultModeIgnoresGodogStatus(t *testing.T) {
	f := firstFault(t)
	hit := []oracle.Finding{{Severity: oracle.High, Family: oracle.Atomicity,
		Detail: "hub reported 0 row(s) written but the DB holds 1"}}
	if err := Judge(Injected, f, hit, fullAttempted, fullTotal); err != nil {
		t.Fatalf("the verdict must depend on the findings alone, got: %v", err)
	}
}

// Presence is correlation. Causality needs the control — and the two failures must
// not be interchangeable: one accuses the PRODUCT, the other the HARNESS.
func TestControlPhaseFailsWhenTheFindingIsAlreadyPresent(t *testing.T) {
	f := firstFault(t)
	present := []oracle.Finding{{Severity: oracle.High, Family: oracle.Atomicity,
		Detail: "hub reported 0 row(s) written but the DB holds 1"}}

	if err := Judge(Control, f, nil, fullAttempted, fullTotal); err != nil {
		t.Fatalf("a clean control must pass, got: %v", err)
	}

	ctlErr := Judge(Control, f, present, fullAttempted, fullTotal)
	if ctlErr == nil {
		t.Fatal("a control phase that already exhibits the finding must fail")
	}
	injErr := Judge(Injected, f, nil, fullAttempted, fullTotal)
	if injErr == nil {
		t.Fatal("an injected phase without the finding must fail")
	}

	if !strings.Contains(ctlErr.Error(), "CONTROL") || !strings.Contains(ctlErr.Error(), "PRODUCT") {
		t.Errorf("the control failure must name the phase and accuse the product, got: %v", ctlErr)
	}
	if !strings.Contains(injErr.Error(), "INJECTED") || !strings.Contains(injErr.Error(), "HARNESS") {
		t.Errorf("the injected failure must name the phase and accuse the harness, got: %v", injErr)
	}
	if ctlErr.Error() == injErr.Error() {
		t.Fatal("the two phase failures must never be interchangeable")
	}
}
