package cli

import (
	"fmt"
	"strconv"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

func newInboxCmd() *cobra.Command {
	var all bool
	cmd := &cobra.Command{
		Use:   "inbox",
		Short: "What needs you — the tenant's recent decision.* events, newest first",
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			entries, err := c.InboxList(pageLimit(cmd), all)
			if err != nil {
				return err
			}
			now := time.Now()
			return printPage(cmd,
				[]string{"ID", "QUAND", "PROCESS", "MESSAGE", "ENTITY_KEY"}, entries,
				func(e client.InboxEntry) []string { return inboxRow(e, now) })
		},
	}
	addPageLimitFlag(cmd, 50, client.InboxPageCap, "How many recent decisions to list (1-200)")
	cmd.Flags().BoolVar(&all, "all", false, "Include disposed (acked) decisions, marked [traité]")

	ack := &cobra.Command{
		Use:   "ack ID",
		Short: "Dispose of a decision — emits annotation.decision.acked (idempotent)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			id, err := strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be a number (from `lm inbox`): %s", args[0])
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.InboxAck(id); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "acked.")
			return nil
		},
	}
	cmd.AddCommand(ack)
	return cmd
}

// inboxRow maps one entry to the table row: ID first, MESSAGE suffixed [traité] when disposed.
func inboxRow(e client.InboxEntry, now time.Time) []string {
	msg := e.Message
	if e.Acked {
		msg += " [traité]"
	}
	return []string{strconv.FormatInt(e.ID, 10), quandOf(e.OccurredAt, now), e.Process, msg, e.EntityKey}
}

// quandOf renders the QUAND column: the humanized age of a decision's RFC3339 timestamp.
// RFC3339Nano is used because Java Instant.toString() includes fractional seconds when non-zero
// (Postgres NOW() has microsecond precision); it parses both "…Z" and "….123Z". An unparseable
// value falls back to the raw string.
func quandOf(occurredAt string, now time.Time) string {
	if ts, err := time.Parse(time.RFC3339Nano, occurredAt); err == nil {
		return humanizeAge(ts, now)
	}
	return occurredAt
}
