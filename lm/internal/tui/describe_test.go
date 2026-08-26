package tui

import (
	"strings"
	"testing"
)

// The panel renders its title with SetDynamicColors(true), so a record key containing
// '[red]' would alter the chrome. Escaping lives HERE, at the sink, because no page puts
// colour markup in a title — which means a page cannot forget.
//
// Note on the assertion direction: tview's GetText(true) (our titleText() helper) both
// strips recognized style tags AND un-escapes a tview.Escape'd "[[" back to a literal
// "[". So an UNESCAPED "[red]" is parsed as a real tag and silently consumed — the word
// "red" and its brackets vanish, leaving only "evil". An ESCAPED "[red]evil" is instead
// treated as plain characters and un-escapes back to the literal string "[red]evil" —
// fully preserved, harmless. So the correct sink must make titleText() CONTAIN the raw
// "[red]evil" (proving the markup rendered as inert text, not as an interpreted tag) —
// verified empirically against tview v0.42.0; the naive "must NOT contain" assertion
// gets this backwards and fails on a correctly-escaped title.
func TestDescribePanelEscapesTheTitle(t *testing.T) {
	d := NewDescribePanel()
	d.SetContent("[red]evil", "body")
	if got := d.titleText(); !strings.Contains(got, "[red]evil") {
		t.Errorf("describe title corrupted: colour markup was interpreted instead of shown literally, got %q", got)
	}
}

// The body is NOT escaped here: four pages colour their bodies on purpose, so escaping
// is each page's job (and escape_sweep_test.go is that inventory).
//
// The "plain" assertion alone is vacuous: it holds whether the body is left raw (its
// "[yellow]" tag is interpreted and stripped by GetText(true)) OR wrongly escaped (its
// "[yellow]" survives un-escaped back to a literal) — either way "plain" is still there.
// The discriminating assertion is the absence of the literal "[yellow]" tag text: that
// only survives in the WRONG (escaped) world, so this is what would catch a regression
// that starts escaping the body at the sink.
func TestDescribePanelLeavesTheBodyAlone(t *testing.T) {
	d := NewDescribePanel()
	d.SetContent("t", "[yellow]heading[white] plain")
	got := d.bodyText()
	if !strings.Contains(got, "plain") {
		t.Errorf("body text lost: %q", got)
	}
	if strings.Contains(got, "[yellow]") {
		t.Errorf("body was escaped at the sink — colour markup survived as literal text instead of being interpreted: %q", got)
	}
}

// Pre-existing coverage (predates the title-escaping fix): plain title and body,
// no colour markup involved, so escaping is a no-op here and the original
// assertions carry over unchanged.
func TestDescribePanelSetContent(t *testing.T) {
	d := NewDescribePanel()
	d.SetContent("sage", "id: cf3a\nstatus: ● success")

	titleText := d.titleText()
	if !strings.Contains(titleText, "sage") {
		t.Errorf("title should contain 'sage', got %q", titleText)
	}
	body := d.bodyText()
	if !strings.Contains(body, "cf3a") {
		t.Errorf("body should contain 'cf3a', got %q", body)
	}
	if !strings.Contains(body, "success") {
		t.Errorf("body should contain 'success', got %q", body)
	}
}

// A second SetContent call must fully replace the panel, not append to it —
// the previous body must not survive alongside the new one.
func TestDescribePanelReplaceContent(t *testing.T) {
	d := NewDescribePanel()
	d.SetContent("first", "old body")
	d.SetContent("second", "new body")
	if !strings.Contains(d.titleText(), "second") {
		t.Errorf("title not replaced on second call")
	}
	if strings.Contains(d.bodyText(), "old body") {
		t.Errorf("old body still present after SetContent: %q", d.bodyText())
	}
}
