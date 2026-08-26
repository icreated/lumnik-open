package resources

import "strings"

// RunStatusKind classifies a hub run/endpoint status string into the small,
// closed set of buckets every presentation site renders. The hub emits
// exactly four values — Running, Completed, Failed, Cancelled — and this is
// the single place that vocabulary is decoded; per-site glyphs and colors
// stay local to each page.
type RunStatusKind int

const (
	KindUnknown RunStatusKind = iota
	KindRunning
	KindCompleted
	KindFailed
	KindCancelled
)

// runStatusKind maps a hub status string (case-insensitive) to its
// RunStatusKind. Every unrecognized string — including "" — maps to
// KindUnknown.
func runStatusKind(s string) RunStatusKind {
	switch strings.ToUpper(s) {
	case "RUNNING":
		return KindRunning
	case "COMPLETED":
		return KindCompleted
	case "FAILED":
		return KindFailed
	case "CANCELLED":
		return KindCancelled
	}
	return KindUnknown
}
