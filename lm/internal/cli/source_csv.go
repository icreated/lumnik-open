package cli

import "github.com/spf13/cobra"

func newSourceCsvCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "csv",
		Short: "CSV source connector operations",
	}
	cmd.AddCommand(newCsvIngestCmd()) // reuses same handler
	return cmd
}
