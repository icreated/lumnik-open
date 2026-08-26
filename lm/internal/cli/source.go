package cli

import "github.com/spf13/cobra"

func newSourceCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "source",
		Short: "Manage data sources (jdbc, csv, ...)",
	}
	cmd.AddCommand(newSourceJdbcCmd())
	cmd.AddCommand(newSourceRestCmd())
	cmd.AddCommand(newSourceCsvCmd())
	cmd.AddCommand(newSourceSchemaCmd())
	cmd.AddCommand(newSourceRediscoverCmd())
	return cmd
}
