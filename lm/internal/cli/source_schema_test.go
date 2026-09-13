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

// The schema card is the input of the generator loop: an integrator facing an SI with two
// hundred tables reads it once and emits one manifest per table. That needs the card as data,
// not as a column-aligned page —
//
//	lm source schema --scope atelier -o json | jq -r '.tables[].name'
//
// -o being a PERSISTENT root flag, `-o json` was already accepted here before it was honoured:
// the command printed its table and the pipeline downstream parsed prose. A silently ignored
// flag is worse than a rejected one.
func TestSourceSchemaHonoursJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"scope":"atelier","tables":[{"name":"connector.t_c_order","label":"t_c_order","columns":[{"name":"custcol_47","pgType":"text","label":"N° de chantier"}]}]}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"source", "schema", "--scope", "atelier", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var card struct {
		Scope  string `json:"scope"`
		Tables []struct {
			Name    string `json:"name"`
			Columns []struct {
				Name   string `json:"name"`
				PgType string `json:"pgType"`
			} `json:"columns"`
		} `json:"tables"`
	}
	if err := json.Unmarshal(buf.Bytes(), &card); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if card.Scope != "atelier" || len(card.Tables) != 1 {
		t.Fatalf("the card lost its shape: %+v", card)
	}
	if card.Tables[0].Name != "connector.t_c_order" || card.Tables[0].Columns[0].PgType != "text" {
		t.Errorf("a generator needs the table and its column types: %+v", card.Tables[0])
	}
}
