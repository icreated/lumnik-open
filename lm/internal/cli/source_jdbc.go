package cli

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui"
)

func newSourceJdbcCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "jdbc",
		Short: "JDBC source connector commands",
	}
	cmd.AddCommand(newSourceJdbcTestCmd())
	cmd.AddCommand(newSourceJdbcDiscoverCmd())
	cmd.AddCommand(newSourceJdbcAddCmd())
	cmd.AddCommand(newSourceJdbcListTablesCmd())
	return cmd
}

func newSourceJdbcTestCmd() *cobra.Command {
	var jdbcURL, jdbcUser string
	cmd := &cobra.Command{
		Use:   "test",
		Short: "Test a JDBC connection (password read from stdin)",
		RunE: func(cmd *cobra.Command, args []string) error {
			pwd, err := readPasswordStdin()
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.JdbcTest(client.JdbcTestRequest{URL: jdbcURL, User: jdbcUser, Password: pwd})
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "OK       : %v\nDialect  : %s\nVersion  : %s\nLatency  : %d ms\nSchemas  : %s\n",
				resp.OK, resp.Dialect, resp.Version, resp.LatencyMs, strings.Join(resp.Schemas, ", "))
			return nil
		},
	}
	cmd.Flags().StringVar(&jdbcURL, "url", "", "JDBC URL")
	cmd.Flags().StringVar(&jdbcUser, "user", "", "DB user")
	_ = cmd.MarkFlagRequired("url")
	_ = cmd.MarkFlagRequired("user")
	return cmd
}

func newSourceJdbcDiscoverCmd() *cobra.Command {
	var jdbcURL, jdbcUser, jdbcSchema, jdbcSkipPattern, jdbcOutput string
	var jdbcIncludeViews bool
	cmd := &cobra.Command{
		Use:   "discover",
		Short: "List tables in a JDBC source (password read from stdin)",
		RunE: func(cmd *cobra.Command, args []string) error {
			pwd, err := readPasswordStdin()
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			schema := jdbcSchema
			if schema == "" {
				schema = "public"
			}
			skip := strings.Split(jdbcSkipPattern, ",")
			resp, err := c.JdbcDiscover(client.JdbcDiscoverRequest{
				URL: jdbcURL, User: jdbcUser, Password: pwd,
				Schema: schema, SkipPatterns: skip, IncludeViews: jdbcIncludeViews,
			})
			if err != nil {
				return err
			}
			if jdbcOutput == "json" {
				return json.NewEncoder(machineOut(cmd)).Encode(resp)
			}
			w := cmd.OutOrStdout()
			fmt.Fprintf(w, "%-10s %-25s %10s %5s %-20s %-15s\n", "SCHEMA", "NAME", "ROWS", "COLS", "PK", "WATERMARK")
			for _, t := range resp.Tables {
				wm := "-"
				if t.SuggestedWatermark != nil {
					wm = *t.SuggestedWatermark
				}
				fmt.Fprintf(w, "%-10s %-25s %10d %5d %-20s %-15s\n",
					t.Schema, t.Name, t.RowEstimate, len(t.Columns),
					strings.Join(t.PrimaryKey, ","), wm)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&jdbcURL, "url", "", "JDBC URL")
	cmd.Flags().StringVar(&jdbcUser, "user", "", "DB user")
	cmd.Flags().StringVar(&jdbcSchema, "schema", "public", "Source schema")
	cmd.Flags().StringVar(&jdbcSkipPattern, "skip-pattern", "pg_*,information_schema*,t_*", "Comma-separated glob patterns")
	cmd.Flags().BoolVar(&jdbcIncludeViews, "include-views", false, "Include views")
	cmd.Flags().StringVar(&jdbcOutput, "output", "table", "Output format: table|json")
	_ = cmd.MarkFlagRequired("url")
	_ = cmd.MarkFlagRequired("user")
	return cmd
}

func newSourceJdbcAddCmd() *cobra.Command {
	var jdbcURL, jdbcUser, jdbcTables, jdbcTemplateMode, jdbcScheduleCron string
	var jdbcInteractive bool
	cmd := &cobra.Command{
		Use:   "add <source-name>",
		Short: "Add a JDBC source — create connectors and mappings (password read from stdin)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			sourceName := args[0]
			pwd, err := readPasswordStdin()
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}

			var addTables []client.JdbcAddTable
			if jdbcInteractive {
				disc, err := c.JdbcDiscover(client.JdbcDiscoverRequest{
					URL: jdbcURL, User: jdbcUser, Password: pwd,
					Schema:       "public",
					SkipPatterns: []string{"pg_*", "information_schema*", "t_*", "ad_*"},
				})
				if err != nil {
					return err
				}
				selected, err := tui.PickTables(disc.Tables)
				if err != nil {
					return err
				}
				if len(selected) == 0 {
					fmt.Fprintln(cmd.OutOrStdout(), "No tables selected — aborted.")
					return nil
				}
				for _, t := range selected {
					wm := ""
					if t.SuggestedWatermark != nil {
						wm = *t.SuggestedWatermark
					}
					addTables = append(addTables, client.JdbcAddTable{Table: t.Name, Watermark: wm, Mode: "watermark"})
				}
			} else {
				for _, t := range strings.Split(jdbcTables, ",") {
					t = strings.TrimSpace(t)
					if t == "" {
						continue
					}
					addTables = append(addTables, client.JdbcAddTable{Table: t, Mode: "watermark"})
				}
			}

			resp, err := c.JdbcAdd(client.JdbcAddRequest{
				SourceName: sourceName, URL: jdbcURL, User: jdbcUser, Password: pwd,
				Tables: addTables, TemplateMode: jdbcTemplateMode, ScheduleCron: jdbcScheduleCron,
			})
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			fmt.Fprintf(w, "Created %d connectors:\n", len(resp.Created))
			for _, e := range resp.Created {
				fmt.Fprintf(w, "  • %s  (mapping %s)\n", e.ConnectorName, e.MappingName)
			}
			if len(resp.Skipped) > 0 {
				fmt.Fprintf(w, "Skipped %d (already exist):\n", len(resp.Skipped))
				for _, e := range resp.Skipped {
					fmt.Fprintf(w, "  • %s\n", e.ConnectorName)
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&jdbcURL, "url", "", "JDBC URL")
	cmd.Flags().StringVar(&jdbcUser, "user", "", "DB user")
	cmd.Flags().StringVar(&jdbcTables, "tables", "", "Comma-separated list of tables")
	cmd.Flags().StringVar(&jdbcTemplateMode, "template-mode", "llm-first", "llm-first|heuristic-only|dump-1-1")
	cmd.Flags().StringVar(&jdbcScheduleCron, "schedule-cron", "", "Quartz cron expression")
	cmd.Flags().BoolVar(&jdbcInteractive, "interactive", false, "Open TUI table picker")
	_ = cmd.MarkFlagRequired("url")
	_ = cmd.MarkFlagRequired("user")
	return cmd
}

func newSourceJdbcListTablesCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "list-tables <source-name>",
		Short: "List tables registered under a JDBC source",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.JdbcListTables(args[0])
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			fmt.Fprintf(w, "%-30s %-40s %-15s\n", "TABLE", "CONNECTOR", "STATUS")
			for _, t := range resp.Tables {
				fmt.Fprintf(w, "%-30s %-40s %-15s\n", t.Table, t.ConnectorName, t.Status)
			}
			return nil
		},
	}
	return cmd
}

func readPasswordStdin() (string, error) {
	r := bufio.NewReader(os.Stdin)
	line, err := r.ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}
