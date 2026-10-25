package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// SINCE renders like every other date column (output.HumanTime), not the raw ISO string
// the hub sends: -o json/yaml already carry the full-precision timestamp (Print marshals
// the struct directly for those formats, bypassing observationRow entirely), so the table
// buys nothing by staying raw — only a column nobody could read at a glance. Expected value
// computed the same way output.HumanTime computes it, not hardcoded, so this does not depend
// on the machine running it being on any particular timezone.
func TestObservationsList_printsTapeInLocalTime(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"workflow":"commande","docKey":"CMD-1","state":"draft",
			"since":"2026-08-01T10:00:00Z","firstObservedAt":"2026-07-30T09:00:00Z","alertedAt":null}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"observations", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	got := out.String()
	since, err := time.Parse(time.RFC3339, "2026-08-01T10:00:00Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	wantSince := since.In(time.Local).Format("2006-01-02 15:04")
	for _, want := range []string{"WORKFLOW", "commande", "CMD-1", "draft", wantSince} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "2026-08-01T10:00:00Z") {
		t.Errorf("SINCE should render local-formatted, not the raw ISO string:\n%s", got)
	}
	if strings.Contains(got, "0001-01-01") {
		t.Error("a null alertedAt must print empty, not a zero time")
	}
}

// #1368: the journal passes its filters through and prints, per move, the seconds spent in the
// state it left (raw, like MEDIAN_DETECTION_S).
func TestObservationsTransitions_filtersAndSecondsInFrom(t *testing.T) {
	var query string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/workflows/observations/transitions" {
			t.Errorf("path %s", r.URL.Path)
		}
		query = r.URL.RawQuery
		_, _ = w.Write([]byte(`[{"workflow":"commande","docKey":"CMD-1","from":"EN_COURS","to":"LIVREE",
			"enteredFromAt":"2026-08-01T10:00:00Z","observedAt":"2026-08-01T12:30:05Z"}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"observations", "transitions", "--workflow", "commande", "--doc", "CMD-1", "--limit", "5"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	for _, want := range []string{"doc=CMD-1", "limit=5", "workflow=commande"} {
		if !strings.Contains(query, want) {
			t.Errorf("query %q misses %q", query, want)
		}
	}
	got := out.String()
	for _, want := range []string{"IN_FROM_S", "CMD-1", "EN_COURS", "LIVREE", "9005"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}
