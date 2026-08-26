package cli

import (
	"runtime/debug"

	"github.com/spf13/cobra"
)

var (
	Version   = "dev"
	GitCommit = "unknown"
)

// buildStamp falls back to the VCS info Go embeds in every `go build` — two
// binaries on one machine must be tellable apart (rehearsal carnet #2).
func buildStamp() (string, string) {
	// Collect first, compose after: Settings order is not guaranteed, and a
	// dirty-before-revision ordering must not leave the commit as "unknown+dirty".
	commit, when := GitCommit, ""
	revision, dirty := "", false
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, kv := range info.Settings {
			switch kv.Key {
			case "vcs.revision":
				revision = kv.Value
			case "vcs.time":
				when = kv.Value
			case "vcs.modified":
				dirty = kv.Value == "true"
			}
		}
	}
	if commit == "unknown" && len(revision) >= 12 {
		commit = revision[:12]
	}
	if dirty && commit != "unknown" {
		commit += "+dirty"
	}
	return commit, when
}

func newVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print the lm binary version",
		Run: func(cmd *cobra.Command, args []string) {
			commit, when := buildStamp()
			if when != "" {
				cmd.Printf("lm version %s (commit %s, built from %s)\n", Version, commit, when)
			} else {
				cmd.Printf("lm version %s (commit %s)\n", Version, commit)
			}
		},
	}
}
