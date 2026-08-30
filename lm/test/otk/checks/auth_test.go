package checks

import (
	"fmt"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// clean is the absence of a finding, spelled so the matrix below reads as a matrix.
const clean = oracle.Severity(-1)

// The auth grader is a severity matrix: what the probe DECLARED it expects, crossed with
// what the hub actually answered. Written as twelve one-off funcs it covered eight of the
// thirty-two cells and nobody could see which eight — including the entire Allow200 row,
// which returned "clean" for every status because it was the switch's default arm.
//
// Every cell is stated here, so a hole is a missing row rather than a silence.
func TestAuthOutcomeMatrix(t *testing.T) {
	cases := []struct {
		name   string
		expect Expectation
		status int
		want   oracle.Severity
		detail string // substring the finding must carry, so a row cannot pass on severity alone
	}{
		// ── the boundary never answered: not our finding to make ──────────────
		{"reject401/transport error", Reject401, 0, clean, ""},
		{"reject403/transport error", Reject403, 0, clean, ""},
		{"emptyResult/transport error", EmptyResult, 0, clean, ""},
		{"allow200/transport error", Allow200, 0, clean, ""},

		// ── a request that must be REJECTED but was SERVED is a breach ────────
		// Not just 200: a 201/202/204 means the hub carried the request out. The
		// grader used to call only 200 a bypass and filed every other 2xx under
		// "wrong rejection code" — MEDIUM, which cannot fail the declared tier.
		{"reject401/200 is a landed bypass", Reject401, 200, oracle.Critical, "auth bypass landed"},
		{"reject401/201 is also served", Reject401, 201, oracle.Critical, "auth bypass landed"},
		{"reject401/204 is also served", Reject401, 204, oracle.Critical, "auth bypass landed"},
		{"reject403/200 is a role escalation", Reject403, 200, oracle.Critical, "auth bypass landed"},
		{"reject403/204 is also served", Reject403, 204, oracle.Critical, "auth bypass landed"},

		// ── rejected with the code the contract names ─────────────────────────
		{"reject401/401", Reject401, 401, clean, ""},
		{"reject403/403", Reject403, 403, clean, ""},

		// ── rejected, but not the way the contract says ───────────────────────
		{"reject401/403", Reject401, 403, oracle.Medium, "wrong rejection code"},
		{"reject401/404", Reject401, 404, oracle.Medium, "wrong rejection code"},
		{"reject401/302", Reject401, 302, oracle.Medium, "wrong rejection code"},
		{"reject403/401", Reject403, 401, oracle.Medium, "wrong rejection code"},
		{"reject403/404", Reject403, 404, oracle.Medium, "wrong rejection code"},

		// ── the hub fell over instead of deciding ─────────────────────────────
		{"reject401/500", Reject401, 500, oracle.Medium, "untyped error"},
		{"reject403/500", Reject403, 500, oracle.Medium, "untyped error"},
		{"emptyResult/500", EmptyResult, 500, oracle.Medium, "untyped error"},
		{"allow200/500", Allow200, 500, oracle.Medium, "refused a legitimate request"},

		// ── the cross-tenant contract is an RLS-scoped 200 ────────────────────
		// Status only: a foreign tenant may legitimately have its OWN rows, so a
		// non-empty 200 is clean here and leakage is CrossTenantLeak's job.
		{"emptyResult/200", EmptyResult, 200, clean, ""},
		{"emptyResult/403 rejected instead", EmptyResult, 403, oracle.Medium, "expected 200"},
		{"emptyResult/401 rejected instead", EmptyResult, 401, oracle.Medium, "expected 200"},
		{"emptyResult/204 is not the contract", EmptyResult, 204, oracle.Medium, "expected 200"},

		// ── the happy path: refusing a legitimate request is a finding too ────
		// This whole row used to be the switch's `default:` arm and graded CLEAN for
		// every status, including 401, 403 and 500.
		{"allow200/200", Allow200, 200, clean, ""},
		{"allow200/204", Allow200, 204, clean, ""},
		{"allow200/401", Allow200, 401, oracle.Medium, "refused a legitimate request"},
		{"allow200/403", Allow200, 403, oracle.Medium, "refused a legitimate request"},
		{"allow200/404", Allow200, 404, oracle.Medium, "refused a legitimate request"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := AuthOutcome(tc.status, `[{"name":"x"}]`, tc.expect, "some-defect", 7)

			if tc.want == clean {
				if len(got) != 0 {
					t.Fatalf("expected no finding, got %v", got)
				}
				return
			}
			if len(got) != 1 {
				t.Fatalf("expected exactly one finding, got %v", got)
			}
			if got[0].Severity != tc.want {
				t.Errorf("severity: got %v, want %v (detail=%q)", got[0].Severity, tc.want, got[0].Detail)
			}
			if got[0].Family != oracle.Auth {
				t.Errorf("family: got %v, want %v", got[0].Family, oracle.Auth)
			}
			if tc.detail != "" && !strings.Contains(got[0].Detail, tc.detail) {
				t.Errorf("detail %q must carry %q", got[0].Detail, tc.detail)
			}
		})
	}
}

// An Expectation the switch does not handle must be LOUD. It used to fall into the
// `default:` arm alongside Allow200 and grade clean, so the day a fifth constant is
// added the harness would pass every probe that declared it — silently, and in the one
// lane whose entire job is to refuse to miss things.
func TestAuthOutcome_AnUngradedExpectationIsNeverClean(t *testing.T) {
	for _, status := range []int{200, 401, 403, 500} {
		t.Run(fmt.Sprintf("status %d", status), func(t *testing.T) {
			got := AuthOutcome(status, "", Expectation(99), "some-defect", 7)
			if len(got) == 0 {
				t.Fatalf("an expectation the grader cannot read must never grade clean")
			}
			if got[0].Severity < oracle.High {
				t.Fatalf("a harness that cannot grade must fail the declared tier, got %v", got[0].Severity)
			}
		})
	}
}

func TestCrossTenantLeak(t *testing.T) {
	cases := []struct {
		name        string
		body        string
		foreignName string
		wantFinding bool
	}{
		{"foreign connector visible", `[{"name":"otk-baseline-7"}]`, "otk-baseline-7", true},
		{"foreign name absent", `[]`, "otk-baseline-7", false},
		// Exact match, not substring: "otk-run-1" must NOT match "otk-run-10".
		{"a substring is not a leak", `[{"name":"otk-run-10"}]`, "otk-run-1", false},
		// A non-JSON error page that echoes the foreign name is still caught.
		{"unparseable body falls back to substring",
			`<html>error: otk-baseline-7 not allowed</html>`, "otk-baseline-7", true},
		{"no foreign name to look for", `[{"name":"x"}]`, "", false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := CrossTenantLeak(tc.body, tc.foreignName, 7)
			if tc.wantFinding {
				if len(got) != 1 || got[0].Severity != oracle.Critical {
					t.Fatalf("expected one CRITICAL finding, got %v", got)
				}
				return
			}
			if len(got) != 0 {
				t.Fatalf("expected no finding, got %v", got)
			}
		})
	}
}
