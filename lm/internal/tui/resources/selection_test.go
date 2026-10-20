package resources

import (
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// A live refresh must keep the cursor on the OBJECT the integrator selected, not on its row
// number: Del, R, ^P, c and a act on the selection (#1107).

func conns(names ...string) []types.Connector {
	out := make([]types.Connector, len(names))
	for i, n := range names {
		out[i] = types.Connector{ID: "id-" + n, Name: n}
	}
	return out
}

func TestSources_selectionFollowsTheConnectorAcrossRefreshes(t *testing.T) {
	for name, next := range map[string][]string{
		"reordered":      {"C", "A", "B"},
		"inserted above": {"X", "A", "B", "C"},
		"removed above":  {"B", "C"},
	} {
		t.Run(name, func(t *testing.T) {
			s := NewSources(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
			s.renderList(conns("A", "B", "C"), nil)
			s.table.Select(2, 0) // B
			s.renderList(conns(next...), nil)
			if got, _ := s.selectedItem(); got.Name != "B" {
				t.Fatalf("selection moved to %q, want B", got.Name)
			}
		})
	}
}

func TestRuns_selectionFollowsTheRunWhenANewerOneArrives(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	at := func(m int) *time.Time { v := time.Date(2026, 10, 16, 10, m, 0, 0, time.UTC); return &v }
	r.renderList([]types.Run{{ID: "r2", StartedAt: at(2)}, {ID: "r1", StartedAt: at(1)}}, nil)
	r.table.Select(2, 0) // r1
	r.renderList([]types.Run{{ID: "r3", StartedAt: at(3)}, {ID: "r2", StartedAt: at(2)}, {ID: "r1", StartedAt: at(1)}}, nil)
	if got, _ := r.selected(); got.ID != "r1" {
		t.Fatalf("selection moved to %q, want r1", got.ID)
	}
}

func TestProcesses_selectionFollowsTheProcess(t *testing.T) {
	p := NewManifests(nil, ProcessesSource(client.New(config.Context{Server: "http://127.0.0.1:0"})), nil, nil)
	p.all = []ManifestRow{{Name: "a"}, {Name: "b"}}
	p.show()
	p.table.Select(2, 0)
	p.all = []ManifestRow{{Name: "0"}, {Name: "a"}, {Name: "b"}}
	p.show()
	if got, _ := p.selected(); got.Name != "b" {
		t.Fatalf("selection moved to %q, want b", got.Name)
	}
}

func TestInbox_selectionFollowsTheEntry(t *testing.T) {
	i := NewInbox(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	i.renderList([]client.InboxEntry{{ID: 2}, {ID: 1}}, nil)
	i.table.Select(2, 0)
	i.renderList([]client.InboxEntry{{ID: 3}, {ID: 2}, {ID: 1}}, nil)
	if got, _ := i.selected(); got.ID != 1 {
		t.Fatalf("selection moved to %d, want 1", got.ID)
	}
}

func TestEndpoints_refreshKeepsTheSelectedEndpoint(t *testing.T) {
	p := NewEndpoints(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	eps := []types.Endpoint{{ID: "e1"}, {ID: "e2"}}
	p.renderList("c1", eps, nil)
	p.table.Select(2, 0)
	p.renderList("c1", eps, nil)
	if got, _ := p.selectedItem(); got.ID != "e2" {
		t.Fatalf("refresh threw the cursor to %q, want e2", got.ID)
	}
}

// D leads from a connector or a run to its dead letters (#1111).
func TestD_opensTheSelectedObjectsDeadLetters(t *testing.T) {
	var got []string
	s := NewSources(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	s.SetOnDeadLetters(func(id string) { got = append(got, "conn:"+id) })
	s.renderList(conns("A"), nil)
	s.table.Select(1, 0)
	s.handleKey(tcell.NewEventKey(tcell.KeyRune, 'D', tcell.ModNone))

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.SetOnDeadLetters(func(id string) { got = append(got, "run:"+id) })
	r.renderList([]types.Run{{ID: "r1"}}, nil)
	r.table.Select(1, 0)
	r.handleKey(tcell.NewEventKey(tcell.KeyRune, 'D', tcell.ModNone))

	if len(got) != 2 || got[0] != "conn:id-A" || got[1] != "run:r1" {
		t.Errorf("D led to %v", got)
	}
}
