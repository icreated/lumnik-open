package cli

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui"
)

// defaultSkipPatterns hides the catalog and the hub's own tables from discover and the picker.
const defaultSkipPatterns = "pg_*,information_schema*,t_*"

// splitPatterns turns a comma-separated --skip-pattern value into trimmed, non-empty globs.
func splitPatterns(csv string) []string {
	var out []string
	for _, p := range strings.Split(csv, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

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
		Short: "Test a JDBC connection (password: prompted, or stdin when piped)",
		RunE: func(cmd *cobra.Command, args []string) error {
			// The context is resolved BEFORE the prompt: asking for a password and only then
			// saying "no context set" makes the caller type the secret twice.
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			pwd, err := readPassword(cmd, jdbcUser)
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
	addPasswordStdinFlag(cmd)
	_ = cmd.MarkFlagRequired("url")
	_ = cmd.MarkFlagRequired("user")
	return cmd
}

func newSourceJdbcDiscoverCmd() *cobra.Command {
	var jdbcURL, jdbcUser, jdbcSchema, jdbcSkipPattern, jdbcOutput string
	var jdbcIncludeViews bool
	cmd := &cobra.Command{
		Use:   "discover",
		Short: "List tables in a JDBC source (password: prompted, or stdin when piped)",
		RunE: func(cmd *cobra.Command, args []string) error {
			// The context is resolved BEFORE the prompt: asking for a password and only then
			// saying "no context set" makes the caller type the secret twice.
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			pwd, err := readPassword(cmd, jdbcUser)
			if err != nil {
				return err
			}
			schema := jdbcSchema
			if schema == "" {
				schema = "public"
			}
			skip := splitPatterns(jdbcSkipPattern)
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
	addPasswordStdinFlag(cmd)
	cmd.Flags().StringVar(&jdbcSchema, "schema", "public", "Source schema the tables live in")
	cmd.Flags().StringVar(&jdbcSkipPattern, "skip-pattern", defaultSkipPatterns, "Comma-separated glob patterns of tables to skip")
	cmd.Flags().BoolVar(&jdbcIncludeViews, "include-views", false, "Include views")
	cmd.Flags().StringVar(&jdbcOutput, "output", "table", "Output format: table|json")
	_ = cmd.MarkFlagRequired("url")
	_ = cmd.MarkFlagRequired("user")
	return cmd
}

func newSourceJdbcAddCmd() *cobra.Command {
	var jdbcURL, jdbcUser, jdbcSchema, jdbcSkipPattern, jdbcTables, jdbcScheduleCron string
	var jdbcInteractive bool
	cmd := &cobra.Command{
		Use:   "add <source-name>",
		Short: "Add a JDBC source — create one connector per table (password: prompted, or stdin when piped)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			sourceName := args[0]
			// The context is resolved BEFORE the prompt: asking for a password and only then
			// saying "no context set" makes the caller type the secret twice.
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			pwd, err := readPassword(cmd, jdbcUser)
			if err != nil {
				return err
			}

			var addTables []client.JdbcAddTable
			if jdbcInteractive {
				disc, err := c.JdbcDiscover(client.JdbcDiscoverRequest{
					URL: jdbcURL, User: jdbcUser, Password: pwd,
					Schema:       jdbcSchema,
					SkipPatterns: splitPatterns(jdbcSkipPattern),
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
					// mode is the hub's call: watermark when a column exists, full otherwise
					addTables = append(addTables, client.JdbcAddTable{Table: t.Name, Watermark: wm})
				}
			} else {
				for _, t := range strings.Split(jdbcTables, ",") {
					t = strings.TrimSpace(t)
					if t == "" {
						continue
					}
					addTables = append(addTables, client.JdbcAddTable{Table: t})
				}
			}

			resp, err := c.JdbcAdd(client.JdbcAddRequest{
				SourceName: sourceName, URL: jdbcURL, User: jdbcUser, Password: pwd, Schema: jdbcSchema,
				Tables: addTables, ScheduleCron: jdbcScheduleCron,
			})
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			fmt.Fprintf(w, "Created %d connectors:\n", len(resp.Created))
			for _, e := range resp.Created {
				fmt.Fprintf(w, "  • %s\n", e.ConnectorName)
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
	addPasswordStdinFlag(cmd)
	cmd.Flags().StringVar(&jdbcSchema, "schema", "public", "Source schema the tables live in")
	cmd.Flags().StringVar(&jdbcSkipPattern, "skip-pattern", defaultSkipPatterns, "Comma-separated glob patterns of tables to skip (--interactive)")
	cmd.Flags().StringVar(&jdbcTables, "tables", "", "Comma-separated list of tables")
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
			// The rows carry more than the three columns below (connectorId, lastRunAt,
			// rowsIngested), and -o is a persistent root flag: asked for data, hand back the
			// response whole. The human rendering stays byte-for-byte as it is — the
			// transcript in docs/connectors/jdbc.md is a real one.
			if format, _ := cmd.Flags().GetString("output"); format != "table" {
				return output.PrintObject(w, format, resp)
			}
			fmt.Fprintf(w, "%-30s %-40s %-15s\n", "TABLE", "CONNECTOR", "STATUS")
			for _, t := range resp.Tables {
				fmt.Fprintf(w, "%-30s %-40s %-15s\n", t.Table, t.ConnectorName, t.Status)
			}
			return nil
		},
	}
	return cmd
}

// addPasswordStdinFlag declares the piped form, spelled as docker and helm spell it
// (`docker login --password-stdin`, `helm registry login --password-stdin`). There is
// deliberately no --password: docker and helm both keep one and merely warn, but a secret on
// the command line is in `ps` and in the shell history before the command returns.
func addPasswordStdinFlag(cmd *cobra.Command) {
	cmd.Flags().Bool("password-stdin", false, "read the password from stdin instead of prompting")
}

// readPassword gets the source credential for the three commands that open a database themselves.
//
// A terminal is prompted, never echoed — this used to block with no prompt and the typing in
// clear. A pipe is read as before, and warned about when the caller did not declare it with
// --password-stdin: `lm source jdbc add … < list.txt` otherwise sends that file's first line to
// the hub as a password, in silence.
//
// The terminal test wins over the flag: --password-stdin says WHERE the password comes from, and
// a terminal is stdin — so the flag is honoured, just without putting the secret on the screen.
func readPassword(cmd *cobra.Command, user string) (string, error) {
	if tty, ok := terminalIn(cmd); ok {
		// --user is a required flag on all three commands, so the name is always there to show.
		return promptSecret(cmd, tty, "Password for "+user)
	}
	if declared, _ := cmd.Flags().GetBool("password-stdin"); !declared {
		fmt.Fprintln(cmd.ErrOrStderr(),
			"warning: password read from stdin; pass --password-stdin to declare it")
	}
	// The twin reader in integration.go — same line, same trim, one implementation.
	return readLineFrom(cmd.InOrStdin())
}
