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

// A toast ends on the footer as it is when it ends, not as it was when it began: the describe
// panel closed while a toast was up must not have its note restored (#1179 review).
func TestSetToast_restoresTheFooterAsItIsWhenTheToastEnds(t *testing.T) {
	a := runningApp(t, "http://127.0.0.1:1")
	f := a.footer
	onLoop(a, time.Second, func() {
		f.SetHints("hints")
		f.SetNote("Tab: scroll")
		f.SetToast(a.tv, "copied", 50*time.Millisecond)
		f.SetNote("") // the panel closes while the toast is up
	})
	time.Sleep(300 * time.Millisecond) // the toast's revert is queued after 50 ms
	var got string
	onLoop(a, time.Second, func() { got = f.tv.GetText(true) })
	if strings.Contains(got, "Tab: scroll") {
		t.Errorf("the toast restored the closed panel's note: %q", got)
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
