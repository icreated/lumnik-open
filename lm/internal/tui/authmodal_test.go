package tui

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/brand"
)

// The session-expired modal is the TUI's door back to the login flow, so it wears the same
// wordmark as `lm login` — one definition, two surfaces that cannot drift.
func TestAuthLostBody_wearsTheWordmarkAndTheKeys(t *testing.T) {
	body := authLostBody(80)

	for _, want := range []string{"██", "lumnik 2026", "Session expired", "log in again", "dismiss"} {
		if !strings.Contains(body, want) {
			t.Errorf("the auth modal must contain %q, got:\n%s", want, body)
		}
	}
	if !strings.Contains(body, "["+brand.GoldHex+"]") {
		t.Errorf("the wordmark wears lumière here as it does on the login page, got:\n%s", body)
	}
}

func TestAuthLostBody_dropsTheWordmarkWhenTheTerminalIsNarrow(t *testing.T) {
	body := authLostBody(40)

	if strings.Contains(body, "██") {
		t.Errorf("a folded logo would push the keys off screen, got:\n%s", body)
	}
	if !strings.Contains(body, "log in again") {
		t.Error("the keys are the point of the modal — they survive at any width")
	}
}
