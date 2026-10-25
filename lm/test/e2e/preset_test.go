//go:build e2e

package e2e

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

// applyManifest writes the YAML to a temp file and applies it via the real CLI; expansion is server-side.
func applyManifest(t *testing.T, yaml string) string {
	t.Helper()
	url := os.Getenv("QUARKUS_URL")
	if url == "" {
		t.Skip("set QUARKUS_URL to run E2E tests")
	}
	t.Setenv("LUMNIK_URL", url)
	t.Setenv("LUMNIK_TOKEN", "dev-token")

	f, err := os.CreateTemp(t.TempDir(), "preset-*.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(yaml); err != nil {
		t.Fatal(err)
	}
	f.Close()

	out, err := exec.Command("../../lm", "apply", "-f", f.Name()).CombinedOutput()
	if err != nil {
		t.Fatalf("lm apply: %v\n%s", err, out)
	}
	return string(out)
}

func TestPreset_DrfPage_Applies(t *testing.T) {
	yaml := `apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata: { name: e2e-drf, connector_type: rest-generic }
spec:
  base_url: https://api.example.com
  endpoints:
    - id: items
      path: /items
      method: GET
      pagination: { kind: drf-page }
      target: { kind: hybrid, table: e2e_drf_items, unique_key: "$.id", tags: [e2e] }
`
	out := applyManifest(t, yaml)
	// Assert the stable success shape: apply says it applied the connector. (A non-zero exit
	// is already caught in applyManifest; checking a success token avoids false negatives.)
	if !strings.Contains(out, "Applied connector") {
		t.Errorf("drf-page apply did not say it applied the connector: %s", out)
	}
}

func TestPreset_SpringPage_Applies(t *testing.T) {
	yaml := `apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata: { name: e2e-spring, connector_type: rest-generic }
spec:
  base_url: https://api.example.com
  endpoints:
    - id: items
      path: /items
      method: GET
      pagination: { kind: spring-page }
      target: { kind: hybrid, table: e2e_spring_items, unique_key: "$.id", tags: [e2e] }
`
	out := applyManifest(t, yaml)
	if !strings.Contains(out, "Applied connector") {
		t.Errorf("spring-page apply did not say it applied the connector: %s", out)
	}
}
