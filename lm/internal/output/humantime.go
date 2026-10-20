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

// HumanTimeStrOr is HumanTimeStr with a stand-in for an absent timestamp, for a column where
// absence has a meaning of its own — a migration never applied reads "—" (#1149).
func HumanTimeStrOr(iso, absent string) string {
	if s := HumanTimeStr(iso); s != "" {
		return s
	}
	return absent
}

// Truncate shortens s to n CHARACTERS, appending an ellipsis when anything was cut.
//
// Runes, not bytes: the strings that reach here are hub messages, and a hub message carries em
// dashes, accents and whatever a source called its columns (`MONTANT_€` is a real one from the
// kafka bench). A byte slice landing inside a multi-byte rune printed half of it — a `dlq list`
// row was the first place it showed (#498).
//
// Here beside HumanTime for the same reason that one lives here: lm and its TUI render the same
// hub strings for the same human, and a second copy of a rule like this drifts. The TUI had a
// hand-rolled `msg[:60]` on the very same dead-letter message.
func Truncate(s string, n int) string {
	if len(s) <= n {
		return s // byte length <= n implies rune count <= n: nothing to cut, nothing to allocate
	}
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

// DlqMessageWidth is how many characters of a dead-letter message a list row shows. One number,
// because `lm dlq list` and the TUI's :dlq table show the same column of the same string — and
// because it is what decides which half of a message survives: hub messages are written
// mechanism-first for exactly this budget (see KafkaItemReader.tombstoneMessage).
const DlqMessageWidth = 60
