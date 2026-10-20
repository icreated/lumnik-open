package resources

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Search is :search (#1132): whether this hub has a Search provider at all (GET
// /api/search/capability, said first), the declared SearchIndexes with their build state — R
// rebuilds, c cancels a rebuild, Del removes one after a yes — and / to query, bound to the session
// scope like the PWA's Search tab. The query is the page's / text: Esc clears it and the indexes
// come back.
type Search struct {
	app      *tview.Application
	client   *client.Client
	view     *tview.Flex
	banner   *tview.TextView
	table    *tview.Table
	scope    func() string
	offLoop  offLoop
	inFlight inFlight // the guard on R, c and Del (#1147)
	textFilter
	capability *client.SearchCapability
	capErr     error
	indexes    []client.SearchIndexStatus
	listErr    error
	hits       []client.SearchHit
	queryErr   error
	confirm    ConfirmAs
	toast      func(string)
	onError    func(string)
	onSelect   func()
}

func NewSearch(app *tview.Application, c *client.Client, scope func() string, toast, onError func(string)) *Search {
	t := newListTable()
	style.ApplyTableSelection(t)
	banner := tview.NewTextView().SetWrap(true)
	view := tview.NewFlex().SetDirection(tview.FlexRow).AddItem(banner, 2, 0, false).AddItem(t, 0, 1, true)
	view.SetTitle(" :search ").SetBorder(true)
	style.ApplyChrome(view.Box)
	p := &Search{app: app, client: c, view: view, banner: banner, table: t, scope: scope, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// SetConfirm is wired by the App: Del asks through it, and deletes nothing without one.
func (p *Search) SetConfirm(fn ConfirmAs) { p.confirm = fn }
func (p *Search) View() tview.Primitive   { return p.view }

func (p *Search) Hints() string {
	if p.q != "" {
		return "/: another query  Esc: back to the indexes  d: describe the hit  r: refresh"
	}
	return "/: query  R: rebuild  c: cancel the rebuild  Del: delete  d: describe  r: refresh"
}

// SetTextFilter is / — it runs the query q, and "" goes back to the indexes.
func (p *Search) SetTextFilter(q string) { p.q = q; p.Refresh() }

func (p *Search) Refresh() {
	p.offLoop.run(p.app, func() func() {
		q, scope := p.q, ""
		if p.scope != nil {
			scope = p.scope()
		}
		var (
			capability *client.SearchCapability
			capErr     error
			indexes    []client.SearchIndexStatus
			res        *client.SearchResult
			err        error
		)
		if q == "" {
			// The capability is asked beside the indexes, at the same time: it almost never changes,
			// so a query keeps the last answer instead of asking again.
			asked := make(chan struct{})
			go func() { capability, capErr = p.client.SearchCapability(); close(asked) }()
			indexes, err = p.client.SearchList()
			<-asked
		} else {
			res, err = p.client.SearchQuery(client.SearchQueryParams{Text: q, Scope: scope})
		}
		return func() {
			if q == "" {
				p.capability, p.capErr = capability, capErr
				p.renderBanner()
			}
			if q != p.q {
				return // the query changed meanwhile: the dirty round brings its answer
			}
			if q == "" {
				prev := selectedKey(p.table, p.indexes, searchIndexKey)
				p.indexes, p.listErr = indexes, err
				p.render()
				reselect(p.table, p.indexes, searchIndexKey, prev)
				return
			}
			p.hits, p.queryErr = nil, err
			if res != nil {
				p.hits = res.Hits
			}
			p.render()
		}
	})
}

func searchIndexKey(s client.SearchIndexStatus) string { return s.Name }

// renderBanner says what the hub has before anything else: no provider is the normal Open/Pro
// state, and an empty table under it must not read as "nothing matched".
func (p *Search) renderBanner() {
	var text string
	c := p.capability
	switch {
	case p.capErr != nil:
		text = "Search capability unknown: " + p.capErr.Error()
	case c.State == "available":
		text = strings.TrimSpace("Search is available — provider " + c.Provider + " " + c.Version)
	case c.State == "unavailable" && c.Reason == "not_installed":
		text = "Search is not installed on this hub: no Search provider is built in — the normal Open/Pro state, " +
			"not an empty result. Indexes can still be declared; nothing is built or searched until a provider is " +
			"built in (deploy/selfhost/up.sh --search or --search-cluster, closed edition)."
	default:
		text = "Search is " + c.State
		if c.Reason != "" {
			text += ": " + c.Reason
		}
		if c.Provider != "" {
			text += " (provider " + c.Provider + ")"
		}
	}
	p.banner.SetText(tview.Escape(text))
}

func (p *Search) render() {
	if p.q != "" {
		headerRow(p.table, []string{"entity", "key", "score", "match"})
		switch {
		case p.queryErr != nil:
			p.table.SetCell(1, 0, dataCell(searchErr(p.queryErr).Error()).SetSelectable(false))
		case len(p.hits) == 0:
			p.table.SetCell(1, 0, dataCell(fmt.Sprintf("0 hits for %q", p.q)).SetSelectable(false))
		}
		for r, h := range p.hits {
			for c, v := range hitCells(h) {
				p.table.SetCell(r+1, c, dataCell(v))
			}
		}
		return
	}
	headerRow(p.table, []string{"name", "status", "documents", "lag", "rebuild"})
	switch {
	case p.listErr != nil:
		p.table.SetCell(1, 0, dataCell(indexErr(p.listErr).Error()).SetSelectable(false))
	case len(p.indexes) == 0:
		p.table.SetCell(1, 0, tview.NewTableCell("No SearchIndex declared — create one from :new.").SetSelectable(false))
	}
	for r, s := range p.indexes {
		for c, v := range indexCells(s) {
			p.table.SetCell(r+1, c, dataCell(v))
		}
	}
}

func indexCells(s client.SearchIndexStatus) []string {
	status := s.Status
	switch {
	case s.Invalid:
		status = strings.TrimSpace("invalid " + s.InvalidReason)
	case s.Withheld:
		status = "withheld"
	}
	docs := ""
	if s.DocumentCount != nil {
		docs = fmt.Sprint(*s.DocumentCount)
		if s.DocumentCountStale {
			docs += " (stale)"
		}
	}
	rebuild := "never built"
	switch {
	case s.ActiveRun != nil:
		rebuild = "running (" + s.ActiveRun.Phase + ")"
	case s.LastRebuild != nil:
		rebuild = s.LastRebuild.Outcome + " " + output.HumanTimeStr(s.LastRebuild.FinishedAt)
	case s.LiveGeneration != nil:
		rebuild = "built"
	}
	return []string{s.Name, status, docs, fmt.Sprintf("%ds", s.LagSeconds), rebuild}
}

// hitMatchWidth cuts each field value of the MATCH column, as `lm search query` does.
const hitMatchWidth = 60

// hitCells is one hit, rendered as `lm search query` renders it (output.SearchMatch).
func hitCells(h client.SearchHit) []string {
	return []string{h.Entity, output.JoinPairs(h.Key, ","), fmt.Sprintf("%.2f", h.Score), output.SearchMatch(h, hitMatchWidth)}
}

// indexErr says integrator-only for the 403 the index doors (/api/platform/*) give an lm_user. A
// query's 403 is another refusal — the métier scope — so it goes through searchErr alone.
func indexErr(err error) error {
	var api *client.APIError
	if errors.As(err, &api) && api.Status == 403 {
		return fmt.Errorf("%v — index status, rebuild and delete are integrator-only (lm_integrator); / still queries", err)
	}
	return searchErr(err)
}

// searchErr turns the answers that must never read as "no results" into what they mean. Every other
// refusal is the hub's own.
func searchErr(err error) error {
	var api *client.APIError
	if !errors.As(err, &api) {
		return err
	}
	switch api.Status {
	case 501:
		return errors.New("search is not installed on this hub — there is nothing to query (this is not an empty result)")
	case 503:
		return fmt.Errorf("search is degraded: %v", err)
	}
	return err
}

func (p *Search) selectedIndex() (client.SearchIndexStatus, bool) {
	row, _ := p.table.GetSelection()
	if p.q != "" || row < 1 || row-1 >= len(p.indexes) {
		return client.SearchIndexStatus{}, false
	}
	return p.indexes[row-1], true
}

func (p *Search) selectedHit() (client.SearchHit, bool) {
	row, _ := p.table.GetSelection()
	if p.q == "" || row < 1 || row-1 >= len(p.hits) {
		return client.SearchHit{}, false
	}
	return p.hits[row-1], true
}

func (p *Search) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	s, ok := p.selectedIndex()
	switch {
	case !ok:
		return ev
	case ev.Rune() == 'R':
		p.act("R", "rebuild "+s.Name, func() (string, error) {
			id, err := p.client.SearchRebuildStart(context.Background(), s.Name)
			return "rebuild " + shortRunID(id) + " claimed", err
		})
		return nil
	case ev.Rune() == 'c':
		p.act("c", "cancel "+s.Name, func() (string, error) {
			return "cancellation requested", p.client.SearchCancel(context.Background(), s.Name)
		})
		return nil
	case ev.Key() == tcell.KeyDelete && p.confirm != nil:
		if p.inFlight.busy("Del", p.toast) { // no question for a press that would be dropped
			return nil
		}
		p.confirm(fmt.Sprintf("Delete SearchIndex %q? Its indexed documents are purged.", s.Name), "Delete", func() {
			p.act("Del", "delete "+s.Name, func() (string, error) { return "deleted", p.client.SearchIndexDelete(s.Name) })
		})
		return nil
	}
	return ev
}

// act runs one index gesture off the loop. A rebuild or a cancel is recorded, not finished, when the
// hub answers: the toast says so, and r shows where it stands. A second press of key is dropped
// while the first is out.
func (p *Search) act(key, what string, call func() (string, error)) {
	p.inFlight.background(p.app, key, p.toast, func() func() {
		done, err := call()
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("%s: %v", what, indexErr(err)))
				return
			}
			say(p.toast, "✓ "+what+": "+done)
			p.Refresh()
		}
	})
}

// Describable — the index's state in full, or the hit's fields.

func (p *Search) SelectedDescribe() (string, string, bool) {
	esc := tview.Escape
	var b strings.Builder
	if h, ok := p.selectedHit(); ok {
		fmt.Fprintf(&b, "entity: %s\nkey:    %s\nscore:  %.2f\n\n", esc(h.Entity), esc(output.JoinPairs(h.Key, ",")), h.Score)
		for _, n := range slices.Sorted(maps.Keys(h.Fields)) {
			fmt.Fprintf(&b, "%s: %s\n", esc(n), esc(h.Fields[n]))
		}
		if h.CanonicalLink != "" {
			fmt.Fprintf(&b, "\nlink: %s\n", esc(h.CanonicalLink))
		}
		return h.Entity, b.String(), true
	}
	s, ok := p.selectedIndex()
	if !ok {
		return "", "", false
	}
	cells := indexCells(s)
	fmt.Fprintf(&b, "name:      %s\nstatus:    %s\ndocuments: %s\nlag:       %s\nrebuild:   %s\n",
		esc(s.Name), esc(cells[1]), esc(cells[2]), cells[3], esc(cells[4]))
	if s.LastError != "" {
		fmt.Fprintf(&b, "\n── last error ──\n%s\n", esc(s.LastError))
	}
	return s.Name, b.String(), true
}

func (p *Search) OnSelectionChange(cb func()) { p.onSelect = cb }
