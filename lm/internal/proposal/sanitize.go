package proposal

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

// lineBreak reports the runes YAML (1.1 and 1.2 alike) reads as the end of a line.
func lineBreak(r rune) bool {
	switch r {
	case '\n', '\r', '\v', '\f', '\u0085', '\u2028', '\u2029':
		return true
	}
	return false
}

// unsafeRune is a rune that must not reach the output raw: a control, a bidirectional control, or any
// other invisible format rune (category Cf — a right-to-left mark reorders the punctuation around it).
func unsafeRune(r rune) bool {
	return unicode.IsControl(r) || textsafe.DangerousRune(r) || unicode.Is(unicode.Cf, r)
}

// oneLine flattens a dossier string so it can sit in a "# " comment: every line break becomes a
// space, every other control or bidirectional rune becomes its \uXXXX escape. Nothing the dossier
// says can end the comment.
func oneLine(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case lineBreak(r):
			b.WriteByte(' ')
		case unsafeRune(r):
			fmt.Fprintf(&b, `\u%04x`, r)
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// yamlString writes a flag value as one double-quoted scalar. JSON escaping is YAML-valid, and
// HTML escaping is off: a legacy column may hold <, > or &.
func yamlString(s string) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(s) // a string always encodes
	return strings.TrimSuffix(buf.String(), "\n")
}

// Target is what the integrator designates; the dossier knows none of it. Empty is absent.
type Target struct{ Name, Scope, Table, Column, Key, After string }

var afterRx = regexp.MustCompile(`^[0-9]+[mhd]$`)

// Validate is the least that keeps the output safe and unambiguous. The manifest grammar is the
// hub's, which says the rest on apply: only --after's shape and --table's schema dot are checked
// here, because a wrong one is the commonest typo and costs a round trip.
func (t Target) Validate() error {
	for _, f := range []struct{ flag, value string }{
		{"--name", t.Name}, {"--scope", t.Scope}, {"--table", t.Table},
		{"--column", t.Column}, {"--key", t.Key}, {"--after", t.After},
	} {
		for _, r := range f.value {
			if unsafeRune(r) || lineBreak(r) {
				return fmt.Errorf("%s carries a control, line-break or bidirectional character", f.flag)
			}
		}
	}
	if t.After != "" && !afterRx.MatchString(t.After) {
		return errors.New("--after must look like 14d, 36h or 90m")
	}
	if t.Table != "" && !strings.Contains(t.Table, ".") {
		return errors.New("--table must name its schema, as in connector.t_orders")
	}
	return nil
}
