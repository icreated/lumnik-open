package output

import (
	"fmt"
	"io"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// FormatValidation writes per-field errors/warnings. Returns nil if there are no errors
// (warnings allowed); returns an error if there is at least one error.
//
// name is what the manifest is called in the report, and rerun the command to suggest once the
// errors are fixed — empty when there is none to suggest. Both are decided by the caller on
// purpose: whether a manifest came from a file or from a pipe is the CLI's contract, and a copy
// of that rule here is how the label a refusal prints drifts away from the one a validation does.
func FormatValidation(out io.Writer, name, rerun string, r *client.ValidationResult) error {
	nErr := len(r.Errors)
	nWarn := len(r.Warnings)

	if nErr == 0 && nWarn == 0 {
		fmt.Fprintf(out, "✓ %s — valid\n", name)
		return nil
	}

	mark := "✓"
	if nErr > 0 {
		mark = "✗"
	} else if nWarn > 0 {
		mark = "!"
	}
	fmt.Fprintf(out, "%s %s — %d error(s), %d warning(s)\n\n", mark, name, nErr, nWarn)

	for _, e := range r.Errors {
		printOne(out, "ERROR  ", e)
	}
	for _, w := range r.Warnings {
		printOne(out, "WARN   ", w)
	}

	if nErr > 0 {
		if rerun == "" {
			fmt.Fprintln(out, "Fix the errors above in what produced this manifest.")
		} else {
			fmt.Fprintf(out, "Fix errors above before running '%s'.\n", rerun)
		}
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
