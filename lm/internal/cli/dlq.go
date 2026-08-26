package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
)

// dlqServerLimitCap mirrors DlqAdminResource's server-side clamp (max 200); the client
// clamps to it so the truncation hint compares len against the EFFECTIVE limit.
const dlqServerLimitCap = 200

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
			conn, _ := cmd.Flags().GetString("connector")
			run, _ := cmd.Flags().GetString("run")
			limit, _ := cmd.Flags().GetInt("limit")
			// Clamp to the hub's own [1, dlqServerLimitCap] range so the client and server behave
			// identically — a nonsense --limit 0/negative becomes 1 (forwarded), not the server default.
			effectiveLimit := limit
			if effectiveLimit < 1 {
				effectiveLimit = 1
			}
			if effectiveLimit > dlqServerLimitCap {
				effectiveLimit = dlqServerLimitCap
			}
			items, err := c.DlqList(conn, run, effectiveLimit)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			if err := output.Print(cmd.OutOrStdout(), format,
				[]string{"ID", "CONNECTOR", "RUN", "REASON", "ATTEMPTS", "MESSAGE"}, items,
				func(d types.DeadLetter) []string {
					return []string{
						shortID(d.ID), shortID(d.ConnectorID), shortID(d.RunID),
						d.SkipReason, fmt.Sprintf("%d", d.Attempts), truncate(d.ErrorMessage, 60),
					}
				}); err != nil {
				return err
			}
			if effectiveLimit > 0 && len(items) == effectiveLimit {
				fmt.Fprintf(cmd.ErrOrStderr(), "note: result hit the limit of %d — there may be more\n", effectiveLimit)
			}
			return nil
		},
	}
	listCmd.Flags().Int("limit", 100, "Maximum number of entries to return")
	d.AddCommand(listCmd)

	d.AddCommand(&cobra.Command{
		Use:  "discard ID",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, _ := cmd.Flags().GetString("connector")
			run, _ := cmd.Flags().GetString("run")
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
			conn, _ := cmd.Flags().GetString("connector")
			run, _ := cmd.Flags().GetString("run")
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
			conn, _ := cmd.Flags().GetString("connector")
			run, _ := cmd.Flags().GetString("run")
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
