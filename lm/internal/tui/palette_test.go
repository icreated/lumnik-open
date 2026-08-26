package tui

import (
	"reflect"
	"testing"
)

func TestPalette_AutocompleteMatchesPrefix(t *testing.T) {
	p := NewPalette([]string{"connectors", "runs", "dlq", "hooks", "modules", "migrations"}, nil)
	got := p.Autocomplete("co")
	want := []string{"connectors"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("want %v, got %v", want, got)
	}
}

func TestPalette_AutocompleteEmptyReturnsAll(t *testing.T) {
	p := NewPalette([]string{"connectors", "runs"}, nil)
	got := p.Autocomplete("")
	if len(got) != 2 {
		t.Errorf("want 2 entries, got %v", got)
	}
}

func TestPalette_AutocompleteNoMatch(t *testing.T) {
	p := NewPalette([]string{"connectors", "runs"}, nil)
	got := p.Autocomplete("xyz")
	if len(got) != 0 {
		t.Errorf("want 0 entries, got %v", got)
	}
}

func TestPalette_ResolveAliasExact(t *testing.T) {
	p := NewPalette([]string{"sources", "runs"}, map[string]string{"connectors": "sources"})
	if got := p.Resolve("connectors"); got != "sources" {
		t.Errorf("Resolve(connectors) = %q, want sources", got)
	}
}

func TestPalette_ResolveAliasPrefix(t *testing.T) {
	p := NewPalette([]string{"sources", "runs"}, map[string]string{"connectors": "sources"})
	if got := p.Resolve("co"); got != "sources" {
		t.Errorf("Resolve(co) = %q, want sources", got)
	}
}

func TestPalette_AutocompleteIncludesAliases(t *testing.T) {
	p := NewPalette([]string{"sources", "runs"}, map[string]string{"connectors": "sources"})
	got := p.Autocomplete("con")
	if len(got) != 1 || got[0] != "connectors" {
		t.Errorf("Autocomplete(con) = %v, want [connectors]", got)
	}
}

func TestPalette_AmbiguousPrefixDoesNotResolve(t *testing.T) {
	// "s" prefixes both "sources" and "scopes" — no single match, no exact match.
	p := NewPalette([]string{"sources", "scopes"}, nil)
	if got := p.Resolve("s"); got != "" {
		t.Errorf("Resolve(s) = %q, want \"\"", got)
	}
}
