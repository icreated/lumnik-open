package tui

import (
	"github.com/icreated/lumnik-open/lm/internal/tui/resources"
	"sync"
	"time"

	"github.com/rivo/tview"
)

type Footer struct {
	tv    *tview.TextView
	mu    sync.Mutex
	hints string
	note  string // the open describe panel's own word, said after the hints (#1173)
}

func NewFooter() *Footer {
	tv := tview.NewTextView().
		SetDynamicColors(true).
		SetTextAlign(tview.AlignLeft)
	tv.SetBackgroundColor(tview.Styles.PrimitiveBackgroundColor)
	return &Footer{tv: tv}
}

func (f *Footer) View() tview.Primitive { return f.tv }

func (f *Footer) SetHints(text string) {
	f.mu.Lock()
	f.hints = text
	f.mu.Unlock()
	f.redraw()
}

// SetNote sets the word said after every hints line until it is set again; "" clears it. A page
// rewriting its hints keeps it, and clearing it leaves the hints alone (an error stays said).
func (f *Footer) SetNote(text string) {
	f.mu.Lock()
	f.note = text
	f.mu.Unlock()
	f.redraw()
}

// redraw shows the hints line, and the note after it.
func (f *Footer) redraw() {
	f.mu.Lock()
	line := " " + f.hints + " "
	if f.note != "" {
		line = " " + f.hints + "  " + f.note + " "
	}
	f.mu.Unlock()
	f.tv.SetText(line)
}

// SetToast temporarily replaces the hints line with a transient message.
// After `duration`, the hints line is restored as it is then — a note cleared
// meanwhile stays cleared. Safe to call from any goroutine — the revert is
// queued via tview's update loop.
//
// `app` is the tview Application used to schedule the revert draw.
func (f *Footer) SetToast(app *tview.Application, msg string, duration time.Duration) {
	// msg is data (paths, server text), never markup — the footer renders with
	// DynamicColors, so escaping lives here and no caller ever worries.
	f.tv.SetText(" " + tview.Escape(msg) + " ")
	go func() {
		time.Sleep(duration)
		resources.QueueUpdate(app, f.redraw)
	}()
}
