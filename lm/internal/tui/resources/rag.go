package resources

import (
	"fmt"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// aiSwitchedOff is the module registry's reason for a module the operator switched off with
// lumnik.ai.enabled=false (its manifest's enabled_by).
const aiSwitchedOff = "disabled:lumnik.ai.enabled"

// Rag is the :rag page — `lm rag status` and `lm rag reindex` (#1135): the chunks indexed per métier
// scope, the platform corpus' last indexing, and R to rebuild that corpus. Whether there is a RAG at
// all comes from the module registry first: switched off is not absent, and the page says which.
//
// Everything but the fetch runs on the event loop (offLoop's render, the key handler), so the page
// state needs no lock.
type Rag struct {
	app         *tview.Application
	table       *tview.Table
	client      *client.Client
	offLoop     offLoop
	inFlight    inFlight // the guard on R (#1147)
	onError     func(string)
	toast       func(string)
	confirm     ConfirmAs
	unavailable string // why there is no RAG to show or reindex; "" when there is
}

func NewRag(app *tview.Application, c *client.Client, onError, toast func(string)) *Rag {
	t := newListTable()
	t.SetTitle(" :rag ").SetBorder(true)
	r := &Rag{app: app, table: t, client: c, onError: onError, toast: toast}
	t.SetInputCapture(r.handleKey)
	return r
}

// SetConfirm is wired by the App: R asks through it, and reindexes nothing without one.
func (r *Rag) SetConfirm(fn ConfirmAs) { r.confirm = fn }

func (r *Rag) View() tview.Primitive { return r.table }

func (r *Rag) Hints() string {
	return "R: reindex the platform corpus  r: refresh"
}

// ragAvailability reads the module registry: "" when the rag module is active, otherwise what the
// panel says instead of the index.
func ragAvailability(modules []types.Module) string {
	for _, m := range modules {
		if m.Name != "rag" {
			continue
		}
		switch {
		case m.Status == "ACTIVE":
			return ""
		case m.Reason == aiSwitchedOff:
			return "The AI is switched off on this hub (lumnik.ai.enabled=false): the RAG module is installed and its chunks are kept, but nothing is indexed or searched."
		default:
			return "The RAG module is inactive on this hub: " + m.Reason
		}
	}
	return "RAG is not in this build (the Open edition carries no RAG module): there is no index."
}

// Refresh asks the module registry, then — only when the RAG is there — its status and scopes, off
// the event loop.
func (r *Rag) Refresh() {
	r.offLoop.run(r.app, func() func() {
		modules, err := r.client.ModuleList()
		if err != nil {
			return func() { r.renderMessage(fmt.Sprintf("ERROR: %v", err), "") }
		}
		if why := ragAvailability(modules); why != "" {
			return func() { r.renderMessage(why, why) }
		}
		status, err := r.client.RagStatus()
		var scopes []client.RagScope
		if err == nil {
			scopes, err = r.client.RagScopes()
		}
		if err != nil {
			return func() { r.renderMessage(fmt.Sprintf("ERROR: %v", err), "") }
		}
		return func() { r.render(status, scopes) }
	})
}

// renderMessage shows one line instead of the index; unavailable is what R then answers ("" lets R
// ask the hub — an error may be passing).
func (r *Rag) renderMessage(msg, unavailable string) {
	r.unavailable = unavailable
	r.table.Clear()
	r.table.SetTitle(" :rag ")
	r.table.SetCell(0, 0, dataCell(msg).SetSelectable(false))
}

func (r *Rag) render(status *client.RagStatus, scopes []client.RagScope) {
	r.unavailable = ""
	r.table.Clear()
	r.table.SetTitle(ragTitle(status))
	renderRagScopes(r.table, scopes)
	if len(scopes) == 0 {
		r.table.SetCell(1, 0, tview.NewTableCell("No chunk carries a scope yet — a connector's run indexes its rows under its scopes.").SetSelectable(false))
	}
}

// ragTitle carries the platform corpus — what R rebuilds — in the page title.
func ragTitle(st *client.RagStatus) string {
	if st == nil || !st.Indexed {
		return " :rag — platform corpus: not indexed "
	}
	at := ""
	if st.IndexedAt != nil {
		at = ", indexed " + output.HumanTimeStr(*st.IndexedAt)
	}
	return tview.Escape(fmt.Sprintf(" :rag — platform corpus: %d chunks%s (%s / %s) ",
		st.Chunks, at, st.Provider, st.EmbeddingModel))
}

func (r *Rag) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Rune() == 'R' {
		r.reindex()
		return nil
	}
	return ev
}

// reindex is R: the platform corpus (module manifests, the API reference, the hooks) is rebuilt after
// a yes. A connector's rows are not in it — its runs index them.
func (r *Rag) reindex() {
	if r.unavailable != "" {
		say(r.onError, "no reindex: "+r.unavailable)
		return
	}
	if r.confirm == nil || r.inFlight.busy("R", r.toast) { // no question for a press that would be dropped
		return
	}
	r.confirm("Reindex the platform corpus (module manifests, API reference, hooks)? Connector rows are indexed by their runs, not by this.", "Reindex", func() {
		r.inFlight.background(r.app, "R", r.toast, func() func() {
			err := r.client.RagReindex()
			return func() {
				if err != nil {
					say(r.onError, fmt.Sprintf("reindex: %v", err))
					return
				}
				say(r.toast, "✓ platform corpus reindex started")
				r.Refresh()
			}
		})
	})
}
