package client

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestAppList_getsTheRegistryAndKeepsTheBodyVerbatim(t *testing.T) {
	const body = `[{"name":"stock","scope":"stock","futureField":1}]`
	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	got, err := New(config.Context{Server: srv.URL}).AppList()
	if err != nil {
		t.Fatal(err)
	}
	if gotMethod != http.MethodGet || gotPath != "/api/apps" {
		t.Errorf("called %s %s, want GET /api/apps", gotMethod, gotPath)
	}
	if string(got) != body {
		t.Errorf("the hub's JSON must reach the caller untouched (a field this lm does not know included), got %s", got)
	}
}
