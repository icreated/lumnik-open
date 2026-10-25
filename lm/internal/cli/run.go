package cli

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"time"

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

// runColumns adds EVALUATED (#1351) only when the hub answered for some run — see types.AnyEvaluated.
func runColumns(runs []types.Run) ([]string, func(types.Run) []string) {
	if !types.AnyEvaluated(runs) {
		return runHeaders, runRow
	}
	return append(slices.Clone(runHeaders), "EVALUATED"), func(r types.Run) []string {
		return append(runRow(r), r.EvaluatedLabel())
	}
}

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
			headers, row := runColumns(runs)
			return printPage(cmd, headers, runs, row)
		},
	}
	list.Flags().String("connector", "", "filter by connector name or id")
	addPageLimitFlag(list, 50, client.RunsPageCap, "max runs to list")
	r.AddCommand(list)

	var wait waitFlags
	get := &cobra.Command{
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
			if wait.wait {
				ctx, stop := interruptible(cmd.Context())
				defer stop()
				if run, err = waitForEvaluated(ctx, c, run, wait); err != nil {
					return err
				}
			}
			format, _ := cmd.Flags().GetString("output")
			// Single-element slice so json/yaml/table render uniformly; OTK parses [0].
			runs := []types.Run{run}
			headers, row := runColumns(runs)
			if err := output.Print(cmd.OutOrStdout(), format, headers, runs, row); err != nil {
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
			// cell_errors counts CELLS a hook did not deliver as declared. FIVE shapes: EMPTIED (a
			// parse hook could not read the value), LEFT AS IT ARRIVED (fix-mojibake declined to
			// repair it; split left it in place with some `into` columns unfilled), or REPLACED by
			// a value the source never sent (to-uuid on-invalid: generate), or OVERWRITTEN by a value
			// it did send from another column (map-columns renaming onto a name the row already had
			// - the only shape produced outside the cell tier), or DECLARED UNREADABLE by an
			// operator's Kotlin script (`unreadable`, #1432), the cells as the script left them.
			// The row was written in all five, so this is a lead, not a quarantine - and the wording
			// must not claim any one shape for all of them. A PR that adds a counting site extends
			// this list and the one in docs/connectors/transformers.md.
			if run.CellErrors > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"⚠ %d cell(s) a hook did not deliver as declared — the rows were still written\n",
					run.CellErrors); err != nil {
					return err
				}
			}
			// A table that shrank must say why: rows a csv file no longer holds, removed by a run that
			// read the whole file (#1195), or the older versions of a key, removed by a jdbc table's
			// first keyed run (#1196). Outside the IN/OUT/SKIPPED identity, so a note.
			if run.RecordsRemoved > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"• %d row(s) removed — the source no longer holds them\n",
					run.RecordsRemoved); err != nil {
					return err
				}
			}
			// IN 0 because the file had not changed is not IN 0 because it was empty (#1371).
			if run.FilesUnchanged > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"• %d file(s) unchanged since they were last read — not read again\n",
					run.FilesUnchanged); err != nil {
					return err
				}
			}
			// A drop folder that is not being emptied must not be a WARN in the hub's log only (#1382).
			if run.FilesNotArchived > 0 {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(),
					"• %d file(s) read but not archived — still in the source folder (after_process failed; see the hub log)\n",
					run.FilesNotArchived); err != nil {
					return err
				}
			}
			// Which version of an operator's script produced these rows (#1430): the fingerprint `lm hook
			// scripts` shows, one line per version — two when the script was replaced mid-run.
			for _, s := range run.Scripts {
				if _, err := fmt.Fprintf(cmd.ErrOrStderr(), "• script %s  sha256 %s\n", s.Name, s.SHA256); err != nil {
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
	}
	wait.registerAs(get, "wait-evaluated",
		"kind:Process has evaluated the rows the run wrote (exit 1 if the hub cannot say)")
	r.AddCommand(get)

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

// waitForEvaluated polls the run until the hub says kind:Process has evaluated its rows (#1353). A
// null answer ends the wait with an error rather than exit 0: it is the hub saying it cannot tell —
// no enabled Process, processes changed since the run started, a run from before it recorded it —
// and a script reading exit 0 as "evaluated" would read an empty inbox as "no decision".
func waitForEvaluated(ctx context.Context, c *client.Client, run types.Run, w waitFlags) (types.Run, error) {
	waitCtx, cancel, interval := w.deadline(ctx)
	defer cancel()
	timer := time.NewTimer(interval)
	defer timer.Stop()
	for {
		switch {
		case run.Evaluated == nil:
			return run, fmt.Errorf("the hub cannot say whether kind:Process evaluated run %s: no enabled Process, "+
				"its processes changed since the run started, or the run predates the hub recording it", run.ID)
		case *run.Evaluated:
			return run, nil
		}
		timer.Reset(interval)
		select {
		case <-waitCtx.Done():
			if ctx.Err() != nil {
				return run, fmt.Errorf("stopped waiting — run %s is not evaluated yet", run.ID)
			}
			return run, fmt.Errorf("gave up waiting after %s — kind:Process has not evaluated run %s yet", w.timeout, run.ID)
		case <-timer.C:
		}
		next, err := c.RunGetCtx(waitCtx, run.ID)
		if waitCtx.Err() != nil {
			continue // the select above says which of the two ended the wait
		}
		if err != nil {
			return run, fmt.Errorf("lost track of run %s: %w", run.ID, err)
		}
		run = next
	}
}
