package newkind

import (
	"strings"
	"testing"
)

func TestClassifyManifest(t *testing.T) {
	// Invalid YAML must be told apart from a missing/unknown kind (review round 1).
	if _, _, err := ClassifyManifest("kind: [unclosed"); err == nil {
		t.Error("syntax error must surface as err, not as missing-kind")
	}
	if class, kind, err := ClassifyManifest("kind: View"); class != "" || kind != "View" || err != nil {
		t.Errorf("unknown-but-present kind: got (%q,%q,%v)", class, kind, err)
	}
	if class, kind, _ := ClassifyManifest("apiVersion: x"); class != "" || kind != "" {
		t.Errorf("missing kind: got (%q,%q)", class, kind)
	}
	if class, kind, err := ClassifyManifest("kind: App"); class != "app" || kind != "App" || err != nil {
		t.Errorf("App: got (%q,%q,%v), want (\"app\",\"App\",nil)", class, kind, err)
	}
	// A scalar is VALID yaml that just isn't a manifest — missing kind, not a syntax error.
	if class, kind, err := ClassifyManifest("no kind at all"); class != "" || kind != "" || err != nil {
		t.Errorf("scalar yaml: got (%q,%q,%v), want missing-kind with nil err", class, kind, err)
	}
}

func TestManifestClass(t *testing.T) {
	cases := []struct{ in, want string }{
		{"apiVersion: x\nkind: Connector\nmetadata: {}", "connector"},
		{"kind: Entity\nspec: {}", "entity"},
		{"kind: Process\n", "process"},
		{"kind: Workflow\n", "workflow"},
		{"kind: App\nspec: {}", "app"},
		{"kind: View\n", ""}, // rung 4 — not routable here
		{"no kind at all", ""},
	}
	for _, c := range cases {
		if got := ManifestClass(c.in); got != c.want {
			t.Errorf("ManifestClass(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestFormatIssues(t *testing.T) {
	out := FormatIssues(
		[]Issue{{Path: "spec.transport.bucket", Message: "required field missing",
			Recommendations: []string{"Add: bucket: my-bucket"}}},
		[]Issue{{Path: "metadata.scopes", Message: "no scopes declared"}})
	for _, want := range []string{"spec.transport.bucket", "required field missing",
		"Add: bucket: my-bucket", "WARN", "metadata.scopes"} {
		if !strings.Contains(out, want) {
			t.Errorf("FormatIssues output missing %q:\n%s", want, out)
		}
	}
}
