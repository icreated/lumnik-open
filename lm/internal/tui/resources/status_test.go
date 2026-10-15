package resources

import (
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

func TestRunStatusKind(t *testing.T) {
	cases := []struct {
		in   string
		want RunStatusKind
	}{
		{"Running", KindRunning},
		{"Queued", KindQueued},
		{"queued", KindQueued},
		{"Completed", KindCompleted},
		{"Failed", KindFailed},
		{"Cancelled", KindCancelled},
		{"completed", KindCompleted},
		{"FAILED", KindFailed},
		{"Partial", KindPartial},
		{"partial", KindPartial},
		{"", KindUnknown},
		{"garbage", KindUnknown},
		{"ERROR", KindUnknown}, // the hub never emits this; the alias is retired
	}
	for _, c := range cases {
		if got := runStatusKind(c.in); got != c.want {
			t.Errorf("runStatusKind(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

// TestStatusFor_CompletedIsSuccess is the live-bug anchor: the hub emits
// "Completed" (never "SUCCESS"), and runs.go's :runs page must render it
// with the success glyph/color, not the unknown default.
func TestStatusFor_CompletedIsSuccess(t *testing.T) {
	if got := statusFor("Completed"); got != runStatusSuccess {
		t.Errorf("statusFor(%q) = %v, want runStatusSuccess", "Completed", got)
	}
}

// TestConnectorStatus_CompletedIsSuccess is the live-bug anchor for the
// :connectors page — same hub vocabulary, independent hand-rolled switch.
func TestConnectorStatus_CompletedIsSuccess(t *testing.T) {
	ct := types.Connector{Enabled: true, Status: "Completed"}
	green := tcell.NewRGBColor(0x22, 0xC5, 0x5E)
	if got := connectorColor(ct); got != green {
		t.Errorf("connectorColor(Completed) = %v, want green %v", got, green)
	}
	if got := connectorGlyph(ct); got != "●" {
		t.Errorf("connectorGlyph(Completed) = %q, want %q", got, "●")
	}
}

// TestEndpointStatusColor_CompletedIsSuccess covers the :endpoints page —
// already correct today, kept here so the table stays authoritative for
// all three sites at once.
func TestEndpointStatusColor_CompletedIsSuccess(t *testing.T) {
	green := tcell.NewRGBColor(0x22, 0xC5, 0x5E)
	if got := endpointStatusColor("Completed"); got != green {
		t.Errorf("endpointStatusColor(Completed) = %v, want green %v", got, green)
	}
}

// hubStatuses is the hub's whole run-status vocabulary. Every entry must decode
// to its kind AND render at every site — see TestEveryHubStatusRendersEverywhere.
var hubStatuses = map[string]RunStatusKind{
	"Queued":    KindQueued,
	"Running":   KindRunning,
	"Completed": KindCompleted,
	"Partial":   KindPartial,
	"Failed":    KindFailed,
	"Cancelled": KindCancelled,
}

// TestEveryHubStatusRendersEverywhere is the inventory guard, and it fails in
// BOTH directions: add a kind to status.go and this table is short of it
// (the cardinality check below); add a table entry and every render site must
// answer for it.
//
// It exists because Partial was added to the hub and lm rendered it as
// KindUnknown — a blank glyph in the default color, which is exactly how "no
// run yet" looks. A run that stopped below its target was LESS visible than a
// green one, at four independent hand-rolled switches.
//
// What it cannot see: a status the HUB emits that never reaches this table.
// No test in this repository can — the vocabulary lives in Java. What it does
// guarantee is that nothing already named here renders as nothing.
func TestEveryHubStatusRendersEverywhere(t *testing.T) {
	if len(hubStatuses) != int(kindCount)-1 { // -1: KindUnknown is not a hub status
		t.Fatalf("hubStatuses has %d entries but RunStatusKind declares %d kinds — "+
			"a kind was added without naming the status that decodes to it",
			len(hubStatuses), int(kindCount)-1)
	}
	for status, want := range hubStatuses {
		t.Run(status, func(t *testing.T) {
			if got := runStatusKind(status); got != want {
				t.Errorf("runStatusKind(%q) = %v, want %v", status, got, want)
			}
			if got := statusFor(status); got == runStatusUnknown {
				t.Errorf(":runs renders %q as unknown", status)
			} else if got.glyph() == " " {
				t.Errorf(":runs gives %q a blank glyph", status)
			}
			if got := endpointStatusColor(status); got == tcell.ColorDefault {
				t.Errorf(":endpoints renders %q in the default color", status)
			}
			ct := types.Connector{Enabled: true, Status: status}
			if got := connectorColor(ct); got == tcell.ColorDefault {
				t.Errorf(":connectors renders %q in the default color", status)
			}
			if got := connectorGlyph(ct); got == " " {
				t.Errorf(":connectors gives %q a blank glyph", status)
			}
		})
	}
}
