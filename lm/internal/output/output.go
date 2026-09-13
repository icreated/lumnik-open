package output

import (
	"encoding/json"
	"fmt"
	"io"
	"text/tabwriter"

	"gopkg.in/yaml.v3"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

// Print renders items in the requested format.
//
// format: "table" | "json" | "yaml"
// headers: column titles (only used by "table"; row func MUST return len(headers) cells)
// rows: slice of any type — for table, the row func extracts columns
// toRow: extracts cells from a T for table output; nil for json/yaml
func Print[T any](w io.Writer, format string, headers []string, rows []T, toRow func(T) []string) error {
	switch format {
	case "table":
		if toRow == nil {
			return fmt.Errorf("table format requires a toRow function")
		}
		tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
		for i, h := range headers {
			if i > 0 {
				_, _ = fmt.Fprint(tw, "\t")
			}
			_, _ = fmt.Fprint(tw, h)
		}
		_, _ = fmt.Fprintln(tw)
		for _, r := range rows {
			cells := toRow(r)
			for i, c := range cells {
				if i > 0 {
					_, _ = fmt.Fprint(tw, "\t")
				}
				_, _ = fmt.Fprint(tw, c)
			}
			_, _ = fmt.Fprintln(tw)
		}
		return tw.Flush()
	case "json":
		enc := json.NewEncoder(textsafe.Unwrap(w))
		enc.SetIndent("", "  ")
		return enc.Encode(rows)
	case "yaml":
		return yaml.NewEncoder(textsafe.Unwrap(w)).Encode(rows)
	default:
		return fmt.Errorf("unknown output format: %s (want table|json|yaml)", format)
	}
}

// PrintObject renders ONE value in a machine format. It exists next to Print because a nested
// document — a schema card, tables inside a scope — is not a row table: flattening it to print it
// would drop exactly what a generator downstream reads.
//
// "table" is refused rather than approximated: the caller owns the human rendering, which is the
// only one that can decide what a page of nested data looks like.
func PrintObject(w io.Writer, format string, v any) error {
	switch format {
	case "json":
		enc := json.NewEncoder(textsafe.Unwrap(w))
		enc.SetIndent("", "  ")
		return enc.Encode(v)
	case "yaml":
		return yaml.NewEncoder(textsafe.Unwrap(w)).Encode(v)
	default:
		return fmt.Errorf("unknown output format: %s (want json|yaml)", format)
	}
}
