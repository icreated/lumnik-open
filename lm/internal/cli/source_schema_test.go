package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSourceSchemaRenders(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"scope":"atelier","tables":[{"name":"connector.t_c_order","label":"t_c_order","columns":[{"name":"custcol_47","pgType":"text","label":"N° de chantier"}]}]}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"source", "schema", "--scope", "atelier"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "connector.t_c_order") || !strings.Contains(out, "N° de chantier") {
		t.Errorf("schema output missing content: %s", out)
	}
}

func TestSourceRediscoverPrintsSummary(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"scope": "atelier", "tableCount": 3, "columnCount": 27,
			"enriched": true, "sourceType": "jdbc-ad",
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"source", "rediscover", "--scope", "atelier"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "3 tables") || !strings.Contains(out, "enriched") {
		t.Errorf("rediscover summary missing: %s", out)
	}
}
