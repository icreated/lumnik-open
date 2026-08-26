package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// doRawTestClient builds a client pointed at srv, with the server URL taken verbatim
// (trailing slash included) so the path-joining rule is exercised as written.
func doRawTestClient(server string, h *http.Client) *Client {
	return &Client{ctx: config.Context{Server: server}, http: h}
}

func TestDoRawReturnsBody(t *testing.T) {
	var gotMethod, gotPath, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		buf := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(buf)
		gotBody = string(buf)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()

	data, err := doRawTestClient(srv.URL, srv.Client()).
		doRaw("POST", "/api/things/apply", "application/yaml", "application/json", []byte("kind: X"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if string(data) != `{"ok":true}` {
		t.Errorf("body = %q, want the server's response verbatim", string(data))
	}
	if gotMethod != "POST" || gotPath != "/api/things/apply" {
		t.Errorf("request was %s %s, want POST /api/things/apply", gotMethod, gotPath)
	}
	if gotBody != "kind: X" {
		t.Errorf("server received body %q, want %q", gotBody, "kind: X")
	}
}

func TestDoRawRefusalBecomesAPIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "37")
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":"scope not granted"}`))
	}))
	defer srv.Close()

	_, err := doRawTestClient(srv.URL, srv.Client()).
		doRaw("GET", "/api/views", "", "application/json", nil)
	var apiErr *APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("error = %v (%T), want *APIError", err, err)
	}
	if apiErr.Status != 403 {
		t.Errorf("status = %d, want 403", apiErr.Status)
	}
	if apiErr.Body != `{"error":"scope not granted"}` {
		t.Errorf("body = %q, want the refusal payload verbatim", apiErr.Body)
	}
	if apiErr.RetryAfter != "37" {
		t.Errorf("RetryAfter = %q, want %q", apiErr.RetryAfter, "37")
	}
	if got, want := apiErr.Error(), "access refused (403): scope not granted"; got != want {
		t.Errorf("Error() = %q, want %q", got, want)
	}
}

func TestDoRawSetsHeadersOnlyWhenNonEmpty(t *testing.T) {
	var ct, accept string
	var ctPresent, acceptPresent bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ct, ctPresent = r.Header.Get("Content-Type"), len(r.Header.Values("Content-Type")) > 0
		accept, acceptPresent = r.Header.Get("Accept"), len(r.Header.Values("Accept")) > 0
	}))
	defer srv.Close()
	c := doRawTestClient(srv.URL, srv.Client())

	if _, err := c.doRaw("POST", "/x", "application/yaml", "application/json", []byte("y")); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ct != "application/yaml" || accept != "application/json" {
		t.Errorf("headers = (%q, %q), want (application/yaml, application/json)", ct, accept)
	}

	if _, err := c.doRaw("GET", "/x", "", "", nil); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ctPresent {
		t.Errorf("Content-Type sent as %q, want no header at all when contentType is empty", ct)
	}
	if acceptPresent {
		t.Errorf("Accept sent as %q, want no header at all when accept is empty", accept)
	}
}

// TestDoRawWrapsReadAllError pins the semantic change from the doRaw
// refactor: a handler that declares a Content-Length larger than what it
// actually writes, then closes, must surface as an error from doRaw rather
// than silently returning a truncated body.
func TestDoRawWrapsReadAllError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("short"))
	}))
	defer srv.Close()

	_, err := doRawTestClient(srv.URL, srv.Client()).
		doRaw("GET", "/x", "", "application/json", nil)
	if err == nil {
		t.Fatal("doRaw returned no error for a body shorter than its declared Content-Length")
	}
}

func TestDoRawJoinsPathWithoutDoubleSlash(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
	}))
	defer srv.Close()

	if _, err := doRawTestClient(srv.URL+"/", srv.Client()).
		doRaw("GET", "/api/workflows", "", "application/json", nil); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if strings.Contains(gotPath, "//") || gotPath != "/api/workflows" {
		t.Errorf("path = %q, want /api/workflows from a server URL ending in a slash", gotPath)
	}
}
