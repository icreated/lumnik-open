package tui

import (
	"errors"
	"testing"
)

// The badge says what the connection does, not whether an event happened to pass (#1108).
func TestLiveStatus_followsTheConnection(t *testing.T) {
	var s LiveStatus
	if got := s.Label(); got != "[CONNECTING]" {
		t.Fatalf("initial = %q", got)
	}
	s.Observe(nil)
	if got := s.Label(); got != "[LIVE✓]" {
		t.Fatalf("after open = %q, want [LIVE✓] even with no event yet", got)
	}
	s.Observe(errors.New("SSE upstream status 403"))
	if got := s.Label(); got != "[RECONNECTING]" {
		t.Fatalf("after drop = %q, want [RECONNECTING]", got)
	}
}

func TestContextLabel_saysWhichHub(t *testing.T) {
	for _, c := range []struct{ name, server, want string }{
		{"prod", "https://hub.example.com", "prod (hub.example.com)"},
		{"", "http://localhost:8080", "localhost:8080"},
		{"hub.example.com", "https://hub.example.com", "hub.example.com"},
	} {
		if got := contextLabel(c.name, c.server); got != c.want {
			t.Errorf("contextLabel(%q,%q) = %q, want %q", c.name, c.server, got, c.want)
		}
	}
}

// A connector name is data: in the confirmation it cannot carry colour tags (#1110).
func TestConfirmText_escapesNameAndContext(t *testing.T) {
	got := confirmText(`Delete connector "x[red]"?`, "prod[::b]")
	want := "Delete connector \"x[red[]\"?\n\non prod[::b[]"
	if got != want {
		t.Fatalf("confirmText = %q, want %q", got, want)
	}
}

// The header says a / filter is in force, escaped, and drops it once cleared (#1120).
func TestFilteredResource(t *testing.T) {
	if got := filteredResource("sources", "erp[red]"); got != ":sources /erp[red[] (Esc clears)" {
		t.Errorf("got %q", got)
	}
	if got := filteredResource("sources", ""); got != ":sources" {
		t.Errorf("got %q", got)
	}
}
