package tui

import (
	"github.com/icreated/lumnik-open/lm/internal/tui/style"

	"github.com/rivo/tview"
)

// DescribePanel renders a read-only split-pane below the current resource
// table. Content is plain multiline text; the producing page formats it.
// The panel knows nothing about specific resource shapes.
type DescribePanel struct {
	title *tview.TextView
	body  *tview.TextView
	flex  *tview.Flex
}

func NewDescribePanel() *DescribePanel {
	title := tview.NewTextView().SetDynamicColors(true).SetTextAlign(tview.AlignLeft)
	body := tview.NewTextView().SetDynamicColors(true).SetScrollable(true).SetTextAlign(tview.AlignLeft)
	body.SetBorder(true)
	style.ApplyChrome(body.Box)

	flex := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(title, 1, 0, false).
		AddItem(body, 0, 1, false)

	return &DescribePanel{title: title, body: body, flex: flex}
}

// SetContent rewrites the panel's title and body.
//
// The TITLE is escaped here, once, rather than in each of the eleven SelectedDescribe
// implementations: no page puts colour markup in a title, so the sink can escape
// unconditionally — and a new page cannot forget. Callers must therefore pass the title
// RAW; tview.Escape is not idempotent, so escaping it twice would show a stray '['.
// The BODY is left untouched: several pages colour theirs on purpose, so escaping the
// data inside it stays each page's job (see resources/escape_sweep_test.go).
func (d *DescribePanel) SetContent(title, body string) {
	d.title.SetText("── describe: " + tview.Escape(title) + " ──")
	d.body.SetText(body)
	d.body.ScrollToBeginning()
}

func (d *DescribePanel) View() tview.Primitive { return d.flex }

// titleText / bodyText are test helpers (current rendered text without ANSI).
func (d *DescribePanel) titleText() string { return d.title.GetText(true) }
func (d *DescribePanel) bodyText() string  { return d.body.GetText(true) }
