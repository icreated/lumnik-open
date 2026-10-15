//go:build otk

package otk

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

const (
	sumApplyCase generator.CaseID = "apply/hack/sqli#0"
	sumAuthCase  generator.CaseID = "auth/bad-audience"
	sumRunCase   generator.CaseID = "run/textual/rtl-in-data-cell"
)

// assertCaseArithmetic pins the invariant a summary must never violate:
// CasesAttempted == CasesExercised + CasesDormant. A battery-level probe like
// hub-intactness — dormant but never attempted as a case — must not be able to
// break it (see TestBuildSummary_KnownOffCatalogueLanesAccepted).
func assertCaseArithmetic(t *testing.T, s BatterySummary) {
	t.Helper()
	if s.CasesAttempted != s.CasesExercised+s.CasesDormant {
		t.Errorf("case arithmetic broken: attempted=%d != exercised=%d + dormant=%d",
			s.CasesAttempted, s.CasesExercised, s.CasesDormant)
	}
}

// 1. normal synthetic grouping
func TestBuildSummary_NormalGrouping(t *testing.T) {
	attempted := []generator.CaseID{sumApplyCase, sumAuthCase, sumRunCase}
	dormant := []dormancyEntry{{CaseID: sumAuthCase, Reason: "set OTK_OIDC_ISSUER"}}
	findings := []oracle.Finding{
		{Severity: oracle.High, Family: oracle.Atomicity, CaseID: sumApplyCase, Detail: "x"},
		{Severity: oracle.Critical, Family: oracle.Security, CaseID: sumApplyCase, Detail: "y"},
	}

	got, err := buildSummary(attempted, dormant, findings, 5*time.Second)
	if err != nil {
		t.Fatalf("buildSummary() error = %v", err)
	}
	if got.CasesAttempted != 3 {
		t.Errorf("CasesAttempted = %d, want 3", got.CasesAttempted)
	}
	if got.CasesDormant != 1 {
		t.Errorf("CasesDormant = %d, want 1", got.CasesDormant)
	}
	if got.CasesExercised != 2 {
		t.Errorf("CasesExercised = %d, want 2", got.CasesExercised)
	}
	if got.Findings[oracle.High] != 1 || got.Findings[oracle.Critical] != 1 {
		t.Errorf("Findings = %v, want {High:1, Critical:1}", got.Findings)
	}
	if len(got.Dormancy["auth"]) != 1 {
		t.Errorf("Dormancy[auth] = %v, want 1 entry", got.Dormancy["auth"])
	}
	assertCaseArithmetic(t, got)
}

// 2. unknown lane in Dormant → error
func TestBuildSummary_UnknownLaneInDormant_Errors(t *testing.T) {
	dormant := []dormancyEntry{{CaseID: "proces/apply/x", Reason: "typo'd lane"}}
	if _, err := buildSummary(nil, dormant, nil, 0); err == nil {
		t.Fatal("buildSummary() = nil error, want an error for an unknown lane in Dormant")
	}
}

// 3. unknown lane in Attempted → error
func TestBuildSummary_UnknownLaneInAttempted_Errors(t *testing.T) {
	attempted := []generator.CaseID{"proces/apply/x"}
	if _, err := buildSummary(attempted, nil, nil, 0); err == nil {
		t.Fatal("buildSummary() = nil error, want an error for an unknown lane in Attempted")
	}
}

// 4. empty CaseID → error (covers all three inputs)
func TestBuildSummary_EmptyCaseID_Errors(t *testing.T) {
	t.Run("attempted", func(t *testing.T) {
		if _, err := buildSummary([]generator.CaseID{""}, nil, nil, 0); err == nil {
			t.Fatal("want an error for an empty CaseID in Attempted")
		}
	})
	t.Run("dormant", func(t *testing.T) {
		dormant := []dormancyEntry{{CaseID: "", Reason: "no case"}}
		if _, err := buildSummary(nil, dormant, nil, 0); err == nil {
			t.Fatal("want an error for an empty CaseID in Dormant")
		}
	})
	t.Run("finding", func(t *testing.T) {
		findings := []oracle.Finding{{Severity: oracle.High, Family: oracle.Security, Detail: "x"}}
		if _, err := buildSummary(nil, nil, findings, 0); err == nil {
			t.Fatal("want an error for an empty CaseID in a Finding")
		}
	})
}

// 5. a case tried then dormant → absent from exercised
func TestBuildSummary_AttemptedThenDormant_IsNotExercised(t *testing.T) {
	attempted := []generator.CaseID{sumApplyCase}
	dormant := []dormancyEntry{{CaseID: sumApplyCase, Reason: "needs OTK_DB_DSN"}}

	got, err := buildSummary(attempted, dormant, nil, 0)
	if err != nil {
		t.Fatalf("buildSummary() error = %v", err)
	}
	if got.CasesExercised != 0 {
		t.Errorf("CasesExercised = %d, want 0 (the only attempted case went dormant)", got.CasesExercised)
	}
	assertCaseArithmetic(t, got)
}

// 6. two reasons for the same CaseID → both visible, dormant count not doubled
func TestBuildSummary_TwoReasonsSameCaseID_BothKeptCountNotDoubled(t *testing.T) {
	attempted := []generator.CaseID{sumApplyCase}
	dormant := []dormancyEntry{
		{CaseID: sumApplyCase, Reason: "first reason"},
		{CaseID: sumApplyCase, Reason: "second reason"},
	}

	got, err := buildSummary(attempted, dormant, nil, 0)
	if err != nil {
		t.Fatalf("buildSummary() error = %v", err)
	}
	if got.CasesDormant != 1 {
		t.Errorf("CasesDormant = %d, want 1 (one distinct CaseID)", got.CasesDormant)
	}
	if len(got.Dormancy["apply"]) != 2 {
		t.Fatalf("Dormancy[apply] = %v, want both reasons kept", got.Dormancy["apply"])
	}
	out := (&got).String()
	if !strings.Contains(out, "first reason") || !strings.Contains(out, "second reason") {
		t.Errorf("String() must surface both reasons, got %q", out)
	}
	assertCaseArithmetic(t, got)
}

// TestBuildSummary_KnownOffCatalogueLanesAccepted is also the regression pin for
// a review finding on this PR: hub-intactness is a once-per-battery probe, never
// a godog scenario, so it is NEVER added to attemptedCaseIDs (see otk_test.go).
// Counting it into CasesDormant anyway used to report impossible arithmetic —
// this exact input ("1 attempted, 1 exercised, 1 dormant") is what caught it.
func TestBuildSummary_KnownOffCatalogueLanesAccepted(t *testing.T) {
	attempted := []generator.CaseID{"view-store/store-roundtrip"}
	dormant := []dormancyEntry{{CaseID: "hub-intactness/db-dsn", Reason: "set OTK_DB_DSN"}}

	got, err := buildSummary(attempted, dormant, nil, 0)
	if err != nil {
		t.Fatalf("buildSummary() error = %v, want the off-catalogue lanes accepted", err)
	}
	if got.CasesAttempted != 1 || got.CasesExercised != 1 || got.CasesDormant != 0 {
		t.Errorf("got attempted=%d exercised=%d dormant=%d, want 1/1/0 — "+
			"hub-intactness is dormant but was never an attempted case, so it must not count",
			got.CasesAttempted, got.CasesExercised, got.CasesDormant)
	}
	if len(got.Dormancy["hub-intactness"]) != 1 {
		t.Errorf("Dormancy[hub-intactness] = %v, want the probe still visible there", got.Dormancy["hub-intactness"])
	}
	assertCaseArithmetic(t, got)
}
