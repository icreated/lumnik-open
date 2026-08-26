package tui

import (
	"sort"
	"strings"
)

// Palette tracks the known resource names and provides prefix autocomplete.
// Aliases are accepted spellings that resolve to a canonical name — the old
// mechanism words keep working while the nav speaks usage.
type Palette struct {
	names   []string          // canonical + alias spellings, sorted
	aliases map[string]string // alias → canonical
}

func NewPalette(names []string, aliases map[string]string) *Palette {
	sorted := append([]string{}, names...)
	for a := range aliases {
		sorted = append(sorted, a)
	}
	sort.Strings(sorted)
	return &Palette{names: sorted, aliases: aliases}
}

func (p *Palette) Autocomplete(prefix string) []string {
	if prefix == "" {
		return append([]string{}, p.names...)
	}
	var out []string
	for _, n := range p.names {
		if strings.HasPrefix(n, prefix) {
			out = append(out, n)
		}
	}
	return out
}

// Resolve returns the canonical name for a given (partial) input, or "" if not found.
func (p *Palette) Resolve(input string) string {
	matches := p.Autocomplete(input)
	if len(matches) == 1 {
		return p.canonical(matches[0])
	}
	for _, n := range matches {
		if n == input {
			return p.canonical(n)
		}
	}
	return ""
}

func (p *Palette) canonical(name string) string {
	if c, ok := p.aliases[name]; ok {
		return c
	}
	return name
}
