package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestRagRediscover(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path + "?" + r.URL.RawQuery
		_ = json.NewEncoder(w).Encode(map[string]any{
			"scope": "atelier", "tableCount": 3, "columnCount": 27,
			"enriched": true, "sourceType": "jdbc-ad",
		})
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})

	res, err := c.RagRediscover("atelier")
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/api/platform/rag/rediscover?scope=atelier" {
		t.Errorf("path = %q", gotPath)
	}
	if res.TableCount != 3 || !res.Enriched || res.SourceType != "jdbc-ad" {
		t.Errorf("bad result: %+v", res)
	}
}

func TestRagSchema(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"scope":"atelier","tables":[{"name":"connector.t_c_order","label":"t_c_order","columns":[{"name":"custcol_47","pgType":"text","label":"N° de chantier"}]}]}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})

	card, err := c.RagSchema("atelier")
	if err != nil {
		t.Fatal(err)
	}
	if len(card.Tables) != 1 || card.Tables[0].Name != "connector.t_c_order" {
		t.Fatalf("bad card: %+v", card)
	}
	if card.Tables[0].Columns[0].Label != "N° de chantier" {
		t.Errorf("label = %q", card.Tables[0].Columns[0].Label)
	}
}
