package cli

import (
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/icreated/lumnik-open/lm/internal/ask"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newAskCmd() *cobra.Command {
	ask := &cobra.Command{
		Use:   "ask QUESTION",
		Short: "Ask your data a question in natural language (text-to-SQL over the fused hub)",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			question := strings.Join(args, " ")
			scope, _ := cmd.Flags().GetString("scope")
			lang, _ := cmd.Flags().GetString("lang")
			previous, _ := cmd.Flags().GetString("previous")
			// An ask costs a model call: a format this command cannot print is refused before it is paid for.
			format := outputFormat(cmd)
			if format != "table" && format != "json" && format != "yaml" {
				return fmt.Errorf("unknown output format: %s (want table|json|yaml)", format)
			}
			res, err := c.RagAsk(question, scope, client.AnswerLang(lang), previous)
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			if format != "table" {
				return output.PrintObject(out, format, res)
			}
			fmt.Fprintln(out, res.Answer)
			if res.UnderstoodAs != "" {
				fmt.Fprintf(out, "  understood as: %s\n", res.UnderstoodAs)
			}
			if res.DataAsOf != "" {
				fmt.Fprintf(out, "  data as of: %s (last completed run)\n", res.DataAsOf)
			}
			printDisputed(out, res.Disputed, res.DisputedTruncated)
			printRows(out, res)
			// Honesty: show the SQL that actually ran, so the answer is auditable.
			if res.SQL != "" {
				fmt.Fprintln(out)
				fmt.Fprintf(out, "  SQL: %s\n", res.SQL)
			}
			printOtherDoors(out, res.OtherDoors)
			return nil
		},
	}
	ask.Flags().String("scope", "", "The scope whose fused data to query")
	ask.Flags().String("previous", "", "The question this one continues, so a follow-up like \"Et SUP-004 ?\" resolves against it")
	ask.Flags().String("lang", "", "Two-letter answer language (default: from LC_ALL, LC_MESSAGES or LANG; none: the question's own)")
	return ask
}

// gestureFor maps a hub door token to the command THIS binary offers for it, or "" when it has
// none — an unknown token is silence, never printed raw. (The hub sends tokens rather than
// commands because the gesture differs per surface: the TUI names a page, the PWA a tab.)
func gestureFor(door string) string {
	switch door {
	case ask.Lifecycle:
		return "lm chat  — the declared lifecycle is retrieved there, not by SQL"
	case ask.Conflicts:
		return "lm conflicts list  — where this scope has source conflicts or lookup ambiguities, row by row"
	default:
		return ""
	}
}

// printDisputed names the records this answer filtered on while a source disagrees with another (#874),
// before the SQL: it qualifies the answer itself.
func printDisputed(out io.Writer, disputed []client.Disputed, truncated bool) {
	if len(disputed) == 0 {
		return
	}
	fmt.Fprintln(out)
	fmt.Fprintln(out, "  disputed — a source disagrees on a value this answer filtered on:")
	for _, d := range disputed {
		verdict := "counted"
		if !d.Counted {
			verdict = "not counted"
		}
		var sources []string
		for _, s := range d.Sources {
			sources = append(sources, s.Src+": "+s.Display())
		}
		fmt.Fprintf(out, "    %s  %s = '%s'  %s  %s\n", d.Key, d.Column, d.Matched, verdict, strings.Join(sources, " · "))
	}
	if truncated {
		fmt.Fprintln(out, "    … and more — lm conflicts list")
	}
}

// cellEscapes keeps a value on its row and under its header: a raw tab would move it to the next column.
var cellEscapes = strings.NewReplacer("\t", `\t`, "\n", `\n`, "\r", `\r`)

// printRows tabulates every row the hub returned, after what qualifies the answer and before the SQL
// (#1105): when the phrased answer is withheld or failed, these rows are the answer. The hub's LIMIT
// bounds them, and the count line says how many were printed. An answer that ran no SQL has no result.
func printRows(out io.Writer, res *client.AskResult) {
	if res.SQL == "" {
		return
	}
	fmt.Fprintln(out)
	if len(res.Rows) > 0 {
		var table strings.Builder
		tw := tabwriter.NewWriter(&table, 0, 0, 2, ' ', 0)
		headers := make([]string, len(res.Columns))
		for i, col := range res.Columns {
			headers[i] = strings.ToUpper(col)
		}
		for _, cells := range append([][]string{headers}, res.Rows...) {
			shown := make([]string, len(cells))
			for i, c := range cells {
				shown[i] = cellEscapes.Replace(c)
			}
			fmt.Fprintln(tw, "  "+strings.Join(shown, "\t"))
		}
		_ = tw.Flush()
		// tabwriter pads every cell but the last, so an empty last cell would leave the padding behind.
		for _, l := range strings.Split(strings.TrimSuffix(table.String(), "\n"), "\n") {
			fmt.Fprintln(out, strings.TrimRight(l, " "))
		}
	}
	fmt.Fprintf(out, "  %s\n", rowsReturned(len(res.Rows), res.RowCount))
}

func rowsReturned(shown, counted int) string {
	switch {
	case shown != counted:
		return fmt.Sprintf("%d rows shown; the hub counted %d", shown, counted)
	case shown == 0:
		return "no rows returned"
	case shown == 1:
		return "1 row returned"
	default:
		return fmt.Sprintf("%d rows returned", shown)
	}
}

func printOtherDoors(out io.Writer, doors []string) {
	var lines []string
	for _, d := range doors {
		if g := gestureFor(d); g != "" {
			lines = append(lines, g)
		}
	}
	if len(lines) == 0 {
		return
	}
	fmt.Fprintln(out)
	for _, l := range lines {
		fmt.Fprintf(out, "  try: %s\n", l)
	}
}
