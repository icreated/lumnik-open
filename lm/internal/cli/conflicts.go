package cli

import (
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var conflictHeaders = []string{"ENTITY", "KEY", "COLUMNS", "SOURCES"}

func conflictRow(c client.Conflict) []string {
	return []string{c.Entity, c.Key, strings.Join(c.Columns, ","), strings.Join(c.SourceNames(), ",")}
}

func newConflictsCmd() *cobra.Command {
	root := &cobra.Command{Use: "conflicts", Short: "Inspect unresolved fusion conflicts"}
	list := &cobra.Command{
		Use:   "list",
		Short: "List fused-entity rows whose sources still disagree",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			limit, _ := cmd.Flags().GetInt("limit")
			items, err := c.ConflictList(limit)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, conflictHeaders, items, conflictRow)
		},
	}
	list.Flags().Int("limit", 200, "max conflicts to list")
	root.AddCommand(list)
	return root
}
