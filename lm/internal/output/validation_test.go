package output

import (
	"bytes"
	"os"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func oneError() *client.ValidationResult {
	return &client.ValidationResult{Errors: []client.ValidationError{{
		Path: "spec.auth.token_env", Severity: "ERROR", Message: "required field missing",
	}}}
}

// A manifest read from a file is named by that file, and the reader is told the command to run
// once it is fixed — the shape six docs pages transcribe verbatim.
func TestValidation_namesTheFileAndTheCommand(t *testing.T) {
	var out bytes.Buffer
	if err := FormatValidation(&out, "stripe.yaml", "lm apply -f stripe.yaml", oneError()); err == nil {
		t.Fatal("errors must make the command exit non-zero")
	}
	s := out.String()
	if !strings.Contains(s, "✗ stripe.yaml — 1 error(s), 0 warning(s)") {
		t.Errorf("the file must be named, in the transcribed shape: %s", s)
	}
	if !strings.Contains(s, "Fix errors above before running 'lm apply -f stripe.yaml'.") {
		t.Errorf("the reader must get the command to run, verbatim: %s", s)
	}
}

// A piped manifest has no file to re-edit, so the caller hands over no command and the advice
// points upstream instead. Offering 'lm apply -f -' would sit waiting on a terminal.
func TestValidation_withoutACommandAdvisesTheSource(t *testing.T) {
	var out bytes.Buffer
	if err := FormatValidation(&out, "stdin", "", oneError()); err == nil {
		t.Fatal("errors must make the command exit non-zero")
	}
	s := out.String()
	if !strings.Contains(s, "stdin") {
		t.Errorf("the source must be named: %s", s)
	}
	if strings.Contains(s, "-f -") {
		t.Errorf("advice that would hang at a terminal must not be printed: %s", s)
	}
}

func TestValidation_validSourceIsNamed(t *testing.T) {
	var out bytes.Buffer
	if err := FormatValidation(&out, "stdin", "", &client.ValidationResult{}); err != nil {
		t.Fatalf("a valid manifest must exit zero: %v", err)
	}
	if !strings.Contains(out.String(), "stdin — valid") {
		t.Errorf("got %q", out.String())
	}
}

// The seam, asserted on the source: rendering must not know what "-" means. That contract lives
// in the CLI (isStdinSentinel / manifestLabel / rerunCommand), and a second copy here is exactly
// how the label a refusal prints and the one a validation prints come to drift apart.
func TestValidation_doesNotKnowTheStdinSentinel(t *testing.T) {
	b, err := os.ReadFile("validation.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{`"-"`, "stdin"} {
		if strings.Contains(string(b), forbidden) {
			t.Errorf("validation.go decides %q for itself — the caller must hand it the name", forbidden)
		}
	}
}
