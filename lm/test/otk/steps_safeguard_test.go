//go:build otk

package otk

import "testing"

// TestNeedsSafeguardCleanup locks the After-hook safeguard decision. The critical
// case is a nil baseline (the pre-command snapshot never succeeded, so apply never
// ran): the safeguard must NOT run, otherwise it would treat every pre-existing
// connector as a leak and mass-delete the hub.
func TestNeedsSafeguardCleanup(t *testing.T) {
	cases := []struct {
		name    string
		cleaned bool
		before  map[string]bool
		want    bool
	}{
		{"already cleaned by the step -> skip", true, map[string]bool{"a": true}, false},
		{"not cleaned, baseline captured -> run", false, map[string]bool{"a": true}, true},
		{"not cleaned, empty baseline captured -> run", false, map[string]bool{}, true},
		{"not cleaned, nil baseline (apply never reached) -> skip (avoid mass-delete)", false, nil, false},
		{"cleaned, nil baseline -> skip", true, nil, false},
	}
	for _, c := range cases {
		if got := needsSafeguardCleanup(c.cleaned, c.before); got != c.want {
			t.Errorf("%s: needsSafeguardCleanup(%v, %v) = %v, want %v",
				c.name, c.cleaned, c.before, got, c.want)
		}
	}
}
