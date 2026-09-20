package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/ask"
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
			res, err := c.RagAsk(question, scope)
			if err != nil {
				return err
			}
			cmd.Println(res.Answer)
			if res.DataAsOf != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "  data as of: %s (last completed run)\n", res.DataAsOf)
			}
			// Honesty: show the SQL that actually ran, so the answer is auditable.
			if res.SQL != "" {
				cmd.Println()
				fmt.Fprintf(cmd.OutOrStdout(), "  SQL: %s\n", res.SQL)
			}
			printOtherDoors(cmd, res.OtherDoors, scope)
			return nil
		},
	}
	ask.Flags().String("scope", "", "The métier scope whose fused data to query")
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
		return "lm conflicts list" + suffix + "  — where this scope's sources disagree, row by row"
	default:
		return ""
	}
}

func printOtherDoors(cmd *cobra.Command, doors []string, scope string) {
	var lines []string
	for _, d := range doors {
		if g := gestureFor(d, scope); g != "" {
			lines = append(lines, g)
		}
	}
	if len(lines) == 0 {
		return
	}
	cmd.Println()
	for _, l := range lines {
		fmt.Fprintf(cmd.OutOrStdout(), "  try: %s\n", l)
	}
}
