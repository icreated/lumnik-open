package cli

import (
	"fmt"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var observationHeaders = []string{"WORKFLOW", "DOC", "STATE", "SINCE", "ALERTED"}

// Raw timestamps on purpose: the CLI is the scriptable surface, the TUI is where the
// age is humanized.
func observationRow(o client.Observation) []string {
	alerted := ""
	if o.AlertedAt != nil {
		alerted = o.AlertedAt.Format(time.RFC3339)
	}
	return []string{o.Workflow, o.DocKey, o.State, o.Since.Format(time.RFC3339), alerted}
}

func newObservationsCmd() *cobra.Command {
	root := &cobra.Command{Use: "observations", Short: "Inspect the workflow tape"}
	list := &cobra.Command{
		Use:   "list",
		Short: "List watched documents, the stillest first",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			limit, _ := cmd.Flags().GetInt("limit")
			items, err := c.ObservationList(limit)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, observationHeaders, items, observationRow)
		},
	}
	list.Flags().Int("limit", 200, "max observations to list")
	root.AddCommand(list)
	stats := &cobra.Command{
		Use:   "stats",
		Short: "The tape aggregated per workflow: watched, dormant, median detection",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.ObservationStats()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, statsHeaders, items, statsRow)
		},
	}
	root.AddCommand(stats)
	return root
}

var statsHeaders = []string{"WORKFLOW", "WATCHED", "DORMANT", "MEDIAN_DETECTION_S"}

// Raw seconds on purpose (scriptable surface); "-" is absence — no dormant documents —
// never rendered as 0.
func statsRow(s client.ObservationStatsRow) []string {
	median := "-"
	if s.MedianDetectionSeconds != nil {
		median = fmt.Sprintf("%d", *s.MedianDetectionSeconds)
	}
	return []string{s.Workflow, fmt.Sprintf("%d", s.Watched), fmt.Sprintf("%d", s.Dormant), median}
}
