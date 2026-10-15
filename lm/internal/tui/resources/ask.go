package resources

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/ask"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Ask is the :ask page — analytic (text-to-SQL) chat over the ingested hub.
// It reuses the k9s gold chrome plus OSC52 yank / CSV export theatre (spec §5).
type Ask struct {
	app      *tview.Application
	client   *client.Client
	flex     *tview.Flex
	input    *tview.InputField
	headline *tview.TextView
	sqlView  *tview.TextView
	table    *tview.Table
	answer   *tview.TextView
	scope    string
	last     *client.AskResult
	onError  func(string)
	onToast  func(string)
}

func NewAsk(app *tview.Application, c *client.Client, onError, onToast func(string)) *Ask {
	a := &Ask{app: app, client: c, onError: onError, onToast: onToast}

	a.input = tview.NewInputField().SetLabel(" Ask: ").SetFieldWidth(0)
	a.input.SetDoneFunc(func(key tcell.Key) {
		switch key {
		case tcell.KeyEnter:
			a.submit(strings.TrimSpace(a.input.GetText()))
		case tcell.KeyEscape, tcell.KeyTab, tcell.KeyBacktab:
			// Blur the field so the global keys (: ? page-switch) work again —
			// otherwise the cursor is trapped in the input (rehearsal carnet #13).
			a.app.SetFocus(a.table)
		}
	})

	a.headline = tview.NewTextView().SetDynamicColors(true).SetTextAlign(tview.AlignCenter)
	a.sqlView = tview.NewTextView().SetDynamicColors(true)
	a.answer = tview.NewTextView().SetDynamicColors(true).SetWordWrap(true)

	a.table = tview.NewTable().SetBorders(false).SetSelectable(true, false)
	style.ApplyTableSelection(a.table)
	a.table.SetInputCapture(a.tableKeys)

	a.flex = tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.input, 1, 0, true).
		AddItem(a.headline, 1, 0, false).
		AddItem(a.sqlView, 3, 0, false).
		AddItem(a.table, 0, 1, false).
		AddItem(a.answer, 4, 0, false)
	style.ApplyChrome(a.flex.Box)
	a.flex.SetTitle(" :ask ").SetBorder(true)

	return a
}

func (a *Ask) View() tview.Primitive { return a.flex }

func (a *Ask) Hints() string {
	return "Enter: submit · Esc: leave the field · y: yank SQL · e: export CSV · n: new question · r: clear"
}

// SetScope is called by the App when switching to :ask so questions carry the
// current session scope.
func (a *Ask) SetScope(scope string) { a.scope = scope }

// FocusPrimitive lets the App focus the input field when the page opens.
func (a *Ask) FocusPrimitive() tview.Primitive { return a.input }

// Refresh clears the page (App calls it on `r` and when switching in).
func (a *Ask) Refresh() {
	a.input.SetText("")
	a.headline.SetText("")
	a.sqlView.SetText("")
	a.answer.SetText("")
	a.table.Clear()
	a.last = nil
}

func (a *Ask) submit(question string) {
	if question == "" {
		return
	}
	a.headline.SetText("[yellow]…thinking[white]")
	scope := a.scope // capture on the event loop; SetScope may write a.scope concurrently
	go func() {
		res, err := a.client.RagAsk(question, scope, client.AnswerLang(""))
		QueueUpdate(a.app, func() {
			if err != nil {
				// Calm, full-page degradation — notably for REST/CSV scopes with no
				// discovered schema, where the backend returns 500/503 today.
				a.renderNotice(friendlyAskError(err, scope))
				return
			}
			a.render(res)
		})
	}()
}

// renderNotice shows a calm, full-page message (e.g. no discovered schema for a
// REST/CSV scope) in place of results — never a raw error toast.
func (a *Ask) renderNotice(msg string) {
	msg = tview.Escape(msg) // server notice = data, not markup
	a.last = nil
	a.headline.SetText("")
	a.sqlView.SetText("")
	a.table.Clear()
	if style.ColorsEnabled() {
		a.answer.SetText("[yellow]" + msg + "[white]")
	} else {
		a.answer.SetText(msg)
	}
	a.app.SetFocus(a.input)
}

func (a *Ask) render(res *client.AskResult) {
	a.last = res

	// gold headline band
	head := tview.Escape(deriveHeadline(res.Answer, res.RowCount))
	if style.ColorsEnabled() {
		a.headline.SetText("[black:#FFB000] " + head + " [-:-]")
	} else {
		a.headline.SetText(head)
	}

	// generated SQL + transparency badge
	tag, label := sqlBadge(res.Answer)
	a.sqlView.SetText(fmt.Sprintf("[%s]%s[white]\n%s", tag, label, tview.Escape(res.SQL)))

	// result rows
	a.table.Clear()
	for i, col := range res.Columns {
		cell := dataCell(col).SetSelectable(false).SetExpansion(1)
		if style.ColorsEnabled() {
			cell.SetTextColor(tcell.ColorYellow)
		}
		a.table.SetCell(0, i, cell)
	}
	for r, row := range res.Rows {
		for c, val := range row {
			a.table.SetCell(r+1, c, dataCell(val).SetExpansion(1))
		}
	}

	// plain-language answer
	answerText := res.Answer
	if res.DataAsOf != "" {
		answerText += "\n\ndata as of: " + res.DataAsOf + " (last completed run)"
	}
	for _, g := range doorGestures(res.OtherDoors) {
		answerText += "\n\n" + g
	}
	a.answer.SetText(tview.Escape(answerText))

	// hand focus to the results so y / e / n work without typing into the input
	a.app.SetFocus(a.table)
}

// doorGestures turns the hub's door tokens into THIS surface's gestures.
//
// Deliberately not shared with `lm ask`: the hub sends tokens precisely because the gesture
// differs per surface, and here it is a TUI page, not a command. `conflicts` has one — the
// :conflicts page. `lifecycle` has none: the declared graph is answered by the semantic chat,
// and this atelier has no chat page, so pointing at :observations (the watched-lifecycle ledger,
// a different thing) would be a signpost to a near miss. An unknown token is silence (#515).
func doorGestures(doors []string) []string {
	var out []string
	for _, d := range doors {
		if d == ask.Conflicts {
			out = append(out, "where this scope has source conflicts or lookup ambiguities: :conflicts")
		}
	}
	return out
}

func (a *Ask) tableKeys(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Rune() == 'e':
		a.exportCSV()
		return nil
	case ev.Rune() == 'n' || ev.Key() == tcell.KeyEnter:
		a.app.SetFocus(a.input)
		return nil
	}
	return ev
}

func (a *Ask) exportCSV() {
	if a.last == nil || len(a.last.Columns) == 0 {
		return
	}
	name := fmt.Sprintf("lm-ask-%d.csv", time.Now().Unix())
	if err := os.WriteFile(name, []byte(rowsToCSV(a.last.Columns, a.last.Rows)), 0o644); err != nil {
		if a.onError != nil {
			a.onError("export: " + err.Error())
		}
		return
	}
	if a.onToast != nil {
		a.onToast("✓ exported " + name)
	}
}

// SelectedYank implements Yankable: `y` copies the generated SQL (spec §5.1).
func (a *Ask) SelectedYank() (string, bool) {
	if a.last == nil || a.last.SQL == "" {
		return "", false
	}
	return a.last.SQL, true
}
