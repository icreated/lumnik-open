package checks

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestReconcileCounts_balanced_isClean(t *testing.T) {
	if f := ReconcileCounts(5, 3, 2, 2, testCaseID, 7); len(f) != 0 {
		t.Fatalf("balanced should be clean, got %v", f)
	}
}

func TestReconcileCounts_conservingWithSkips_isClean(t *testing.T) {
	// 10 in, 7 out, 3 skipped (all benign — no dlq) -> conserves, no finding.
	if f := ReconcileCounts(10, 7, 3, 0, testCaseID, 7); len(f) != 0 {
		t.Fatalf("conserving with skips>0 should be clean, got %v", f)
	}
}

func TestReconcileCounts_silentDrop_isHigh(t *testing.T) {
	f := ReconcileCounts(5, 2, 2, 0, testCaseID, 7) // 5 in, only 2 out + 2 skipped = 4 accounted -> 1 vanished
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity || f[0].Seed != 7 || f[0].CaseID != testCaseID {
		t.Fatalf("want one HIGH atomicity finding (seed 7), got %v", f)
	}
	detail := f[0].Detail
	for _, want := range []string{"in=5", "out=2", "skipped=2", "dlq=0"} {
		if !strings.Contains(detail, want) {
			t.Fatalf("detail %q must mention %q", detail, want)
		}
	}
}

func TestReconcileCounts_phantom_isHigh(t *testing.T) {
	f := ReconcileCounts(2, 3, 1, 0, testCaseID, 7) // 2 in, 3 out + 1 skipped = 4 accounted -> 2 phantom
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity || f[0].Seed != 7 {
		t.Fatalf("want one HIGH atomicity finding (seed 7), got %v", f)
	}
}

func TestReconcileCounts_dlqExceedsSkipped_isHigh1b(t *testing.T) {
	// in==out+skipped holds (Law 1 clean), but dlq(3) > skipped(2): a DLQ row the
	// skip counter never counted -> Law 1b violation.
	f := ReconcileCounts(5, 3, 2, 3, testCaseID, 7)
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity || f[0].Seed != 7 {
		t.Fatalf("want one HIGH atomicity finding (seed 7), got %v", f)
	}
	if !strings.Contains(f[0].Detail, "the DLQ holds rows the skip counter never counted") {
		t.Fatalf("detail must name Law 1b's meaning, got %q", f[0].Detail)
	}
}

func TestReconcileCounts_bothLawsViolated_returnsBoth(t *testing.T) {
	// in=5, out=1, skipped=1: 1+1=2 != 5 -> Law 1 violated.
	// dlq=3 > skipped=1 -> Law 1b also violated, independently.
	// The two laws are independent invariants, not classification branches:
	// both findings must be returned, not just the first one hit.
	f := ReconcileCounts(5, 1, 1, 3, testCaseID, 7)
	if len(f) != 2 {
		t.Fatalf("want exactly 2 findings (Law 1 + Law 1b), got %d: %v", len(f), f)
	}
	for _, finding := range f {
		if finding.Severity != oracle.High || finding.Family != oracle.Atomicity || finding.Seed != 7 || finding.CaseID != testCaseID {
			t.Fatalf("want HIGH atomicity finding (seed 7), got %v", finding)
		}
	}
	if !strings.Contains(f[0].Detail, "in=5") || !strings.Contains(f[0].Detail, "out=1") ||
		!strings.Contains(f[0].Detail, "skipped=1") || !strings.Contains(f[0].Detail, "dlq=3") {
		t.Fatalf("first finding must carry the Law-1 detail with all four numbers, got %q", f[0].Detail)
	}
	if !strings.Contains(f[1].Detail, "the DLQ holds rows the skip counter never counted") {
		t.Fatalf("second finding must carry the Law-1b message, got %q", f[1].Detail)
	}
}

func TestReconcileCounts_dlqEqualsSkipped_isClean(t *testing.T) {
	// dlq==skipped is legal: every skip was DLQ'd, none benign.
	if f := ReconcileCounts(5, 2, 3, 3, testCaseID, 7); len(f) != 0 {
		t.Fatalf("dlq==skipped should be clean, got %v", f)
	}
}

func TestReconcileLanded_match_isClean(t *testing.T) {
	if f := ReconcileLanded(3, 3, testCaseID, 7); len(f) != 0 {
		t.Fatalf("match should be clean, got %v", f)
	}
}

func TestReconcileLanded_divergence_isHigh(t *testing.T) {
	f := ReconcileLanded(3, 5, testCaseID, 7) // hub claims 3 written, DB holds 5
	if len(f) != 1 || f[0].Severity != oracle.High || f[0].Family != oracle.Atomicity || f[0].Seed != 7 {
		t.Fatalf("want one HIGH atomicity finding (seed 7), got %v", f)
	}
}

// TestReconcile_authoritativeCountSurvivesTruncation pins that ReconcileCounts
// runs on the run record's scalar fields (recordsSkipped, deadLetterCount),
// never on a derived count from a paginated listing. recordsSkipped is a
// single authoritative field on the run record — it was never subject to the
// `lm dlq list` page-cap — so at high N there is no line-counting substitute
// to fall back to: a caller that passes anything other than the run record's
// own fields is holding it wrong, and this test is the executable proof that
// the correct source (250 in every field) balances the books while an
// under-reported source (200) does not.
func TestReconcile_authoritativeCountSurvivesTruncation(t *testing.T) {
	const seed int64 = 42

	// Run-record fields, all 250 dead-lettered, none benign — conservation holds.
	if f := ReconcileCounts(250, 0, 250, 250, testCaseID, seed); len(f) != 0 {
		t.Fatalf("authoritative path: 250 in / 0 out / 250 skipped / 250 dlq must be clean; got %v", f)
	}

	// An under-reported recordsSkipped (as if sourced from something other than
	// the run record's own field) falsely signals 50 rows vanished → must be HIGH.
	f := ReconcileCounts(250, 0, 200, 200, testCaseID, seed)
	if len(f) != 1 {
		t.Fatalf("truncated path: want exactly 1 finding; got %d: %v", len(f), f)
	}
	if f[0].Severity != oracle.High {
		t.Fatalf("truncated path: want Severity=High; got %v", f[0].Severity)
	}
}
