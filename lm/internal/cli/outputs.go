package cli

import (
	"fmt"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var outputHeaders = []string{"INTEGRATION", "EVENT", "STATUS", "WHEN", "ERROR"}

// Raw timestamps on purpose: the CLI is the scriptable surface, the TUI is where the age is
// humanized. A delivery that never got a response has no status at all.
func outputRow(o client.Output) []string {
	status := "ok"
	if !o.Succeeded {
		status = "no response"
		if o.HTTPStatus != nil {
			status = fmt.Sprintf("%d", *o.HTTPStatus)
		}
	}
	return []string{o.Integration, o.EventType, status, o.AttemptedAt.Format(time.RFC3339), o.Error}
}

func newOutputsCmd() *cobra.Command {
	root := &cobra.Command{Use: "outputs", Short: "Inspect what the substrate sent out"}
	list := &cobra.Command{
		Use:   "list",
		Short: "List deliveries across every integration, failures first",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			limit, _ := cmd.Flags().GetInt("limit")
			items, err := c.OutputList(limit)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, outputHeaders, items, outputRow)
		},
	}
	list.Flags().Int("limit", 200, "max deliveries to list")
	root.AddCommand(list)
	return root
}
