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
	f.tv.SetText(" " + text + " ")
}

// SetToast temporarily replaces the hints line with a transient message.
// After `duration`, the previous hints are restored. Safe to call from any
// goroutine — the revert is queued via tview's update loop.
//
// `app` is the tview Application used to schedule the revert draw.
func (f *Footer) SetToast(app *tview.Application, msg string, duration time.Duration) {
	f.mu.Lock()
	saved := f.hints
	f.mu.Unlock()
	// msg is data (paths, server text), never markup — the footer renders with
	// DynamicColors, so escaping lives here and no caller ever worries.
	f.tv.SetText(" " + tview.Escape(msg) + " ")
	go func() {
		time.Sleep(duration)
		resources.QueueUpdate(app, func() {
			f.tv.SetText(" " + saved + " ")
		})
	}()
}
