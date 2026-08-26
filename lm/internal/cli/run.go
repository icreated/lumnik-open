package cli

import (
	"fmt"
	"strings"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

// filterRunsByConnector keeps only runs belonging to the given connector id.
func filterRunsByConnector(runs []types.Run, connectorID string) []types.Run {
	out := make([]types.Run, 0, len(runs))
	for _, r := range runs {
		if r.ConnectorID == connectorID {
			out = append(out, r)
		}
	}
	return out
}

func runRow(r types.Run) []string {
	started := ""
	if !r.StartedAt.IsZero() {
		started = r.StartedAt.Format(time.RFC3339)
	}
	ended := ""
	if r.EndedAt != nil {
		ended = r.EndedAt.Format(time.RFC3339)
	}
	return []string{
		shortID(r.ID), r.ConnectorLabel(), r.Status,
		fmt.Sprintf("%d", r.RecordsIn), fmt.Sprintf("%d", r.RecordsOut),
		fmt.Sprintf("%d", r.RecordsSkipped), fmt.Sprintf("%d", r.DeadLetterCount),
		started, ended,
	}
}

var runHeaders = []string{"ID", "CONNECTOR", "STATUS", "IN", "OUT", "SKIPPED", "DEAD-LETTERS", "STARTED", "ENDED"}

func newRunCmd() *cobra.Command {
	r := &cobra.Command{Use: "run", Short: "Inspect connector runs"}

	list := &cobra.Command{
		Use:   "list",
		Short: "List recent runs",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			limit, _ := cmd.Flags().GetInt("limit")
			runs, err := c.RunList(limit)
			if err != nil {
				return err
			}
			if conn, _ := cmd.Flags().GetString("connector"); conn != "" {
				id, err := resolveConnectorID(c, conn)
				if err != nil {
					return err
				}
				runs = filterRunsByConnector(runs, id)
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, runHeaders, runs, runRow)
		},
	}
	list.Flags().String("connector", "", "filter by connector name or id")
	list.Flags().Int("limit", 50, "max runs to list")
	r.AddCommand(list)

	r.AddCommand(&cobra.Command{
		Use:   "get ID",
		Short: "Show one run",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveRunID(c, args[0])
			if err != nil {
				return err
			}
			run, err := c.RunGet(id)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			// Single-element slice so json/yaml/table render uniformly; OTK parses [0].
			if err := output.Print(cmd.OutOrStdout(), format, runHeaders, []types.Run{run}, runRow); err != nil {
				return err
			}
			// Human-facing warning goes to STDERR so it never pollutes stdout for -o json/yaml
			// (machine consumers, incl. the OTK, parse stdout; a stderr note is safe for all formats).
			if run.DeadLetterCount > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"⚠ %d rows quarantined — see: lm dlq list --run %s\n",
					run.DeadLetterCount, run.ID); err != nil {
					return err
				}
			}
			// cell_errors counts CELLS a parse hook could not read and nulled — the row was
			// still written, so this is a lead, not a quarantine. Same stderr door as above.
			if run.CellErrors > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"⚠ %d cell(s) emptied by a parse hook that could not read the value\n",
					run.CellErrors); err != nil {
					return err
				}
			}
			// A terminal run's reason must reach the human — a mute Failed was one of
			// the first real test's silences. Same stderr contract as the alert above.
			// The label follows the STATUS: Cancelled runs carry a message too
			// ("cancelled by user"), and calling them failed would lie.
			if run.ErrorMessage != "" {
				note := "✖ run failed"
				if run.Status != "Failed" {
					note = "• run " + strings.ToLower(run.Status)
				}
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"%s: %s\n", note, run.ErrorMessage); err != nil {
					return err
				}
			}
			return nil
		},
	})
	return r
}
