package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMappingList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "shopify-customers", "version": 1, "target": "business_partner.BusinessPartner"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"mapping", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "shopify-customers") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

// -o being a persistent root flag, `mapping get` (without --raw) accepted -o json and ignored
// it, always printing the aligned card (#532). --raw stays untouched: it feeds `mapping apply`.
func TestMappingGetHonoursJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "shopify-customers", "version": 2, "target": "business_partner.BusinessPartner",
			"mode": "merge", "dataset": 1, "row": 3, "cell": 0,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"mapping", "get", "shopify-customers", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if got["target"] != "business_partner.BusinessPartner" || got["mode"] != "merge" {
		t.Fatalf("the mapping detail lost its shape: %v", got)
	}
}
