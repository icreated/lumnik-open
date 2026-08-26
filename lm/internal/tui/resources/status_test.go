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
		{"Completed", KindCompleted},
		{"Failed", KindFailed},
		{"Cancelled", KindCancelled},
		{"completed", KindCompleted},
		{"FAILED", KindFailed},
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
