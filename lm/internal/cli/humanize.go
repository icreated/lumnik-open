package cli

import (
	"fmt"
	"time"
)

// humanizeAge renders how long ago t was, for the inbox QUAND column.
// Duration-based on purpose (no calendar math) — rung-1 simplicity.
func humanizeAge(t, now time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "à l'instant"
	case d < time.Hour:
		return fmt.Sprintf("%dmin", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	case d < 48*time.Hour:
		return "hier"
	default:
		return fmt.Sprintf("%dj", int(d.Hours()/24))
	}
}
