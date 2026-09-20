package resources

import (
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// Ctrl-L used to call HookReload, POST /api/platform/hooks/{id}/reload — an endpoint that does
// not exist on the hub (#610, same finding as `lm hook reload`). handleKey has no Ctrl-L branch
// left at all, so no server is stubbed here: an unreachable address is enough to prove nothing
// could be sent even if a branch reappeared, without a real listener.
func TestHooksHandleKey_ctrlLNeverTouchesTheHub(t *testing.T) {
	h := NewHooks(client.New(config.Context{Server: "http://127.0.0.1:0"}), func(string) {})

	ev := tcell.NewEventKey(tcell.KeyCtrlL, 0, tcell.ModNone)
	if got := h.handleKey(ev); got != ev {
		t.Errorf("Ctrl-L must fall through unhandled, got %v", got)
	}
}

func TestHooksHints_doesNotAdvertiseReload(t *testing.T) {
	h := &Hooks{table: tview.NewTable()}
	if got := h.Hints(); got != "r: refresh  :: switch  ?: help  q: quit" {
		t.Errorf("Hints() = %q, must not mention a ^L reload that no longer does anything", got)
	}
}
