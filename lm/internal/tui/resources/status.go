package resources

import "strings"

// RunStatusKind classifies a hub run/endpoint status string into the small,
// closed set of buckets every presentation site renders. The hub emits
// six values — Queued, Running, Completed, Partial, Failed, Cancelled — and
// this is the single place that vocabulary is decoded; per-site glyphs and
// colors stay local to each page.
//
// Partial arrived last, and its absence was not harmless: an unrecognized
// status falls to KindUnknown, which every site renders as a blank glyph in
// the default color — the rendering of "no run yet". A run that stopped below
// its target would have been LESS visible than a green one.
type RunStatusKind int

const (
	KindUnknown RunStatusKind = iota
	KindQueued
	KindRunning
	KindCompleted
	KindPartial
	KindFailed
	KindCancelled

	// kindCount bounds the enum for the inventory test, so a sixth kind cannot
	// be added without one of the render sites noticing. Keep it last.
	kindCount
)

// runStatusKind maps a hub status string (case-insensitive) to its
// RunStatusKind. Every unrecognized string — including "" — maps to
// KindUnknown.
func runStatusKind(s string) RunStatusKind {
	switch strings.ToUpper(s) {
	case "QUEUED":
		return KindQueued
	case "RUNNING":
		return KindRunning
	case "COMPLETED":
		return KindCompleted
	case "PARTIAL":
		return KindPartial
	case "FAILED":
		return KindFailed
	case "CANCELLED":
		return KindCancelled
	}
	return KindUnknown
}
