package style

import (
	"fmt"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/brand"
)

// lumière is declared twice by necessity: brand states it as a hex string a tview tag can carry,
// style states it as a tcell colour. Two spellings of one colour drift silently — unless pinned.
func TestTheGoldIsOneColour(t *testing.T) {
	if got := fmt.Sprintf("#%06x", ChromePrimary.Hex()); got != brand.GoldHex {
		t.Errorf("brand.GoldHex is %s but the TUI chrome is %s — lumière must be one colour", brand.GoldHex, got)
	}
}

func TestNoColorEnv(t *testing.T) {
	t.Setenv("NO_COLOR", "1")
	if ColorsEnabled() {
		t.Errorf("NO_COLOR=1 should disable colors")
	}
	t.Setenv("NO_COLOR", "")
	if !ColorsEnabled() {
		t.Errorf("NO_COLOR unset should enable colors")
	}
}
