package checks

import (
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// SecretEcho checks invariant family 3 (Go-side): a high-entropy sentinel injected
// via an env var (referenced by the manifest) must never appear in lm output. The
// sentinel is high-entropy, so there is no false-positive path. Empty sentinel
// never fires.
func SecretEcho(output, sentinel string, seed int64) []oracle.Finding {
	if sentinel != "" && strings.Contains(output, sentinel) {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, Seed: seed,
			Detail: "injected secret sentinel echoed in lm output",
		}}
	}
	return nil
}

// FileDisclosure checks invariant family 3 (Go-side): a path-traversal payload
// must never cause lm to leak local file CONTENTS. We look for the /etc/passwd
// signature; echoing the path itself in a rejection message is fine.
func FileDisclosure(output string, seed int64) []oracle.Finding {
	if strings.Contains(output, "root:x:0:0") {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, Seed: seed,
			Detail: "local file contents disclosed in lm output (/etc/passwd signature)",
		}}
	}
	return nil
}
