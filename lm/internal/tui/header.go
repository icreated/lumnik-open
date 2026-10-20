package tui

import (
	"fmt"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/brand"

	"github.com/rivo/tview"
)

type Header struct {
	tv       *tview.TextView
	context  string
	resource string
	live     string
	scope    string
}

func NewHeader() *Header {
	tv := tview.NewTextView().
		SetDynamicColors(true).
		SetTextAlign(tview.AlignLeft).
		SetText("")
	tv.SetBackgroundColor(tview.Styles.PrimitiveBackgroundColor)
	return &Header{tv: tv, live: "[CONNECTING]"}
}

func (h *Header) View() tview.Primitive { return h.tv }

func (h *Header) SetContext(c string)    { h.context = c; h.refresh() }
func (h *Header) SetResource(r string)   { h.resource = r; h.refresh() }
func (h *Header) SetLiveStatus(s string) { h.live = s; h.refresh() }
func (h *Header) SetScope(s string)      { h.scope = s; h.refresh() }

func (h *Header) refresh() {
	now := time.Now().Format("15:04:05")
	scopeText := h.scopeText()
	// The name leads: this bar is the only chrome always on screen, so it is where the product
	// says who it is. The context still follows immediately — you must never wonder where you are.
	// Gold, like the borders: the name is chrome. The semantic yellow further along the bar stays
	// a different colour on purpose — a warning must never read as decoration.
	h.tv.SetText(fmt.Sprintf(" [%s::b]%s[white::-] · lm @ %s · %s · %s · %s  %s ",
		brand.GoldHex, brand.Name, h.context, h.resource, scopeText, now, h.live))
}

func (h *Header) scopeText() string {
	if h.scope == "" {
		return "scope: [yellow]system[white]"
	}
	if h.scope == "all" {
		return "scope: [yellow]all ⚠[white]"
	}
	return "scope: " + h.scope
}
