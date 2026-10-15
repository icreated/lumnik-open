package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// Model-bound calls (ask, chat, view generate) must survive a server that thinks
// longer than the fast client's 30s budget: CPU inference legitimately takes
// minutes on a cold model. The test shrinks the fast budget to milliseconds and
// serves slower than it — a call still riding c.http fails here.
func TestModelBoundCallsSurviveSlowServer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"answer":"ok","sql":"SELECT 1","rowCount":0,"rows":[]}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static-token"})
	c.http.Timeout = 20 * time.Millisecond // the fast budget, shrunk for the test

	// Control: prove the probe CAN fail — a plain POST on the fast client dies.
	if err := c.POST("/anything", map[string]any{}, nil); err == nil {
		t.Fatal("control: the fast client should have timed out — the probe cannot fail")
	}

	if _, err := c.RagAsk("q", "", ""); err != nil {
		t.Errorf("RagAsk must ride the slow client: %v", err)
	}
	if _, err := c.RagChat("q", "", 3); err != nil {
		t.Errorf("RagChat must ride the slow client: %v", err)
	}
	if _, err := c.RagChatScoped("q", "", "achat", 3); err != nil {
		t.Errorf("RagChatScoped must ride the slow client: %v", err)
	}
	if _, err := c.ViewGenerate("achat", "les clients gold"); err != nil {
		t.Errorf("ViewGenerate must ride the slow client: %v", err)
	}
}

// EntityResolve replays a resolution pass synchronously over one entity's WHOLE extent — a bulk
// SQL scan across every source, not an LLM call, but the same reason as the model-bound calls
// above: it can legitimately outrun the fast client's 30s budget on a large entity, and a
// timeout there loses the pass summary even though the hub-side replay keeps running to
// completion regardless. Same shrink-and-prove pattern, its own test: the justification differs
// even though the mechanism is identical.
func TestEntityResolveSurvivesSlowServer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"rowsSeen":0,"resolved":0,"ambiguous":0,"unresolvedNoRule":0,
			"unresolvedProvider":0,"newlyMinted":0,"providerCalls":0,"providerFailures":0,"ruleHits":{}}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static-token"})
	c.http.Timeout = 20 * time.Millisecond // the fast budget, shrunk for the test

	if err := c.POST("/anything", map[string]any{}, nil); err == nil {
		t.Fatal("control: the fast client should have timed out — the probe cannot fail")
	}

	if _, err := c.EntityResolve("Customer"); err != nil {
		t.Errorf("EntityResolve must ride the slow client: %v", err)
	}
}
