package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/output"
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

// -o is a persistent root flag, so `lm describe connector-type` accepted -o json and ignored
// it, always printing the tree rendering instead (#532).
func TestDescribeConnectorTypeHonoursJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"fields":[{"name":"base_url","type":"STRING","importance":"HIGH","required":true,"doc":"Base URL"}]}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"describe", "connector-type", "rest-generic", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got struct {
		Fields []struct {
			Name     string `json:"name"`
			Required bool   `json:"required"`
		} `json:"fields"`
	}
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if len(got.Fields) != 1 || got.Fields[0].Name != "base_url" || !got.Fields[0].Required {
		t.Fatalf("the config def lost its shape: %+v", got.Fields)
	}
}

func TestRenderConfigDef_topLevelFields_printed(t *testing.T) {
	def := output.ConfigDef{Fields: []output.ConfigDefField{
		{Name: "base_url", Type: "STRING", Importance: "HIGH", Required: true, Doc: "Base URL"},
		{Name: "default_rate_limit", Type: "STRING", Importance: "MEDIUM", Required: false, Doc: "Rate limit, e.g. 100/s"},
	}}
	out := &bytes.Buffer{}
	renderConfigDef(out, "rest-generic", def)
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
