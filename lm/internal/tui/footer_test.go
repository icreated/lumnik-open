package tui

import (
	"strings"
	"testing"
	"time"

	"github.com/rivo/tview"
)

// Toast and error text is DATA, not markup: the footer renders with
// DynamicColors, so a server error or path containing "[red]" must show
// literally. Escaping lives at the chokepoint — no caller ever worries.
func TestSetToastEscapesMarkup(t *testing.T) {
	f := NewFooter()
	f.SetToast(tview.NewApplication(), "[red]evil", time.Hour)
	got := f.tv.GetText(false)
	if strings.Contains(got, "[red]evil") {
		t.Errorf("unescaped markup reached the footer: %q", got)
	}
	if !strings.Contains(got, tview.Escape("[red]evil")) {
		t.Errorf("escaped toast text missing: %q", got)
	}
}

func TestShowErrorEscapesMarkup(t *testing.T) {
	a := &App{footer: NewFooter()}
	a.showError("[red]evil")
	got := a.footer.tv.GetText(false)
	if strings.Contains(got, "[red]evil") {
		t.Errorf("unescaped markup reached the error line: %q", got)
	}
	if !strings.Contains(got, "ERROR: "+tview.Escape("[red]evil")) {
		t.Errorf("escaped error text missing: %q", got)
	}
}
