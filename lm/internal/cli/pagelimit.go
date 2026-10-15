package cli

import (
	"fmt"
	"strconv"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

// pageLimitCapAnnotation marks a command as a PAGE and records the cap its door applies.
// `--limit` alone cannot say it: two commands use the same flag name for a row budget handed to
// the engine (`lm csv ingest --limit`, `lm source rest run --limit`), which is not a page and has
// no boundary to announce. Going through addPageLimitFlag is the author saying which one it is.
const pageLimitCapAnnotation = "lm/page-limit-cap"

// addPageLimitFlag registers a listing command's --limit next to the cap the hub applies to it.
// Declaring both in one place is what keeps them from drifting apart, and what lets the inventory
// guard drive every paging command without a list of names kept by hand.
func addPageLimitFlag(cmd *cobra.Command, def, serverCap int, usage string) {
	cmd.Flags().Int("limit", def, usage)
	if cmd.Annotations == nil {
		cmd.Annotations = map[string]string{}
	}
	cmd.Annotations[pageLimitCapAnnotation] = strconv.Itoa(serverCap)
}

// pageLimit reads --limit folded into the range the door will honour, so the caller holds the
// EFFECTIVE limit: a nonsense 0 or negative becomes 1 (what the hub does with it), and anything
// above the cap becomes the cap — otherwise `--limit 5000` returns a full page that matches
// nothing, and the note below stays silent exactly where it is most needed.
func pageLimit(cmd *cobra.Command) int {
	n, _ := cmd.Flags().GetInt("limit")
	serverCap, _ := strconv.Atoi(cmd.Annotations[pageLimitCapAnnotation])
	if n < 1 {
		n = 1
	}
	// serverCap == 0 only for a command reading a limit it never declared through
	// addPageLimitFlag: clamp to nothing rather than to a guessed ceiling.
	if serverCap > 0 && n > serverCap {
		n = serverCap
	}
	return n
}

// printPage renders a page to stdout and, on stderr, says when it came back FULL — a boundary,
// not an end. Rendering and announcing live in one signature on purpose: a listing command cannot
// print a page without going through the thing that owns the announcement, so a seventh one
// cannot quietly forget it. (TestEveryPagingCommandAnnouncesAFullPage catches that too, but a
// guard that catches a mistake is weaker than a shape that cannot make it.)
//
// len == limit is a heuristic — a page that fits exactly looks the same from outside — which is
// why the wording says "may". The note goes to stderr, like every other human-facing note here,
// so stdout stays parseable for -o json/yaml and the OTK.
func printPage[T any](cmd *cobra.Command, headers []string, rows []T, toRow func(T) []string) error {
	if err := output.Print(cmd.OutOrStdout(), outputFormat(cmd), headers, rows, toRow); err != nil {
		return err
	}
	if limit := pageLimit(cmd); len(rows) == limit {
		fmt.Fprintf(cmd.ErrOrStderr(), "note: result hit the limit of %d — there may be more\n", limit)
	}
	return nil
}

// outputFormat is the global -o value.
func outputFormat(cmd *cobra.Command) string {
	format, _ := cmd.Flags().GetString("output")
	if format == "" {
		return "table"
	}
	return format
}
