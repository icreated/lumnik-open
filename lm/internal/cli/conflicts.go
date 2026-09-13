package cli

import (
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
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
			items, err := c.ConflictList(pageLimit(cmd))
			if err != nil {
				return err
			}
			return printPage(cmd, conflictHeaders, items, conflictRow)
		},
	}
	addPageLimitFlag(list, 200, client.ConflictsPageCap, "max conflicts to list")
	root.AddCommand(list)
	return root
}
