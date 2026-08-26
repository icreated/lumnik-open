//go:build e2e

package e2e

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestSmoke_Version(t *testing.T) {
	url := os.Getenv("QUARKUS_URL")
	if url == "" {
		t.Skip("set QUARKUS_URL to run E2E tests (e.g. http://localhost:8080)")
	}
	t.Setenv("LUMNIK_URL", url)
	t.Setenv("LUMNIK_TOKEN", "dev-token")
	out, err := exec.Command("../../lm", "version").CombinedOutput()
	if err != nil {
		t.Fatalf("lm version: %v\n%s", err, out)
	}
	if !strings.Contains(string(out), "lm version") {
		t.Errorf("unexpected: %s", out)
	}
}

func TestSmoke_ModuleList(t *testing.T) {
	url := os.Getenv("QUARKUS_URL")
	if url == "" {
		t.Skip("set QUARKUS_URL to run E2E tests")
	}
	t.Setenv("LUMNIK_URL", url)
	t.Setenv("LUMNIK_TOKEN", "dev-token")
	out, err := exec.Command("../../lm", "module", "list").CombinedOutput()
	if err != nil {
		t.Fatalf("lm module list: %v\n%s", err, out)
	}
	if len(out) == 0 {
		t.Errorf("expected non-empty module list output")
	}
}
