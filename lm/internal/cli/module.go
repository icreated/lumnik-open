package cli

import (
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

func newModuleCmd() *cobra.Command {
	m := &cobra.Command{Use: "module", Short: "Installed business modules"}
	m.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List installed modules",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ms, err := c.ModuleList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "VERSION", "SCHEMA", "STATUS", "REASON"}, ms,
				func(m types.Module) []string {
					return []string{m.Name, m.Version, m.Schema, m.Status, m.Reason}
				})
		},
	})
	return m
}
