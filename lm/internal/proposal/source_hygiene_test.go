package proposal

import (
	"os"
	"path/filepath"
	"testing"
	"unicode"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// This package exists to neutralise invisible and bidirectional characters, so its own sources
// must not carry one: a raw U+2028 in a string literal is invisible in review and is rewritten by
// editors and tools (it happened here: escapes silently became raw characters, and one test case
// ended up asserting nothing). Every such rune is written as an escape.
func TestSourcesCarryNoInvisibleRunes(t *testing.T) {
	files, err := filepath.Glob("*.go")
	require.NoError(t, err)
	more, err := filepath.Glob("../cli/proposal*.go")
	require.NoError(t, err)
	files = append(files, more...)
	// the slice's own spec and plan quote code and rune names: they get the same rule. The open
	// edition prunes docs/superpowers whole; only that absence is legitimate.
	if _, err := os.Stat("../../../docs/superpowers"); err == nil {
		docs, err := filepath.Glob("../../../docs/superpowers/*/2026-10-04-proposal-dossier-lm-drafts*.md")
		require.NoError(t, err)
		require.Len(t, docs, 2, "the spec and the plan of slice 3b")
		files = append(files, docs...)
	}
	require.NotEmpty(t, files)

	for _, f := range files {
		raw, err := os.ReadFile(f)
		require.NoError(t, err)
		line := 1
		for _, r := range string(raw) {
			if r == '\n' {
				line++
			}
			invisible := textsafe.DangerousRune(r) || unicode.Is(unicode.Cf, r) ||
				r == '\u0085' || r == '\u2028' || r == '\u2029'
			assert.False(t, invisible, "%s:%d carries the invisible rune U+%04X: write it as an escape", f, line, r)
		}
	}
}
