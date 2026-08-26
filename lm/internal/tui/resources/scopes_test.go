package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestScopesRenderAndSelect(t *testing.T) {
	s := &Scopes{
		items: []client.RagScope{
			{Scope: "compta", Connectors: 3, Chunks: 12431, LastIndexed: "2026-05-24T09:12:00Z"},
			{Scope: "paie", Connectors: 1, Chunks: 2108, LastIndexed: "2026-05-24T08:55:00Z"},
		},
	}
	_, _, ok := s.SelectedDescribe()
	if ok {
		t.Errorf("expected no selection before init")
	}
}

func TestFormatTime(t *testing.T) {
	if got := formatTime(""); got != "" {
		t.Errorf("empty in → empty out, got %q", got)
	}
	if got := formatTime("not-a-time"); got != "not-a-time" {
		t.Errorf("invalid time should pass through, got %q", got)
	}
	if got := formatTime("2026-05-24T09:12:00Z"); got != "2026-05-24 09:12" {
		t.Errorf("valid ISO time, got %q", got)
	}
}
