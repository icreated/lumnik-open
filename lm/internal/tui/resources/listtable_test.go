package resources

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// screenOf draws t alone on a w×h simulation screen and returns its lines, as the user sees them.
func screenOf(t *tview.Table, w, h int) []string {
	sim := tcell.NewSimulationScreen("UTF-8")
	_ = sim.Init()
	sim.SetSize(w, h)
	t.SetRect(0, 0, w, h)
	t.Draw(sim)
	sim.Show()
	cells, cw, ch := sim.GetContents()
	lines := make([]string, ch)
	for y := range ch {
		var b strings.Builder
		for x := range cw {
			if r := cells[y*cw+x].Runes; len(r) > 0 {
				b.WriteRune(r[0])
			} else {
				b.WriteRune(' ')
			}
		}
		lines[y] = b.String()
	}
	return lines
}

// The inventory behind newListTable: a page building its table by hand would lose the fixed header
// and the cursor-following viewport, and only a screen-sized list would show it (#1172). The one
// other table is :view's recents, a headerless list of paths.
func TestListPages_buildTheirTableWithNewListTable(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") || f == "listtable.go" {
			continue
		}
		src, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for i, line := range strings.Split(string(src), "\n") {
			if strings.Contains(line, "tview.NewTable()") && !strings.Contains(line, "recentsUI:") {
				t.Errorf("%s:%d builds a table by hand — a list page's table is newListTable(): %s", f, i+1, strings.TrimSpace(line))
			}
		}
	}
}

// A list taller than the screen, observed live on :sources and :runs (#1172): the header scrolled
// away, and the page opened with its viewport away from its cursor — the first ↓ jumped to the
// top. The page is drawn once empty, as on arrival before the list lands, then with the list; and
// again after a refresh, with the cursor moved down the list.
func TestListPages_keepTheHeaderAndTheCursorRowOnScreen(t *testing.T) {
	const rows, height = 100, 20
	now := time.Now()
	type page struct {
		table  *tview.Table
		land   func()             // the hub's list lands
		header string             // the first column's header
		rowAt  func(i int) string // the text table row i shows
	}
	pages := map[string]func() page{
		"sources": func() page {
			s := NewSources(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
			names := make([]string, rows)
			for i := range names {
				names[i] = fmt.Sprintf("src-%03d", i)
			}
			return page{s.table, func() { s.renderList(conns(names...), nil) }, "NAME",
				func(i int) string { return s.items[i-1].Name }}
		},
		"runs": func() page {
			r := NewRuns(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil)
			list := make([]types.Run, rows)
			for i := range list {
				started := now.Add(-time.Duration(i) * time.Minute)
				list[i] = types.Run{ID: fmt.Sprintf("r%03d", i), ConnectorName: fmt.Sprintf("run-%03d", i),
					Status: "Completed", StartedAt: &started}
			}
			return page{r.table, func() { r.renderList(list, nil) }, "CONNECTOR",
				func(i int) string { return r.items[i-1].ConnectorName }}
		},
	}
	for name, open := range pages {
		t.Run(name, func(t *testing.T) {
			p := open()
			screenOf(p.table, 120, height) // arrival: drawn before the list lands
			p.land()
			check := func(when string) {
				t.Helper()
				lines := screenOf(p.table, 120, height)
				if !strings.Contains(lines[1], p.header) {
					t.Errorf("%s: the table's first line is %q, want the header %s", when, strings.TrimSpace(lines[1]), p.header)
				}
				row, _ := p.table.GetSelection()
				want := p.rowAt(row)
				for _, l := range lines {
					if strings.Contains(l, want) {
						return
					}
				}
				t.Errorf("%s: the cursor is on %s, which is not on screen:\n%s", when, want, strings.Join(lines, "\n"))
			}
			check("on arrival")
			p.land()
			check("after a refresh")
			p.table.Select(60, 0)
			screenOf(p.table, 120, height)
			p.land()
			check("after a refresh, the cursor down the list")
		})
	}
}
