package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestProcessCells(t *testing.T) {
	on := client.ProcessInfo{Name: "lyon-client-arrive", Table: "connector.t_cust_csv",
		Emit: "decision.lyon-client-arrive", Enabled: true}
	got := processCells(on)
	want := []string{"lyon-client-arrive", "connector.t_cust_csv", "decision.lyon-client-arrive", "on"}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("cell %d = %q, want %q", i, got[i], want[i])
		}
	}
	on.Enabled = false
	if got := processCells(on); got[3] != "off" {
		t.Errorf("disabled → %q, want off", got[3])
	}
}

func TestProcessesEmptyDescribe(t *testing.T) {
	if _, _, ok := (&Processes{}).SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}
