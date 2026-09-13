package checks

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// ReconcileCounts enforces the ingestion conservation law in two parts:
//
// Law 1: recordsIn == recordsOut + recordsSkipped — every source row is either
// written or skipped, with nothing vanishing silently (in > out+skipped) or
// appearing from nowhere (in < out+skipped). Violation is HIGH.
//
// Law 1b: dlqCount <= recordsSkipped — the DLQ is a subset of skipped, never
// more (dlq ⊆ skipped). It is not equality: deadLetterCount excludes benign
// skip reasons (Duplicate/PolicyExcluded, per SkipReason.BENIGN_NAMES — the
// one home the hub keeps them in, read by countByRun, ChunkExecutor and
// DeadLetterCapture alike), so a benign mapping drop is
// skipped but never dead-lettered. A violation here (dlq > skipped) means the
// DLQ holds rows the skip counter never counted. HIGH.
//
// Both recordsSkipped and dlqCount must come from `lm run get`'s authoritative
// fields — never from counting `lm dlq list` lines, which are page-capped and
// would under-report at high N.
func ReconcileCounts(recordsIn, recordsOut, recordsSkipped, dlqCount int, seed int64) []oracle.Finding {
	var findings []oracle.Finding
	if recordsIn != recordsOut+recordsSkipped {
		findings = append(findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
			Detail: fmt.Sprintf("ingestion did not conserve rows: in=%d, out=%d, skipped=%d, dlq=%d (out+skipped=%d)",
				recordsIn, recordsOut, recordsSkipped, dlqCount, recordsOut+recordsSkipped),
		})
	}
	if dlqCount > recordsSkipped {
		findings = append(findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
			Detail: fmt.Sprintf("the DLQ holds rows the skip counter never counted: dlq=%d > skipped=%d",
				dlqCount, recordsSkipped),
		})
	}
	return findings
}

// ReconcileLanded is Law 2: the hub's reported recordsOut must match the rows the
// DB actually holds (counted via the non-owner psql role). A mismatch means the
// hub lied about what it persisted — the data-path analog of the v2 Divergence
// check. HIGH. Callers gate this on dbLive.
func ReconcileLanded(recordsOut, tableRows int, seed int64) []oracle.Finding {
	if recordsOut == tableRows {
		return nil
	}
	return []oracle.Finding{{
		Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
		Detail: fmt.Sprintf("hub reported %d row(s) written but the DB holds %d",
			recordsOut, tableRows),
	}}
}
