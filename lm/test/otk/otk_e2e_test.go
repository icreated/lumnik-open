//go:build otk_e2e

// On-demand real-Keycloak E2E smoke. Run against a live selfhost stack:
//
//	OTK_E2E_TOKEN_URL=http://localhost:8180/realms/lumnik/protocol/openid-connect/token \
//	OTK_E2E_CLIENT=lm-cli OTK_E2E_USER=integrator OTK_E2E_PASS=... \
//	LUMNIK_URL=http://localhost:8080 \
//	go test -tags otk_e2e -run TestOTKAuthE2E ./test/otk/ -v
package otk

import (
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/test/otk/auth"
)

func TestOTKAuthE2E(t *testing.T) {
	if os.Getenv("OTK_E2E_TOKEN_URL") == "" || os.Getenv("LUMNIK_URL") == "" {
		t.Skip("set OTK_E2E_TOKEN_URL + LUMNIK_URL to run the real-Keycloak E2E smoke")
	}
	kc := auth.Keycloak{
		TokenURL: os.Getenv("OTK_E2E_TOKEN_URL"),
		ClientID: os.Getenv("OTK_E2E_CLIENT"),
		Username: os.Getenv("OTK_E2E_USER"),
		Password: os.Getenv("OTK_E2E_PASS"),
	}
	base := strings.TrimRight(os.Getenv("LUMNIK_URL"), "/") + "/api/platform/connectors"
	cl := &http.Client{Timeout: 15 * time.Second}

	// Negative: no Authorization header → 401.
	resp, err := cl.Get(base)
	if err != nil {
		t.Fatalf("anon GET: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("anonymous request: status %d, want 401", resp.StatusCode)
	}

	// Positive: real integrator token → 200.
	tok, err := kc.Integrator("1", "1")
	if err != nil {
		t.Fatalf("keycloak token: %v", err)
	}
	req, err := http.NewRequest("GET", base, nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	resp2, err := cl.Do(req)
	if err != nil {
		t.Fatalf("authed GET: %v", err)
	}
	defer resp2.Body.Close()
	body, _ := io.ReadAll(resp2.Body)
	if resp2.StatusCode != 200 {
		t.Fatalf("integrator request: status %d body %q, want 200", resp2.StatusCode, string(body))
	}
}
