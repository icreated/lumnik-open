package cli

import (
	"fmt"

	"github.com/spf13/cobra"
)

func newSourceSchemaCmd() *cobra.Command {
	var scope string
	cmd := &cobra.Command{
		Use:   "schema",
		Short: "Show the discovered SchemaCard for a scope (tables, columns, business labels)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			card, err := c.RagSchema(scope)
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			fmt.Fprintf(out, "scope: %s\n", card.Scope)
			if len(card.Tables) == 0 {
				fmt.Fprintln(out, "(no tables — run 'lm source rediscover --scope "+scope+"')")
				return nil
			}
			for _, t := range card.Tables {
				fmt.Fprintf(out, "\n%s\n", t.Name)
				for _, col := range t.Columns {
					if col.Label != "" && col.Label != col.Name {
						fmt.Fprintf(out, "  %-24s %-14s — %s\n", col.Name, col.PgType, col.Label)
					} else {
						fmt.Fprintf(out, "  %-24s %s\n", col.Name, col.PgType)
					}
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&scope, "scope", "", "scope (tag) to show")
	_ = cmd.MarkFlagRequired("scope")
	return cmd
}

func newSourceRediscoverCmd() *cobra.Command {
	var scope string
	cmd := &cobra.Command{
		Use:   "rediscover",
		Short: "Rebuild + persist the SchemaCard for a scope (registry + best-effort AD labels)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			res, err := c.RagRediscover(scope)
			if err != nil {
				return err
			}
			enriched := "registry labels only"
			if res.Enriched {
				enriched = "enriched with AD labels"
			}
			fmt.Fprintf(cmd.OutOrStdout(),
				"✓ scope %s: %d tables, %d columns (%s, source=%s)\n",
				res.Scope, res.TableCount, res.ColumnCount, enriched, res.SourceType)
			return nil
		},
	}
	cmd.Flags().StringVar(&scope, "scope", "", "scope (tag) to (re)discover")
	_ = cmd.MarkFlagRequired("scope")
	return cmd
}
