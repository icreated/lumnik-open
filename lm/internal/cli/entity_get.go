package cli

import (
	"fmt"
	"slices"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

// newEntityGetCmd reads one page of an entity's canonical rows — a thin facade over
// GET /api/entities/{name}, with no query language of its own: exact-match filters on canonical
// columns, plus the hub's limit/offset. Paging is explicit; the caller asks for the next page.
func newEntityGetCmd() *cobra.Command {
	var offset int
	var filterArgs []string
	cmd := &cobra.Command{
		Use:   "get NAME",
		Short: "Read an entity's canonical rows (one page; --filter COLUMN=VALUE matches exactly, a date as an instant)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			limit, _ := cmd.Flags().GetInt("limit")
			if limit < 1 || limit > client.EntityRowsPageCap {
				return fmt.Errorf("--limit %d is outside 1..%d, the range the hub honours", limit, client.EntityRowsPageCap)
			}
			if offset < 0 {
				return fmt.Errorf("--offset %d must not be negative", offset)
			}
			filters, err := parseEntityFilters(filterArgs)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			rows, err := c.EntityRows(args[0], filters, limit, offset)
			if err != nil {
				return err
			}
			if len(rows) == 0 && outputFormat(cmd) == "table" {
				fmt.Fprintln(cmd.ErrOrStderr(), "no rows")
				return nil
			}
			var cols, headers []string
			if len(rows) > 0 {
				cols = rows[0].Columns()
				for _, f := range filters {
					// The hub ignores a filter naming no canonical column and returns every row. A
					// returned row lacking the column proves it was ignored: never print that page as
					// though the filter had selected it. (An empty page is the same either way.)
					if !slices.Contains(cols, f.Column) {
						return fmt.Errorf("--filter %s: %q is not a column of %s (the hub ignores unknown filters and would return every row)",
							f.Column, f.Column, args[0])
					}
				}
				for _, col := range cols {
					headers = append(headers, strings.ToUpper(col))
				}
			}
			return printPage(cmd, headers, rows, func(r client.EntityRow) []string {
				cells := make([]string, 0, len(cols))
				for _, col := range cols { // the first row's columns, the ones the headers came from
					cells = append(cells, r.Cell(col))
				}
				return cells
			})
		},
	}
	addPageLimitFlag(cmd, 100, client.EntityRowsPageCap, fmt.Sprintf("max rows to return (1..%d)", client.EntityRowsPageCap))
	cmd.Flags().IntVar(&offset, "offset", 0, "page offset")
	cmd.Flags().StringArrayVar(&filterArgs, "filter", nil, "COLUMN=VALUE on a canonical column: exact, or equal as an instant for a date on a date column (repeatable)")
	return cmd
}

// parseEntityFilters turns --filter COLUMN=VALUE flags into filters, refusing what the hub would
// resolve silently: a column it reads as a paging control, or one named twice (it keeps only the
// first value). The split is at the first '=', so a value may contain one.
func parseEntityFilters(args []string) ([]client.EntityFilter, error) {
	var out []client.EntityFilter
	seen := map[string]bool{}
	for _, a := range args {
		col, val, ok := strings.Cut(a, "=")
		if !ok || col == "" {
			return nil, fmt.Errorf("--filter %q: want COLUMN=VALUE", a)
		}
		if col == "limit" || col == "offset" {
			return nil, fmt.Errorf("--filter %s=…: %q is the paging control (--%s), not a filterable column", col, col, col)
		}
		if col == "identity" {
			return nil, fmt.Errorf("--filter identity=…: %q is the hub's switch for the identity columns, not a filterable column", col)
		}
		if seen[col] {
			return nil, fmt.Errorf("--filter names %q twice — the hub would keep only the first value", col)
		}
		seen[col] = true
		out = append(out, client.EntityFilter{Column: col, Value: val})
	}
	return out, nil
}
