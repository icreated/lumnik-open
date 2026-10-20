package cli

import (
	"fmt"
	"sort"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newDataCmd() *cobra.Command {
	d := &cobra.Command{Use: "data", Short: "Inspect ingested raw data"}

	d.AddCommand(&cobra.Command{
		Use:   "row HASH",
		Short: "Show the raw row matching a citation hash",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			row, err := c.DataRowGet(args[0])
			if err != nil {
				return err
			}
			keys := make([]string, 0, len(row))
			for k := range row {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			for _, k := range keys {
				fmt.Fprintf(cmd.OutOrStdout(), "%-24s %v\n", k+":", row[k])
			}
			return nil
		},
	})

	d.AddCommand(&cobra.Command{
		Use:   "tables",
		Short: "List dynamic tables (auto-created from ingested mappings)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ts, err := c.DataTables()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"MAPPING", "SCHEMA", "TABLE", "ROWS"}, ts,
				func(t client.DataTable) []string {
					return []string{t.MappingID, t.TableSchema, t.TableName, t.Rows()}
				})
		},
	})

	return d
}
