package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// Every request method that bypasses do() — apply, entity, validate, describe and
// rest-from-openapi (through doRaw), plus the still hand-built csv, csv-remote and sse —
// must send the token resolved by bearerToken — i.e. the fresh
// `lm login` Access token when present — NOT the static ctx.Token. A stale/expired static
// token alongside a fresh login token must not win. (Marie's real bug: after `lm login`,
// `lm apply` 401'd because it sent ctx.Token.)
//
// The server asserts the header itself (401 on mismatch), so there is no state shared
// between the handler goroutine and the test — a regressed method surfaces as an error.
func TestHandBuiltRequestsUseLoginAccessToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer FRESH-LOGIN" {
			w.WriteHeader(http.StatusUnauthorized) // a method sent the stale/wrong token
			return
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	newClient := func() *Client {
		return &Client{
			ctx: config.Context{
				Server:    srv.URL,
				Token:     "STALE-STATIC", // must NOT be sent when a fresh login token exists
				Access:    "FRESH-LOGIN",
				ExpiresAt: time.Now().Add(time.Hour).Unix(),
			},
			http:   srv.Client(),
			stream: srv.Client(), // sse's client — hand-built here like http, same server
		}
	}

	tempCSV := func() *os.File {
		f, err := os.CreateTemp(t.TempDir(), "*.csv")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = f.Close() }) // close the fd (Windows can't delete an open file)
		if _, err := f.WriteString("a,b\n1,2\n"); err != nil {
			t.Fatal(err)
		}
		_, _ = f.Seek(0, 0)
		return f
	}

	// Each call must succeed (right token → 200); if it regressed to the stale token it 401s.
	cases := map[string]func(*Client) error{
		"apply":    func(c *Client) error { _, err := c.ApplyManifest([]byte("kind: X")); return err },
		"entity":   func(c *Client) error { _, err := c.EntityApply([]byte("kind: View")); return err },
		"validate": func(c *Client) error { _, err := c.ValidateManifest([]byte("kind: X")); return err },
		"describe": func(c *Client) error { _, err := c.DescribeConnectorType("csv-file"); return err },
		"csv":      func(c *Client) error { _, err := c.CsvIngest(tempCSV(), "x.csv", CsvIngestOpts{Name: "n"}); return err },
		"csv-remote": func(c *Client) error {
			_, err := c.CsvIngestRemote(map[string]any{"kind": "local"}, "n", "s", nil, 0)
			return err
		},
		"openapi": func(c *Client) error { _, err := c.RestFromOpenApi("http://x/openapi.json", "n", nil); return err },
		"sse": func(c *Client) error {
			return c.SSEStream(context.Background(), "/events", nil, make(chan SSEEvent, 1))
		},
	}
	for name, call := range cases {
		t.Run(name, func(t *testing.T) {
			if err := call(newClient()); err != nil {
				t.Errorf("%s must send the login Access token (got auth error: %v)", name, err)
			}
		})
	}
}

// An empty-body 401/403 (Quarkus often returns no body) must not surface as a blank
// "API error 401: " — it should hint at the likely cause (Sergey's "on sait pas quelle erreur").
func TestAPIError_emptyBody_isHelpful(t *testing.T) {
	msg := (&APIError{Status: 401, Body: ""}).Error()
	if !strings.Contains(msg, "401") {
		t.Errorf("should mention the status: %q", msg)
	}
	low := strings.ToLower(msg)
	if !strings.Contains(low, "login") && !strings.Contains(low, "auth") {
		t.Errorf("an empty 401 should hint at auth/login, got %q", msg)
	}
}
