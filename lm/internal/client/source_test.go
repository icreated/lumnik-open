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
			Created: []JdbcAddEntry{{ConnectorName: "x", MappingName: "y"}},
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

func TestJdbcDiscoverSendsSchema(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var got JdbcDiscoverRequest
		_ = json.NewDecoder(r.Body).Decode(&got)
		if got.Schema != "myschema" {
			t.Errorf("schema not forwarded: %s", got.Schema)
		}
		_ = json.NewEncoder(w).Encode(JdbcDiscoverResponse{Tables: []JdbcTable{{Name: "t1"}}})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	resp, err := c.JdbcDiscover(JdbcDiscoverRequest{Schema: "myschema"})
	if err != nil || len(resp.Tables) != 1 {
		t.Fatalf("unexpected: %+v err=%v", resp, err)
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
