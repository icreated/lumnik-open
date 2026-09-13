package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func writeFile(t *testing.T, path, content string) error {
	t.Helper()
	return os.WriteFile(path, []byte(content), 0o600)
}

func TestConnectorList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "11111111-1111-1111-1111-111111111111", "name": "shopify", "typeId": "http-poll", "tenantId": 1},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "shopify") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

func TestConnectorApply(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var payload map[string]any
		_ = json.Unmarshal(body, &payload)
		if payload["name"] != "applied" {
			t.Errorf("bad payload: %v", payload)
		}
		w.WriteHeader(201)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": "abc", "name": "applied"})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	tmp := t.TempDir() + "/c.yaml"
	_ = writeFile(t, tmp, "tenantId: 1\nname: applied\ntypeId: hello-world\nenabled: true\nconfig:\n  count: 10\n")

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "apply", "-f", tmp})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
}

func TestConnectorPause_printsPaused(t *testing.T) {
	connID := "cccccccc-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/connectors":
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": connID, "name": "acme"}})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/pause"):
			_ = json.NewEncoder(w).Encode(map[string]any{"id": connID, "enabled": false})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "pause", "acme"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "paused: acme") {
		t.Errorf("expected 'paused: acme', got: %s", buf.String())
	}
}

func TestConnectorResume_printsResumed(t *testing.T) {
	connID := "dddddddd-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/connectors":
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": connID, "name": "acme"}})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/resume"):
			_ = json.NewEncoder(w).Encode(map[string]any{"id": connID, "enabled": true})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "resume", "acme"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "resumed: acme") {
		t.Errorf("expected 'resumed: acme', got: %s", buf.String())
	}
}

// `lm connector list` shows the run WINDOW beside the schedule: without it a connector confined
// to a nightly window looked, in the list, exactly like one that may run at any hour (#499).
func TestConnectorList_showsTheRunWindowBesideTheSchedule(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "11111111-1111-1111-1111-111111111111", "name": "erp-nightly",
				"typeId": "jdbc-generic", "tenantId": 1,
				"schedule": "*/15 * * * *", "window": "22:00-06:00"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"WINDOW", "22:00-06:00", "*/15 * * * *"} {
		if !strings.Contains(buf.String(), want) {
			t.Errorf("missing %q in:\n%s", want, buf.String())
		}
	}
}

// `lm connector get` writes the flat fields once each, and `connector apply` reads back what it
// wrote. A `schedule` that appeared both at the top level and inside `config` left an integrator
// editing the copy the hub reads with nothing — the scheduler selects on the column.
func TestConnectorGet_writesEachFieldOnce(t *testing.T) {
	one := map[string]any{
		"id": "11111111-1111-1111-1111-111111111111", "name": "erp-nightly",
		"typeId": "jdbc-generic", "tenantId": 1, "enabled": true,
		"schedule": "*/15 * * * *", "window": "22:00-06:00",
		"config": map[string]any{"kind": "jdbc-generic"},
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/connectors") {
			_ = json.NewEncoder(w).Encode([]map[string]any{one})
			return
		}
		_ = json.NewEncoder(w).Encode(one)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"connector", "get", "11111111-1111-1111-1111-111111111111"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if n := strings.Count(buf.String(), "schedule:"); n != 1 {
		t.Errorf("schedule appears %d times, want 1:\n%s", n, buf.String())
	}
	if !strings.Contains(buf.String(), "window: 22:00-06:00") {
		t.Errorf("the window must survive the round trip:\n%s", buf.String())
	}
}
