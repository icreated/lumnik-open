package cli

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const searchIndexManifest = "apiVersion: search.lumnik.io/v1\nkind: SearchIndex\nmetadata:\n  name: pool-products\nspec:\n  entity: Product\n"

func writeSearchManifest(t *testing.T) string {
	t.Helper()
	f := filepath.Join(t.TempDir(), "pool-products.yaml")
	if err := os.WriteFile(f, []byte(searchIndexManifest), 0o600); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestSearchApply_postsTheManifestByteForByteAndNamesTheIndex(t *testing.T) {
	var gotRoute, gotBody, gotType string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRoute = r.Method + " " + r.URL.Path
		gotType = r.Header.Get("Content-Type")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		_, _ = fmt.Fprint(w, `{"name":"pool-products","entity":"Product","status":"valid"}`)
	}))
	t.Cleanup(srv.Close)

	out, _, err := runLm(t, srv, "search", "apply", "-f", writeSearchManifest(t))
	if err != nil {
		t.Fatalf("apply: %v", err)
	}
	if gotRoute != "POST /api/search-indexes/apply" {
		t.Errorf("hit %q, want POST /api/search-indexes/apply", gotRoute)
	}
	if gotType != "application/yaml" {
		t.Errorf("Content-Type %q, want application/yaml", gotType)
	}
	if gotBody != searchIndexManifest {
		t.Errorf("manifest was rewritten in flight:\n%q", gotBody)
	}
	for _, want := range []string{"pool-products", "Product", "valid"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q: %s", want, out)
		}
	}
}

func TestSearchApply_jsonOutputIsTheHubBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprint(w, `{"name":"pool-products","entity":"Product","status":"valid"}`)
	}))
	t.Cleanup(srv.Close)

	out, _, err := runLm(t, srv, "search", "apply", "-f", writeSearchManifest(t), "-o", "json")
	if err != nil {
		t.Fatalf("apply -o json: %v", err)
	}
	if !strings.Contains(out, `"name": "pool-products"`) && !strings.Contains(out, `"name":"pool-products"`) {
		t.Errorf("json output lacks the name: %s", out)
	}
	if strings.Contains(out, "Applied") {
		t.Errorf("json output carries the table sentence: %s", out)
	}
}

func TestSearchApply_serverRefusalsKeepTheirMeaning(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"validation", http.StatusBadRequest, `{"error":"spec.entity: entity 'Product' not found"}`, "entity 'Product' not found"},
		{"scope", http.StatusForbidden, `{"error":"scope not granted"}`, "scope not granted"},
		{"conflict", http.StatusConflict, `{"error":"entity 'Product' changed while this SearchIndex was being applied — apply it again"}`, "apply it again"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = fmt.Fprint(w, tc.body)
			}))
			t.Cleanup(srv.Close)
			_, _, err := runLm(t, srv, "search", "apply", "-f", writeSearchManifest(t))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to carry %q", err, tc.want)
			}
		})
	}
}

func TestSearchApply_requiresAFile(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(srv.Close)
	if _, _, err := runLm(t, srv, "search", "apply"); err == nil {
		t.Fatal("expected -f to be required")
	}
}

func TestSearchRm_deletesByNameAndSaysSo(t *testing.T) {
	var gotRoute string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRoute = r.Method + " " + r.URL.EscapedPath()
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(srv.Close)

	out, _, err := runLm(t, srv, "search", "rm", "pool-products")
	if err != nil {
		t.Fatalf("rm: %v", err)
	}
	if gotRoute != "DELETE /api/search-indexes/pool-products" {
		t.Errorf("hit %q, want DELETE /api/search-indexes/pool-products", gotRoute)
	}
	if !strings.Contains(out, "pool-products") {
		t.Errorf("output lacks the name: %s", out)
	}
}

func TestSearchRm_serverRefusalsKeepTheirMeaning(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"unknown", http.StatusNotFound, `{"error":"no search index named 'ghost'"}`, "no search index named"},
		{"scope", http.StatusForbidden, `{"error":"scope not granted"}`, "scope not granted"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = fmt.Fprint(w, tc.body)
			}))
			t.Cleanup(srv.Close)
			_, _, err := runLm(t, srv, "search", "rm", "ghost")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to carry %q", err, tc.want)
			}
		})
	}
}
