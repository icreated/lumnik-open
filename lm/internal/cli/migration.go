package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

func newMigrationCmd() *cobra.Command {
	m := &cobra.Command{
		Use:   "migration",
		Short: "Flyway migration status",
		Long: "Flyway migration status per schema. The hub applies its migrations at every startup:\n" +
			"a pending migration applies when the hub next starts.",
	}

	m.AddCommand(&cobra.Command{
		Use:   "status",
		Short: "Show migration status per schema (pending ones apply when the hub next starts)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ss, err := c.MigrationStatus()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"SCHEMA", "VERSION", "PENDING", "LAST APPLIED"}, ss,
				func(s types.MigrationStatus) []string {
					return []string{s.Schema, s.CurrentVersion, fmt.Sprintf("%d", s.Pending),
						output.HumanTimeStrOr(s.LastApplied, "—")} // "—": never applied (#1149)
				})
		},
	})

	return m
}
