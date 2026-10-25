package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var observationHeaders = []string{"WORKFLOW", "DOC", "STATE", "SINCE", "ALERTED"}

// SINCE/ALERTED render like every other date column (output.HumanTime) — see outputRow's
// comment: -o json/yaml already carry the full-precision timestamp untouched, so a raw ISO
// string in the table bought no scriptability that -o json didn't already give.
func observationRow(o client.Observation) []string {
	return []string{o.Workflow, o.DocKey, o.State, output.HumanTime(&o.Since), output.HumanTime(o.AlertedAt)}
}

func newObservationsCmd() *cobra.Command {
	root := &cobra.Command{Use: "observations", Short: "Inspect the workflow tape"}
	var workflow string
	list := &cobra.Command{
		Use:   "list",
		Short: "List watched documents, the stillest first",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.ObservationList(pageLimit(cmd), workflow)
			if err != nil {
				return err
			}
			return printPage(cmd, observationHeaders, items, observationRow)
		},
	}
	addPageLimitFlag(list, 200, client.ObservationsPageCap, "max observations to list")
	list.Flags().StringVar(&workflow, "workflow", "", "Only this workflow's documents")
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
	var doc string
	transitions := &cobra.Command{
		Use:   "transitions",
		Short: "List the workflow journal, newest first: each move and the seconds spent in the state it left",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.ObservationTransitions(pageLimit(cmd), workflow, doc)
			if err != nil {
				return err
			}
			return printPage(cmd, transitionHeaders, items, transitionRow)
		},
	}
	addPageLimitFlag(transitions, 200, client.ObservationsPageCap, "max moves to list")
	transitions.Flags().StringVar(&workflow, "workflow", "", "Only this workflow's moves")
	transitions.Flags().StringVar(&doc, "doc", "", "Only this document's moves")
	root.AddCommand(transitions)
	return root
}

var transitionHeaders = []string{"WORKFLOW", "DOC", "FROM", "TO", "ENTERED", "OBSERVED", "IN_FROM_S"}

// IN_FROM_S is raw seconds, like MEDIAN_DETECTION_S; for a document's first move it counts from its
// first observation, so it is a lower bound.
func transitionRow(t client.Transition) []string {
	in := int64(t.ObservedAt.Sub(t.EnteredFromAt).Seconds())
	return []string{t.Workflow, t.DocKey, t.From, t.To, output.HumanTime(&t.EnteredFromAt),
		output.HumanTime(&t.ObservedAt), fmt.Sprintf("%d", in)}
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
