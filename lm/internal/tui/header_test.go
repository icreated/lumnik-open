package tui

import (
	"strings"
	"testing"
)

func TestHeaderScopeRendering(t *testing.T) {
	h := NewHeader()
	h.SetContext("local")
	h.SetResource(":connectors")
	h.SetScope("compta")
	if got := h.tv.GetText(true); !strings.Contains(got, "scope: compta") {
		t.Errorf("expected 'scope: compta' in header, got %q", got)
	}

	h.SetScope("all")
	if got := h.tv.GetText(false); !strings.Contains(got, "all ⚠") {
		t.Errorf("expected 'all ⚠' marker, got %q", got)
	}
	if got := h.tv.GetText(false); !strings.Contains(got, "yellow") {
		t.Errorf("expected yellow color tag for 'all', got %q", got)
	}
}

func TestHeaderCarriesTheName(t *testing.T) {
	h := NewHeader()
	h.SetContext("local")
	h.SetResource(":connectors")

	got := h.tv.GetText(true)
	if !strings.Contains(got, "lumnik 2026") {
		t.Errorf("the header wears the name, got %q", got)
	}
	if !strings.Contains(got, "local") || !strings.Contains(got, ":connectors") {
		t.Errorf("and it must still say where you are, got %q", got)
	}
}
