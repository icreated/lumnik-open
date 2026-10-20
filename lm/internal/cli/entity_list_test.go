package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// lm entity list names the entities the caller may read — the names lm entity get takes (#1114).
func TestEntityList_printsTheReadableEntities(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/entities" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`[{"name":"Customer","scopes":["crm"],"sources":["t_crm","t_erp"],"resolution":true}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)
	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"entity", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	for _, want := range []string{"NAME", "Customer", "t_crm,t_erp", "crm", "resolution"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("missing %q:\n%s", want, out.String())
		}
	}
}
