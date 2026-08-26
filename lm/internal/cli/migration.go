package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

func newMigrationCmd() *cobra.Command {
	m := &cobra.Command{Use: "migration", Short: "Flyway migration status"}

	m.AddCommand(&cobra.Command{
		Use:   "status",
		Short: "Show migration status per schema",
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
				[]string{"SCHEMA", "VERSION", "PENDING"}, ss,
				func(s types.MigrationStatus) []string {
					return []string{s.Schema, s.CurrentVersion, fmt.Sprintf("%d", s.Pending)}
				})
		},
	})

	apply := &cobra.Command{
		Use:   "apply",
		Short: "Apply pending migrations for a schema",
		RunE: func(cmd *cobra.Command, args []string) error {
			schema, _ := cmd.Flags().GetString("schema")
			if schema == "" {
				return cmd.Help()
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.MigrationApply(schema); err != nil {
				return err
			}
			cmd.Println("migrations applied for schema:", schema)
			return nil
		},
	}
	apply.Flags().String("schema", "", "Schema name (core, identity, ...)")
	m.AddCommand(apply)

	return m
}
