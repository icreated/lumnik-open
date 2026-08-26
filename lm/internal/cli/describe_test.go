package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestDescribeConnectorType_unknownKind_errors(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
		_, _ = w.Write([]byte(`{"error":"unknown","available":["rest-generic"]}`))
	}))
	defer srv.Close()

	c := client.New(config.Context{Server: srv.URL})
	_, err := c.DescribeConnectorType("nope")
	if err == nil {
		t.Fatal("expected error on unknown kind")
	}
}

func TestRenderConfigDef_topLevelFields_printed(t *testing.T) {
	def := configDefBlock{Fields: []configDefField{
		{Name: "base_url", Type: "STRING", Importance: "HIGH", Required: true, Doc: "Base URL"},
		{Name: "default_rate_limit", Type: "STRING", Importance: "MEDIUM", Required: false, Doc: "Rate limit, e.g. 100/s"},
	}}
	out := &bytes.Buffer{}
	_ = renderConfigDef(out, "rest-generic", def, 0)
	s := out.String()
	if !strings.Contains(s, "Connector type: rest-generic") {
		t.Errorf("missing header, got: %s", s)
	}
	if !strings.Contains(s, "base_url") || !strings.Contains(s, "REQUIRED") {
		t.Errorf("missing base_url/REQUIRED, got: %s", s)
	}
	if !strings.Contains(s, "default_rate_limit") || !strings.Contains(s, "optional") {
		t.Errorf("missing default_rate_limit/optional, got: %s", s)
	}
}
