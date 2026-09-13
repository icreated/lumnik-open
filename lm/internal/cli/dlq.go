package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

// resolveDlqFilters reads --connector/--run and turns a name or abbreviated id into the full id the
// hub's UUID-typed filters require, so `dlq list --run <8 chars>` and `--connector NAME` work like
// their run/connector siblings instead of failing "invalid UUID". Empty flags stay empty (no filter).
func resolveDlqFilters(c *client.Client, cmd *cobra.Command) (connectorID, runID string, err error) {
	connectorID, _ = cmd.Flags().GetString("connector")
	runID, _ = cmd.Flags().GetString("run")
	// A well-formed UUID passes through unchanged: it keeps a full id filtering even when it names a
	// since-deleted connector whose dead-letters outlive it, and skips a needless connector list.
	// Validate the SHAPE, not just the length — connector names carry no pattern constraint, so a
	// 36-character name must still resolve rather than be mistaken for an id and forwarded raw.
	if connectorID, err = resolveConnectorFilter(c, connectorID); err != nil {
		return "", "", err
	}
	if runID != "" {
		if runID, err = resolveRunID(c, runID); err != nil {
			return "", "", err
		}
	}
	return connectorID, runID, nil
}

func newDlqCmd() *cobra.Command {
	d := &cobra.Command{Use: "dlq", Short: "Dead letter queue operations"}

	listCmd := &cobra.Command{
		Use:   "list",
		Short: "List dead letter entries",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, run, err := resolveDlqFilters(c, cmd)
			if err != nil {
				return err
			}
			items, err := c.DlqList(conn, run, pageLimit(cmd))
			if err != nil {
				return err
			}
			return printPage(cmd,
				[]string{"ID", "CONNECTOR", "RUN", "REASON", "ATTEMPTS", "MESSAGE"}, items,
				func(d types.DeadLetter) []string {
					return []string{
						shortID(d.ID), d.ConnectorLabel(), shortID(d.RunID),
						d.SkipReason, fmt.Sprintf("%d", d.Attempts),
						output.Truncate(d.ErrorMessage, output.DlqMessageWidth),
					}
				})
		},
	}
	addPageLimitFlag(listCmd, 100, client.DlqPageCap, "Maximum number of entries to return")
	d.AddCommand(listCmd)

	d.AddCommand(&cobra.Command{
		Use:  "discard ID",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, run, err := resolveDlqFilters(c, cmd)
			if err != nil {
				return err
			}
			id, err := resolveDlqID(c, conn, run, args[0])
			if err != nil {
				return err
			}
			if err := c.DlqDiscard(id); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "discarded:", id)
			return nil
		},
	})

	var newRunID string
	replayed := &cobra.Command{
		Use:  "replayed ID",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, run, err := resolveDlqFilters(c, cmd)
			if err != nil {
				return err
			}
			id, err := resolveDlqID(c, conn, run, args[0])
			if err != nil {
				return err
			}
			if err := c.DlqReplayed(id, newRunID); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "marked replayed:", id)
			return nil
		},
	}
	replayed.Flags().StringVar(&newRunID, "new-run-id", "",
		"run that replayed this entry (rides the hub's optional newRunId link)")
	d.AddCommand(replayed)

	d.AddCommand(&cobra.Command{
		Use:   "get ID",
		Short: "Show one dead-letter entry (reason, detail, offending row)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, run, err := resolveDlqFilters(c, cmd)
			if err != nil {
				return err
			}
			id, err := resolveDlqID(c, conn, run, args[0])
			if err != nil {
				return err
			}
			dl, err := c.DlqGet(id)
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			fmt.Fprintf(w, "ID:        %s\n", dl.ID)
			fmt.Fprintf(w, "Connector: %s\n", dl.ConnectorNameAndID())
			fmt.Fprintf(w, "Run:       %s\n", dl.RunID)
			fmt.Fprintf(w, "Reason:    %s\n", dl.SkipReason)
			fmt.Fprintf(w, "Attempts:  %d\n", dl.Attempts)
			fmt.Fprintf(w, "Message:   %s\n", dl.ErrorMessage)
			fmt.Fprintf(w, "Detail:    %s\n", dl.SkipDetail)
			fmt.Fprintf(w, "Payload:   %s\n", dl.Payload)
			return nil
		},
	})

	d.PersistentFlags().String("connector", "", "Filter by connector ID")
	d.PersistentFlags().String("run", "", "Filter by run ID")
	return d
}

func shortID(s string) string {
	if len(s) > 8 {
		return s[:8]
	}
	return s
}
