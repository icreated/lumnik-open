package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// A secret name is one path segment: typed "../connectors/x" it must reach the hub escaped, not
// as a walk to another door (#1113 security review).
func TestSecretDoors_escapeTheName(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.EscapedPath())
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	name := "../connectors/x"
	_ = c.SecretUpdate(name, "v", nil)
	_, _ = c.SecretDescribe(name)
	_ = c.SecretDelete(name)
	for _, p := range paths {
		if p != "/api/platform/secrets/..%2Fconnectors%2Fx" {
			t.Errorf("request went to %q", p)
		}
	}
	if len(paths) != 3 {
		t.Fatalf("%d requests, want 3", len(paths))
	}
}

// A rotation without --description keeps the label: the hub keeps it only for an absent field, so
// "no description" must not reach it as "" (#1249).
func TestSecretUpdate_withoutDescription_sendsNoField(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body = nil // one request, one body: no key left over from the previous call
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("request body: %v", err)
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	if err := c.SecretUpdate("S", "v", nil); err != nil {
		t.Fatal(err)
	}
	if _, sent := body["description"]; sent {
		t.Errorf("description sent: %v", body)
	}
	label := ""
	if err := c.SecretUpdate("S", "v", &label); err != nil {
		t.Fatal(err)
	}
	if d, sent := body["description"]; !sent || d != "" {
		t.Errorf("an explicit empty description must clear it: %v", body)
	}
}
