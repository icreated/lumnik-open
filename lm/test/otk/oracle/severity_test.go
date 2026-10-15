package oracle

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

func TestSeverityOrdering(t *testing.T) {
	if !(Critical > High && High > Medium && Medium > Low && Low > None) {
		t.Fatalf("severity ordering broken: %d %d %d %d %d",
			Critical, High, Medium, Low, None)
	}
}

func TestFindingString(t *testing.T) {
	f := Finding{Severity: Critical, Family: Security, CaseID: "auth/bad-audience", Seed: 42, Detail: "rls crossed"}
	got := f.String()
	want := "[CRITICAL] security case=auth/bad-audience (seed=42): rls crossed"
	if got != want {
		t.Errorf("got %q want %q", got, want)
	}
}

func TestAuthFamily_StringValue(t *testing.T) {
	if string(Auth) != "auth" {
		t.Fatalf("Auth family = %q, want \"auth\"", Auth)
	}
}

func TestFindingValidate_RejectsEmptyCaseID(t *testing.T) {
	f := Finding{Severity: Critical, Family: Security, Seed: 42, Detail: "rls crossed"}
	if err := f.Validate(); err == nil {
		t.Fatal("Validate() = nil, want an error for an empty CaseID")
	}
}

func TestFindingValidate_AcceptsCaseID(t *testing.T) {
	f := Finding{Severity: Critical, Family: Security, CaseID: generator.CaseID("auth/bad-audience"), Seed: 42, Detail: "rls crossed"}
	if err := f.Validate(); err != nil {
		t.Fatalf("Validate() = %v, want nil", err)
	}
}
