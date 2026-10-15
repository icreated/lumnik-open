package checks

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// TextGlitch checks invariant family 4: lm must never reflect a RAW control or
// bidi rune into its own output (a Trojan-source-style cosmetic defect — safe but
// visually deceptive in a terminal). It matches the raw rune, NOT its escaped
// form: a %q-quoted token like the ASCII sequence backslash-u-202e is safe.
func TextGlitch(stdout, stderr string, caseID generator.CaseID, seed int64) []oracle.Finding {
	for _, s := range []string{stdout, stderr} {
		for _, r := range s {
			if dangerousRune(r) {
				return []oracle.Finding{{
					Severity: oracle.Low, Family: oracle.Text, CaseID: caseID, Seed: seed,
					Detail: fmt.Sprintf("raw control/bidi rune U+%04X reflected into lm output", r),
				}}
			}
		}
	}
	return nil
}

// dangerousRune delegates to textsafe.DangerousRune so the OTK detector and the
// lm sanitizer share one definition of "dangerous" and can never drift.
func dangerousRune(r rune) bool { return textsafe.DangerousRune(r) }
