package output

import "time"

// The two precisions actually used across lm and its TUI: table cells (compact) and
// single-item detail panes (the extra width is available, and matches the sub-second
// precision already used for run durations printed alongside them).
const (
	humanLayout        = "2006-01-02 15:04"
	humanLayoutSeconds = "2006-01-02 15:04:05"
)

// HumanTime renders a timestamp for table cells: compact, in the READER's timezone —
// the CLI genre's "locale" (k9s/kubectl show local time, never locale-language dates).
// A nil or zero time renders empty: absence stays visibly absent.
//
// This is the ONE place lm and its TUI convert a timestamp for a human to read. Every
// date column and detail line must render through this function or HumanTimeSeconds —
// never t.Format(...) directly — or it silently prints the hub's UTC clock dressed up
// in a friendly layout, which reads as correct and is wrong by the reader's UTC offset.
func HumanTime(t *time.Time) string { return humanFormat(t, humanLayout) }

// HumanTimeSeconds is HumanTime with second-level precision, for detail panes.
func HumanTimeSeconds(t *time.Time) string { return humanFormat(t, humanLayoutSeconds) }

func humanFormat(t *time.Time, layout string) string {
	if t == nil || t.IsZero() {
		return ""
	}
	return t.In(time.Local).Format(layout)
}

// HumanTimeStr renders an RFC3339 string the same way; anything unparseable is returned
// verbatim — a strange value from an older hub must stay readable, never become blank.
func HumanTimeStr(iso string) string {
	if iso == "" {
		return ""
	}
	t, err := time.Parse(time.RFC3339Nano, iso)
	if err != nil {
		return iso
	}
	return HumanTime(&t)
}
