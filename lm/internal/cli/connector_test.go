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

// A multi-endpoint connector starts one run per endpoint; the installer follows each of them.
func TestConnectorRun_printsEveryRunId(t *testing.T) {
	connID := "cccccccc-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/connectors":
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": connID, "name": "acme"}})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/run"):
			w.WriteHeader(http.StatusAccepted)
			_ = json.NewEncoder(w).Encode(map[string]any{"runId": "run-1", "runIds": []string{"run-1", "run-2"}})
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
	cmd.SetArgs([]string{"connector", "run", "acme"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if want := "run scheduled: run-1\nrun scheduled: run-2\n"; buf.String() != want {
		t.Errorf("want %q, got %q", want, buf.String())
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

// A paused connector is listed as paused: its cron alone read as a schedule still running, and only
// -o json said otherwise (#1389). The pause stops the schedule, so it is said beside it, and only
// where there is one.
func TestConnectorList_saysWhichScheduleIsPaused(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "11111111-1111-1111-1111-111111111111", "name": "cmms-retired",
				"typeId": "jdbc-generic", "tenantId": 1, "schedule": "0 2 * * *", "enabled": false},
			{"id": "22222222-2222-2222-2222-222222222222", "name": "erp-nightly",
				"typeId": "jdbc-generic", "tenantId": 1, "schedule": "0 3 * * *", "enabled": true},
			{"id": "33333333-3333-3333-3333-333333333333", "name": "manual-only",
				"typeId": "csv-file", "tenantId": 1, "enabled": false},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	out := runRoot(t, "connector", "list")
	if !strings.Contains(out, "0 2 * * * (paused)") {
		t.Errorf("the paused connector's schedule should say so:\n%s", out)
	}
	if strings.Contains(out, "0 3 * * * (paused)") {
		t.Errorf("a running schedule is not paused:\n%s", out)
	}
	// No cron, nothing the pause stops: the cell stays empty.
	if strings.Count(out, "(paused)") != 1 {
		t.Errorf("only the scheduled connector can read paused:\n%s", out)
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
