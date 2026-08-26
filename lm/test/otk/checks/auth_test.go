package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestAuthOutcome_Reject401_Got401_IsClean(t *testing.T) {
	if f := AuthOutcome(401, "authentication required", Reject401, "expired", 7); len(f) != 0 {
		t.Fatalf("clean 401 must yield no finding, got %v", f)
	}
}

func TestAuthOutcome_Reject401_Got200_IsCritical(t *testing.T) {
	f := AuthOutcome(200, `[{"name":"x"}]`, Reject401, "tampered-sig", 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical || f[0].Family != oracle.Auth {
		t.Fatalf("a landed bypass must be CRITICAL/auth, got %v", f)
	}
}

func TestAuthOutcome_Reject403_Got403_IsClean(t *testing.T) {
	if f := AuthOutcome(403, "missing role lm_integrator", Reject403, "wrong-role", 7); len(f) != 0 {
		t.Fatalf("clean 403 must yield no finding, got %v", f)
	}
}

func TestAuthOutcome_Reject403_Got200_IsCritical(t *testing.T) {
	f := AuthOutcome(200, `[]`, Reject403, "wrong-role", 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical {
		t.Fatalf("a role escalation must be CRITICAL, got %v", f)
	}
}

func TestAuthOutcome_ForgedToken_Got500_IsMedium(t *testing.T) {
	f := AuthOutcome(500, "internal error", Reject401, "bad-issuer", 7)
	if len(f) != 1 || f[0].Severity != oracle.Medium {
		t.Fatalf("a 500 on a forged token must be MEDIUM (untyped error), got %v", f)
	}
}

func TestAuthOutcome_EmptyResult_Empty_IsClean(t *testing.T) {
	if f := AuthOutcome(200, `[]`, EmptyResult, "cross-tenant", 7); len(f) != 0 {
		t.Fatalf("an empty cross-tenant list must be clean, got %v", f)
	}
}

func TestAuthOutcome_EmptyResult_OwnRows200_IsClean(t *testing.T) {
	// A foreign tenant may legitimately have its OWN connectors; a non-empty 200 is
	// not a breach by itself. AuthOutcome grades status only — foreign-row leakage
	// (the tenant-1 name appearing) is CrossTenantLeak's job.
	if f := AuthOutcome(200, `[{"name":"tenant2-own-connector"}]`, EmptyResult, "cross-tenant", 7); len(f) != 0 {
		t.Fatalf("a non-empty own-tenant 200 must be clean in AuthOutcome, got %v", f)
	}
}

func TestAuthOutcome_EmptyResult_Got500_IsMedium(t *testing.T) {
	f := AuthOutcome(500, "boom", EmptyResult, "cross-tenant", 7)
	if len(f) != 1 || f[0].Severity != oracle.Medium {
		t.Fatalf("a 500 on a cross-tenant probe must be MEDIUM, got %v", f)
	}
}

func TestAuthOutcome_Reject403_Got401_IsWrongCodeMedium(t *testing.T) {
	// wrong-role should yield 403; a 401 means the hub mishandled a valid principal —
	// still rejected (no breach), but a behavioral regression the lane must surface.
	f := AuthOutcome(401, "authentication required", Reject403, "wrong-role", 7)
	if len(f) != 1 || f[0].Severity != oracle.Medium {
		t.Fatalf("Reject403 got 401 must be a MEDIUM wrong-code finding, got %v", f)
	}
}

func TestAuthOutcome_Reject401_Got403_IsWrongCodeMedium(t *testing.T) {
	// An invalid token that reaches the role check (403) instead of being rejected
	// at authentication (401) is a behavioral regression — surface it.
	f := AuthOutcome(403, "missing role lm_integrator", Reject401, "expired", 7)
	if len(f) != 1 || f[0].Severity != oracle.Medium {
		t.Fatalf("Reject401 got 403 must be a MEDIUM wrong-code finding, got %v", f)
	}
}

func TestAuthOutcome_EmptyResult_Rejected_IsMedium(t *testing.T) {
	// A cross-tenant (valid) token rejected with 401/403 instead of returning
	// 200-empty is a behavioral regression (the documented contract is RLS-scoped
	// 200), not a breach — MEDIUM.
	f := AuthOutcome(403, "missing role lm_integrator", EmptyResult, "cross-tenant", 7)
	if len(f) != 1 || f[0].Severity != oracle.Medium {
		t.Fatalf("EmptyResult rejected (403) must be a MEDIUM finding, got %v", f)
	}
}

func TestAuthOutcome_TransportError_StatusZero_IsClean(t *testing.T) {
	// status 0 = boundary never reached; the caller enforces reachability, not us.
	if f := AuthOutcome(0, "connection refused", Reject401, "garbage", 7); len(f) != 0 {
		t.Fatalf("a transport error (status 0) must yield no finding, got %v", f)
	}
}

func TestCrossTenantLeak_ForeignNamePresent_IsCritical(t *testing.T) {
	f := CrossTenantLeak(`[{"name":"otk-baseline-7"}]`, "otk-baseline-7", 7)
	if len(f) != 1 || f[0].Severity != oracle.Critical {
		t.Fatalf("a foreign connector visible cross-tenant must be CRITICAL, got %v", f)
	}
}

func TestCrossTenantLeak_Absent_IsClean(t *testing.T) {
	if f := CrossTenantLeak(`[]`, "otk-baseline-7", 7); len(f) != 0 {
		t.Fatalf("foreign name absent must be clean, got %v", f)
	}
}

func TestCrossTenantLeak_SubstringNameIsNotALeak(t *testing.T) {
	// Exact match, not substring: "otk-run-1" must NOT match "otk-run-10".
	if f := CrossTenantLeak(`[{"name":"otk-run-10"}]`, "otk-run-1", 7); len(f) != 0 {
		t.Fatalf("a mere substring (otk-run-1 ⊂ otk-run-10) must not be a leak, got %v", f)
	}
}

func TestCrossTenantLeak_UnparseableBodyFallsBackToSubstring(t *testing.T) {
	// A non-JSON error page that echoes the foreign name is still caught.
	if f := CrossTenantLeak(`<html>error: otk-baseline-7 not allowed</html>`, "otk-baseline-7", 7); len(f) != 1 {
		t.Fatalf("unparseable body must fall back to substring detection, got %v", f)
	}
}
