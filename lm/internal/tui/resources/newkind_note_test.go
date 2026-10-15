package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// The TUI's apply had the advisories in hand — ValidateManifest returns them — and rendered them
// only when the manifest was REFUSED. Applied from here, a scopeless connector said nothing (#531).
// They cannot go to the issues view: a non-empty issues string means "not applied, e to re-edit",
// and this manifest is written. The toast carries the paths; `lm validate -f` carries the text.
func TestApplyNote(t *testing.T) {
	if got := applyNote(nil); got != "" {
		t.Errorf("nothing to advise, nothing appended to the toast: %q", got)
	}
	one := []client.ValidationError{{Path: "metadata.scopes", Message: "no scopes declared"}}
	if got, want := applyNote(one), "1 warning (metadata.scopes)"; got != want {
		t.Errorf("applyNote = %q, want %q", got, want)
	}
	two := append(one, client.ValidationError{Path: "spec.connection.password"})
	if got, want := applyNote(two), "2 warnings (metadata.scopes, spec.connection.password)"; got != want {
		t.Errorf("applyNote = %q, want %q", got, want)
	}
}

// The :new page applies a kind:App and used to throw the answer away: a tab this hub does not offer
// was kept and reported by the hub, and the toast said nothing.
func TestAppApplyNote(t *testing.T) {
	if got := appApplyNote([]byte(`{"name":"stock","scope":"stock","default":false}`)); got != "" {
		t.Errorf("nothing to say = %q", got)
	}
	resp := []byte(`{"name":"stock","warnings":[{"path":"spec.tabs[1]","severity":"WARNING","message":"m"}]}`)
	if got, want := appApplyNote(resp), "1 warning (spec.tabs[1])"; got != want {
		t.Errorf("appApplyNote = %q, want %q", got, want)
	}
	if got := appApplyNote([]byte(`not json`)); got != "" {
		t.Errorf("an unreadable answer must not invent a note: %q", got)
	}
}
