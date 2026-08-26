package cli

import (
	"fmt"
	"io"
	"text/tabwriter"
)

// padWriter renders two-column "label   value" lines with tab alignment.
type padWriter struct{ rows [][2]string }

func newPadWriter() *padWriter { return &padWriter{} }

func (p *padWriter) line(label, value string) { p.rows = append(p.rows, [2]string{label, value}) }

func (p *padWriter) flush(w io.Writer) {
	tw := tabwriter.NewWriter(w, 0, 0, 3, ' ', 0)
	for _, r := range p.rows {
		_, _ = fmt.Fprintf(tw, "%s\t%s\n", r[0], r[1])
	}
	_ = tw.Flush()
}
