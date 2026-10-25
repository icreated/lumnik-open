package output

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"gopkg.in/yaml.v3"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

// RowCount says "1 row", "N rows": lm endpoint purge, the TUI's Del on :endpoints and the :ask
// headline count rows for the same human (#1183).
func RowCount(n int) string {
	if n == 1 {
		return "1 row"
	}
	return fmt.Sprintf("%d rows", n)
}

// ConnectorDeleteTakes says what a connector delete takes, from the hub's dry run: lm connector
// delete and the TUI's Del on :sources ask in the same words (#1218).
func ConnectorDeleteTakes(takes types.DeleteTakes) string {
	parts := make([]string, 0, len(takes.Tables)+2)
	for _, t := range takes.Tables {
		parts = append(parts, RowCount(t.Rows)+" of "+t.Table)
	}
	if takes.Credential != "" {
		parts = append(parts, "credential "+takes.Credential)
	}
	return strings.Join(append(parts, "its run history and RAG chunks"), ", ")
}

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
		return encodeJSON(w, rows)
	case "yaml":
		return encodeYAML(w, rows)
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
		return encodeJSON(w, v)
	case "yaml":
		return encodeYAML(w, v)
	default:
		return fmt.Errorf("unknown output format: %s (want json|yaml)", format)
	}
}

// encodeYAML writes v as YAML under the keys -o json prints, in the same order. The structs lm prints carry json tags
// only, and yaml would name a field after its lower-cased Go name (cellErrors as cellerrors). So v goes through JSON
// first; JSON is YAML, and parsing it as a node keeps the key order and each number's text as it is.
func encodeYAML(w io.Writer, v any) error {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return err
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(buf.Bytes(), &doc); err != nil {
		return err
	}
	if err := yamlStyle(&doc); err != nil {
		return err
	}
	return yaml.NewEncoder(textsafe.Unwrap(w)).Encode(&doc)
}

// yamlStyle drops the JSON look the parser recorded (every string quoted, flow braces) and lets yaml style each node
// as it styles a Go value: a string is quoted only when it would read as another type ("true", "yes", "1").
func yamlStyle(n *yaml.Node) error {
	if n.Kind == yaml.ScalarNode && n.Tag == "!!str" {
		return n.Encode(n.Value)
	}
	n.Style = 0
	for _, c := range n.Content {
		if err := yamlStyle(c); err != nil {
			return err
		}
	}
	return nil
}

// encodeJSON writes v indented, byte for byte what it holds: lm's JSON goes to a terminal or a script, never into an
// HTML page, so & < > are not escaped (a `<>` in a SQL stays `<>`).
func encodeJSON(w io.Writer, v any) error {
	enc := json.NewEncoder(textsafe.Unwrap(w))
	enc.SetIndent("", "  ")
	enc.SetEscapeHTML(false)
	return enc.Encode(v)
}
