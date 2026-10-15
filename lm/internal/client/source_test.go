package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestJdbcTestSendsExpectedBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/sources/jdbc/test" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		var got JdbcTestRequest
		_ = json.NewDecoder(r.Body).Decode(&got)
		if got.URL != "jdbc:postgresql://h/d" {
			t.Errorf("url not forwarded: %s", got.URL)
		}
		_ = json.NewEncoder(w).Encode(JdbcTestResponse{OK: true, Dialect: "postgres"})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	resp, err := c.JdbcTest(JdbcTestRequest{URL: "jdbc:postgresql://h/d", User: "u", Password: "p"})
	if err != nil || !resp.OK {
		t.Fatalf("expected ok response, got %+v err=%v", resp, err)
	}
}

func TestJdbcAddReturnsCreatedEntries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/add") {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(JdbcAddResponse{
			Created: []JdbcAddEntry{{ConnectorName: "x"}},
		})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	resp, err := c.JdbcAdd(JdbcAddRequest{SourceName: "s"})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Created) != 1 {
		t.Errorf("expected 1 created, got %d", len(resp.Created))
	}
}

func TestJdbcRequestsSendSchema(t *testing.T) {
	cases := []struct {
		name string
		call func(c *Client) error
	}{
		{"discover", func(c *Client) error { _, err := c.JdbcDiscover(JdbcDiscoverRequest{Schema: "erp"}); return err }},
		{"add", func(c *Client) error { _, err := c.JdbcAdd(JdbcAddRequest{SourceName: "s", Schema: "erp"}); return err }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var got map[string]any
				_ = json.NewDecoder(r.Body).Decode(&got)
				if got["schema"] != "erp" {
					t.Errorf("schema not forwarded: %v", got["schema"])
				}
				_, _ = w.Write([]byte("{}"))
			}))
			defer srv.Close()
			if err := tc.call(New(config.Context{Server: srv.URL})); err != nil {
				t.Fatalf("unexpected: %v", err)
			}
		})
	}
}

func TestJdbcListTablesUsesSourceName(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/sources/jdbc/my-source/tables" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(JdbcListTablesResponse{SourceName: "my-source"})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	resp, err := c.JdbcListTables("my-source")
	if err != nil || resp.SourceName != "my-source" {
		t.Fatalf("unexpected: %+v err=%v", resp, err)
	}
}

func TestJdbcDiscoverDecodesTheUnsupportedColumns(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"tables":[
		  {"schema":"ERP","name":"DOCS","primaryKey":[],"columns":[],"rowEstimate":1,"unsupportedColumns":["BODY BLOB"]},
		  {"schema":"ERP","name":"ORDERS","primaryKey":[],"columns":[],"rowEstimate":2}]}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	resp, err := c.JdbcDiscover(JdbcDiscoverRequest{URL: "jdbc:oracle:thin:@//h/x", User: "u", Password: "p"})
	if err != nil {
		t.Fatal(err)
	}
	if got := resp.Tables[0].UnsupportedColumns; len(got) != 1 || got[0] != "BODY BLOB" {
		t.Errorf("unsupported columns = %v, want [BODY BLOB]", got)
	}
	if len(resp.Tables[1].UnsupportedColumns) != 0 {
		t.Errorf("a table without the field must decode to none, got %v", resp.Tables[1].UnsupportedColumns)
	}
}
