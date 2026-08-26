package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/rivo/tview"
)

func TestHumanizeSince(t *testing.T) {
	cases := []struct {
		d    time.Duration
		want string
	}{
		{30 * time.Second, "now"},
		{5 * time.Minute, "5min"},
		{3 * time.Hour, "3h"},
		{50 * time.Hour, "2d"},
	}
	for _, c := range cases {
		if got := humanizeSince(c.d); got != c.want {
			t.Errorf("humanizeSince(%v) = %q, want %q", c.d, got, c.want)
		}
	}
}

// The tape's order is the SERVER's promise (stillest first); the page must not re-sort it.
func TestObservationsRenderKeepsServerOrderAndMarksAlerts(t *testing.T) {
	alerted := time.Now().Add(-2 * time.Hour)
	o := &Observations{table: tview.NewTable(), items: []client.Observation{
		{Workflow: "commande", DocKey: "CMD-1", State: "draft",
			Since: time.Now().Add(-72 * time.Hour), AlertedAt: &alerted},
		{Workflow: "commande", DocKey: "CMD-2", State: "sent",
			Since: time.Now().Add(-30 * time.Minute)},
	}}
	o.render()
	if got := o.table.GetCell(1, 1).Text; got != "CMD-1" {
		t.Errorf("first row = %q — the server's order must survive rendering", got)
	}
	if got := o.table.GetCell(1, 3).Text; got != "3d" {
		t.Errorf("SINCE = %q, want the humanized age", got)
	}
	if got := o.table.GetCell(1, 4).Text; !strings.Contains(got, "2h") {
		t.Errorf("ALERTED = %q, want the alert's age", got)
	}
	if got := o.table.GetCell(2, 4).Text; got != "—" {
		t.Errorf("a non-alerted row's ALERTED = %q, want the em dash", got)
	}
}

func TestObservationsDescribeNamesTheObservationRelativeSince(t *testing.T) {
	o := &Observations{table: tview.NewTable(), items: []client.Observation{
		{Workflow: "commande", DocKey: "CMD-1", State: "draft",
			Since: time.Now().Add(-72 * time.Hour), FirstObservedAt: time.Now().Add(-96 * time.Hour)},
	}}
	o.render()
	o.table.Select(1, 0)
	title, body, ok := o.SelectedDescribe()
	if !ok {
		t.Fatal("SelectedDescribe returned !ok for a selected row")
	}
	if !strings.Contains(title, "CMD-1") {
		t.Errorf("title = %q", title)
	}
	for _, want := range []string{"draft", "first observed", "observing"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
}

func TestObservationsDescribeEmptyWithoutSelection(t *testing.T) {
	o := &Observations{table: tview.NewTable()}
	if _, _, ok := o.SelectedDescribe(); ok {
		t.Error("no rows -> describe must report !ok, not panic")
	}
}
