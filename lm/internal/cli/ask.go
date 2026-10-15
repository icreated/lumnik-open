package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/ask"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

func newAskCmd() *cobra.Command {
	ask := &cobra.Command{
		Use:   "ask QUESTION",
		Short: "Ask your data a question in natural language (text-to-SQL over the fused hub)",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			question := strings.Join(args, " ")
			scope, _ := cmd.Flags().GetString("scope")
			lang, _ := cmd.Flags().GetString("lang")
			res, err := c.RagAsk(question, scope, client.AnswerLang(lang))
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			fmt.Fprintln(out, res.Answer)
			if res.DataAsOf != "" {
				fmt.Fprintf(out, "  data as of: %s (last completed run)\n", res.DataAsOf)
			}
			printDisputed(out, res.Disputed, res.DisputedTruncated)
			// Honesty: show the SQL that actually ran, so the answer is auditable.
			if res.SQL != "" {
				fmt.Fprintln(out)
				fmt.Fprintf(out, "  SQL: %s\n", res.SQL)
			}
			printOtherDoors(out, res.OtherDoors, scope)
			return nil
		},
	}
	ask.Flags().String("scope", "", "The métier scope whose fused data to query")
	ask.Flags().String("lang", "", "Two-letter answer language (default: from LC_ALL, LC_MESSAGES or LANG; none: the question's own)")
	return ask
}

// gestureFor maps a hub door token to the command THIS binary offers for it, or "" when it has
// none — an unknown token is silence, never printed raw. (The hub sends tokens rather than
// commands because the gesture differs per surface: the TUI names a page, the PWA a tab.)
func gestureFor(door, scope string) string {
	suffix := ""
	if scope != "" {
		suffix = " --scope " + scope
	}
	switch door {
	case ask.Lifecycle:
		return "lm chat" + suffix + "  — the declared lifecycle is retrieved there, not by SQL"
	case ask.Conflicts:
		return "lm conflicts list" + suffix + "  — where this scope has source conflicts or lookup ambiguities, row by row"
	default:
		return ""
	}
}

// printDisputed names the records this answer filtered on while a source disagrees with another (#874),
// before the SQL: it qualifies the answer itself.
func printDisputed(out io.Writer, disputed []client.Disputed, truncated bool) {
	if len(disputed) == 0 {
		return
	}
	fmt.Fprintln(out)
	fmt.Fprintln(out, "  disputed — a source disagrees on a value this answer filtered on:")
	for _, d := range disputed {
		verdict := "counted"
		if !d.Counted {
			verdict = "not counted"
		}
		var sources []string
		for _, s := range d.Sources {
			sources = append(sources, s.Src+": "+s.Display())
		}
		fmt.Fprintf(out, "    %s  %s = '%s'  %s  %s\n", d.Key, d.Column, d.Matched, verdict, strings.Join(sources, " · "))
	}
	if truncated {
		fmt.Fprintln(out, "    … and more — lm conflicts list")
	}
}

func printOtherDoors(out io.Writer, doors []string, scope string) {
	var lines []string
	for _, d := range doors {
		if g := gestureFor(d, scope); g != "" {
			lines = append(lines, g)
		}
	}
	if len(lines) == 0 {
		return
	}
	fmt.Fprintln(out)
	for _, l := range lines {
		fmt.Fprintf(out, "  try: %s\n", l)
	}
}
