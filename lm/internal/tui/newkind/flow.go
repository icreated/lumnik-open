package newkind

import (
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"
)

// ClassifyManifest routes an edited manifest from its top-level kind and tells
// apart the three refusal shapes: a YAML syntax error (err), a present-but-unknown
// kind (class "" with the kind value for the hint), and a missing kind.
func ClassifyManifest(yamlText string) (class string, kind string, err error) {
	// Two-step: any-unmarshal separates true syntax errors from valid-but-not-a-mapping
	// YAML (a bare scalar is valid YAML that just isn't a manifest — missing kind).
	var raw any
	if err := yaml.Unmarshal([]byte(yamlText), &raw); err != nil {
		return "", "", err
	}
	m, ok := raw.(map[string]any)
	if !ok {
		return "", "", nil
	}
	k, _ := m["kind"].(string)
	switch k {
	case "Connector":
		return "connector", k, nil
	case "Entity":
		return "entity", k, nil
	case "Process":
		return "process", k, nil
	case "Workflow":
		return "workflow", k, nil
	case "App":
		return "app", k, nil
	}
	return "", k, nil
}

// ManifestClass is the routing-only view of ClassifyManifest.
func ManifestClass(yamlText string) string {
	class, _, _ := ClassifyManifest(yamlText)
	return class
}

// Issue is the client-agnostic validation line (path → message → recommendations).
type Issue struct {
	Path            string
	Message         string
	Recommendations []string
}

// FormatIssues renders the panel body: errors first, warnings prefixed WARN,
// recommendations indented — the server's field-by-field truth, verbatim.
func FormatIssues(errs, warns []Issue) string {
	var b strings.Builder
	for _, e := range errs {
		fmt.Fprintf(&b, "[red]✗[white] %s — %s\n", e.Path, e.Message)
		for _, r := range e.Recommendations {
			fmt.Fprintf(&b, "      %s\n", r)
		}
	}
	for _, w := range warns {
		fmt.Fprintf(&b, "[yellow]WARN[white] %s — %s\n", w.Path, w.Message)
		for _, r := range w.Recommendations {
			fmt.Fprintf(&b, "      %s\n", r)
		}
	}
	return b.String()
}
