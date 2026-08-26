package checks

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// Expectation is what an auth probe should produce when the boundary holds.
type Expectation int

const (
	Reject401   Expectation = iota // no/invalid principal → 401
	Reject403                      // valid principal, missing role → 403
	EmptyResult                    // foreign-tenant token → 200 (isolation holds at the boundary); foreign-row leakage is graded separately by CrossTenantLeak
	Allow200                       // the happy path → 200 with data
)

// AuthOutcome grades one auth probe against its expectation. Severity attaches to
// the consequence:
//   - a request that should be REJECTED but returns 200 → CRITICAL (a breach);
//   - for the cross-tenant (EmptyResult) case it grades the STATUS only (200 = the
//     boundary held); foreign-row leakage is graded separately by CrossTenantLeak,
//     because a foreign tenant may legitimately have its own rows;
//   - an untyped error (status ≥ 500) → MEDIUM;
//   - rejected, but with the WRONG status code (e.g. wrong-role yielding 401 instead
//     of 403, or a cross-tenant token rejected instead of returning 200-empty) →
//     MEDIUM (a behavioral regression — still no breach, so it surfaces without
//     failing the security gate, but the lane no longer treats it as a clean pass);
//   - the exact expected outcome → PASS.
//
// A non-HTTP status (e.g. 0 from a transport error: connection refused, timeout)
// yields no finding here — it means the boundary was never reached, not that it
// held. The caller must enforce hub reachability as a precondition before grading.
func AuthOutcome(status int, body string, expect Expectation, defect string, seed int64) []oracle.Finding {
	if status == 0 {
		return nil // boundary never reached; the caller enforces reachability
	}
	switch expect {
	case Reject401, Reject403:
		want := 401
		if expect == Reject403 {
			want = 403
		}
		switch {
		case status == 200:
			return []oracle.Finding{{
				Severity: oracle.Critical, Family: oracle.Auth, Seed: seed,
				Detail: fmt.Sprintf("auth bypass landed (%s): expected %d, hub returned 200", defect, want),
			}}
		case status == want:
			return nil // rejected with exactly the expected code
		case status >= 500:
			return []oracle.Finding{{
				Severity: oracle.Medium, Family: oracle.Auth, Seed: seed,
				Detail: fmt.Sprintf("forged token (%s) yielded an untyped error (status %d)", defect, status),
			}}
		default:
			return []oracle.Finding{{
				Severity: oracle.Medium, Family: oracle.Auth, Seed: seed,
				Detail: fmt.Sprintf("wrong rejection code (%s): expected %d, hub returned %d", defect, want, status),
			}}
		}
	case EmptyResult:
		switch {
		case status == 200:
			// Isolation held at the boundary. A non-empty body is NOT a breach by
			// itself: the foreign tenant may legitimately have its OWN rows. Foreign-
			// row leakage (the tenant-1 connector being visible) is graded by the
			// caller via CrossTenantLeak, not here.
			return nil
		case status >= 500:
			return []oracle.Finding{{
				Severity: oracle.Medium, Family: oracle.Auth, Seed: seed,
				Detail: fmt.Sprintf("cross-tenant probe yielded an untyped error (status %d)", status),
			}}
		default:
			return []oracle.Finding{{
				Severity: oracle.Medium, Family: oracle.Auth, Seed: seed,
				Detail: fmt.Sprintf("cross-tenant probe (%s): expected 200 (empty), hub returned %d", defect, status),
			}}
		}
	default: // Allow200
		return nil
	}
}

// CrossTenantLeak fails CRITICAL if the foreign (tenant-1) connector is visible in
// a connector-list body fetched under a different tenant's token. It parses the
// JSON list and matches the name EXACTLY — a substring check would false-positive
// when the foreign name is a prefix of an unrelated connector (e.g. "otk-run-1"
// matching "otk-run-10"). It falls back to a substring scan only when the body is
// not the expected JSON (so a malformed/HTML error page that echoes the name is
// still caught).
func CrossTenantLeak(body, foreignName string, seed int64) []oracle.Finding {
	if foreignName == "" {
		return nil
	}
	visible := false
	if names, err := ConnectorNames(body); err == nil {
		visible = names[foreignName]
	} else {
		visible = strings.Contains(body, foreignName)
	}
	if visible {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Auth, Seed: seed,
			Detail: "tenant isolation crossed: foreign connector " + foreignName + " visible",
		}}
	}
	return nil
}
