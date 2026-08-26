package cli

import (
	"fmt"
	"strings"

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
			return nil
		},
	}
	ask.Flags().String("scope", "", "The métier scope whose fused data to query")
	return ask
}
