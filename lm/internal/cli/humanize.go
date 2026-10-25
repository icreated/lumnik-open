package cli

import (
	"fmt"
	"strconv"
	"time"
)

// humanizeAge renders how long ago t was, for the inbox WHEN column.
// Duration-based on purpose (no calendar math) — rung-1 simplicity.
func humanizeAge(t, now time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "just now"
	case d < time.Hour:
		return fmt.Sprintf("%dmin", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	case d < 48*time.Hour:
		return "yesterday"
	default:
		return fmt.Sprintf("%dd", int(d.Hours()/24))
	}
}

// optInt renders a nullable count; absent stays blank.
func optInt(v *int64) string {
	if v == nil {
		return ""
	}
	return strconv.FormatInt(*v, 10)
}

// optStr renders a nullable string (e.g. SearchIndexStatus.LiveGeneration when no projection-state
// row exists yet); absent stays blank rather than the zero value's own empty string, which reads
// identically to a genuinely empty one (code review finding).
func optStr(v *string) string {
	if v == nil {
		return ""
	}
	return *v
}
