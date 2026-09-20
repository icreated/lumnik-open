// Package ask holds the door-token vocabulary AskResult.otherDoors carries — the single Go
// source `lm ask` and the TUI's :ask page reference, instead of each hardcoding the same literal
// strings independently (#522).
package ask

const (
	Lifecycle = "lifecycle"
	Conflicts = "conflicts"
)

// All is the vocabulary this Go binary recognises — not "has a gesture for": the TUI
// deliberately renders nothing for Lifecycle while still needing to tell it apart from an
// unmapped token sent by a newer hub.
var All = []string{Lifecycle, Conflicts}
