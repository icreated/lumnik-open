package oracle

import "testing"

func TestSeverityOrdering(t *testing.T) {
	if !(Critical > High && High > Medium && Medium > Low && Low > None) {
		t.Fatalf("severity ordering broken: %d %d %d %d %d",
			Critical, High, Medium, Low, None)
	}
}

func TestFindingString(t *testing.T) {
	f := Finding{Severity: Critical, Family: Security, Seed: 42, Detail: "rls crossed"}
	got := f.String()
	want := "[CRITICAL] security (seed=42): rls crossed"
	if got != want {
		t.Errorf("got %q want %q", got, want)
	}
}

func TestAuthFamily_StringValue(t *testing.T) {
	if string(Auth) != "auth" {
		t.Fatalf("Auth family = %q, want \"auth\"", Auth)
	}
}
