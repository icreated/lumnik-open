package resources

import (
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestInboxWhen(t *testing.T) {
	now := time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC)
	cases := []struct{ in, want string }{
		{"", ""},
		{"not-a-time", "not-a-time"},
		{"2026-07-06T11:58:00Z", "2m"},
		{"2026-07-06T11:58:00.123456Z", "1m"}, // Java Instant fractional seconds parse; age truncates
		{"2026-07-06T12:02:00Z", "0m"},        // clock skew: never a negative age
		{"2026-07-06T09:00:00Z", "3h"},
		{"2026-07-04T12:00:00Z", "2d"},
	}
	for _, c := range cases {
		if got := inboxWhen(c.in, now); got != c.want {
			t.Errorf("inboxWhen(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestInboxCells(t *testing.T) {
	now := time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC)
	e := client.InboxEntry{ID: 42, OccurredAt: "2026-07-06T11:58:00Z",
		Process: "lyon-client-arrive", Message: "Nouveau client Claire à Lyon", EntityKey: "e717"}
	got := inboxCells(e, now)
	want := []string{"42", "2m", "lyon-client-arrive", "Nouveau client Claire à Lyon", "e717"}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("cell %d = %q, want %q", i, got[i], want[i])
		}
	}
	e.Acked = true
	if got := inboxCells(e, now); got[3] != "Nouveau client Claire à Lyon [traité]" {
		t.Errorf("acked message = %q, want ' [traité]' suffix", got[3])
	}
}

func TestInboxToggleAllAndEmptyDescribe(t *testing.T) {
	i := &Inbox{items: []client.InboxEntry{{ID: 1, Message: "x"}}}
	if i.showAll.Load() {
		t.Fatal("showAll must default to false (open decisions only)")
	}
	i.toggleAll()
	if !i.showAll.Load() {
		t.Fatal("toggleAll must flip to true")
	}
	empty := &Inbox{}
	if _, _, ok := empty.SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}
