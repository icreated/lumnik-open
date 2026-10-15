package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

func runRow(r types.Run) []string {
	return []string{
		shortID(r.ID), r.ConnectorLabel(), r.EndpointLabel(), r.Status,
		fmt.Sprintf("%d", r.RecordsIn), fmt.Sprintf("%d", r.RecordsOut),
		fmt.Sprintf("%d", r.RecordsSkipped), fmt.Sprintf("%d", r.DeadLetterCount),
		output.HumanTime(r.StartedAt), output.HumanTime(r.EndedAt),
	}
}

// ENDPOINT sits next to CONNECTOR because it is the second half of the same address: a connector
// writes one run row per endpoint (`lm endpoint reset-cursor <connector>/<endpoint>` addresses the
// pair), so without it a two-table connector's rows read identically.
var runHeaders = []string{"ID", "CONNECTOR", "ENDPOINT", "STATUS", "IN", "OUT", "SKIPPED", "DEAD-LETTERS", "STARTED", "ENDED"}

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
			conn, _ := cmd.Flags().GetString("connector")
			connectorID, err := resolveConnectorFilter(c, conn)
			if err != nil {
				return err
			}
			runs, err := c.RunList(connectorID, "", pageLimit(cmd))
			if err != nil {
				return err
			}
			return printPage(cmd, runHeaders, runs, runRow)
		},
	}
	list.Flags().String("connector", "", "filter by connector name or id")
	addPageLimitFlag(list, 50, client.RunsPageCap, "max runs to list")
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
			// cell_errors counts CELLS a hook did not deliver as declared. FOUR shapes: EMPTIED (a
			// parse hook could not read the value), LEFT AS IT ARRIVED (fix-mojibake declined to
			// repair it; split left it in place with some `into` columns unfilled), or REPLACED by
			// a value the source never sent (to-uuid on-invalid: generate), or OVERWRITTEN by a value
			// it did send from another column (map-columns renaming onto a name the row already had
			// - the only shape produced outside the cell tier).
			// The row was written in all four, so this is a lead, not a quarantine - and the wording
			// must not claim any one shape for all of them. A PR that adds a counting site extends
			// this list and the one in docs/connectors/transformers.md.
			if run.CellErrors > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"⚠ %d cell(s) a hook did not deliver as declared — the rows were still written\n",
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

	r.AddCommand(&cobra.Command{
		Use:   "cancel ID",
		Short: "Request cancellation of an in-flight run",
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
			status, err := c.RunCancel(id)
			if err != nil {
				return err
			}
			if status == "cancelled" {
				// A queued run was ended on the spot: there is no chunk to wait for.
				_, err = fmt.Fprintf(cmd.ErrOrStderr(),
					"• cancelled — the run never started; re-read with: lm run get %s\n", id)
				return err
			}
			// The 202 is a request recorded, not a run stopped — the run ends after the
			// current chunk at the earliest. This is not a query result, so — same stdout/
			// stderr contract as the alerts above — it goes to stderr, keeping stdout clean
			// for every output format and machine consumer (incl. the OTK).
			_, err = fmt.Fprintf(cmd.ErrOrStderr(),
				"• cancellation requested — the run ends after the current chunk; re-read with: lm run get %s\n", id)
			return err
		},
	})
	return r
}
