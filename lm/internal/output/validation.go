package output

import (
	"fmt"
	"io"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// FormatValidation writes per-field errors/warnings. Returns nil if there are no errors
// (warnings allowed); returns an error if there is at least one error.
func FormatValidation(out io.Writer, file string, r *client.ValidationResult) error {
	nErr := len(r.Errors)
	nWarn := len(r.Warnings)

	if nErr == 0 && nWarn == 0 {
		fmt.Fprintf(out, "✓ %s — valid\n", file)
		return nil
	}

	mark := "✓"
	if nErr > 0 {
		mark = "✗"
	} else if nWarn > 0 {
		mark = "!"
	}
	fmt.Fprintf(out, "%s %s — %d error(s), %d warning(s)\n\n", mark, file, nErr, nWarn)

	for _, e := range r.Errors {
		printOne(out, "ERROR  ", e)
	}
	for _, w := range r.Warnings {
		printOne(out, "WARN   ", w)
	}

	if nErr > 0 {
		fmt.Fprintf(out, "Fix errors above before running 'lm apply -f %s'.\n", file)
		return fmt.Errorf("validation failed: %d error(s)", nErr)
	}
	return nil
}

func printOne(out io.Writer, label string, e client.ValidationError) {
	fmt.Fprintf(out, "  %s  %s\n", label, e.Path)
	fmt.Fprintf(out, "          %s\n", e.Message)
	for _, r := range e.Recommendations {
		fmt.Fprintf(out, "          → %s\n", r)
	}
	fmt.Fprintln(out)
}
