package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// WHEN renders like every other date column (output.HumanTime), not the raw ISO string the
// hub sends — see observations_test.go's TestObservationsList_printsTapeInLocalTime for why
// -o json/yaml already cover the scriptable case. Expected value computed, not hardcoded.
func TestOutputsList_printsDeliveriesInLocalTime(t *testing.T) {
	// Three rows so the status column's branches are all exercised, not just the 502 case:
	// an HTTP failure, a success, and a failure that never got a response at all.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[
			{"id":91,"integrationId":3,"integration":"n8n-prod",
			 "eventType":"decision.dormant.commande","httpStatus":502,"error":"connect timed out",
			 "durationMs":3001,"attemptedAt":"2026-08-15T09:12:04Z","succeeded":false},
			{"id":92,"integrationId":3,"integration":"n8n-prod",
			 "eventType":"data.row.changed","httpStatus":null,"error":"",
			 "durationMs":12,"attemptedAt":"2026-08-15T09:05:00Z","succeeded":true},
			{"id":93,"integrationId":4,"integration":"slack-alerts",
			 "eventType":"decision.dormant.other","httpStatus":null,"error":"connection refused",
			 "durationMs":50,"attemptedAt":"2026-08-15T08:00:00Z","succeeded":false}
		]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"outputs", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	got := out.String()
	attempted, err := time.Parse(time.RFC3339, "2026-08-15T09:12:04Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	wantWhen := attempted.In(time.Local).Format("2006-01-02 15:04")
	for _, want := range []string{"INTEGRATION", "n8n-prod", "decision.dormant.commande", "502",
		"connect timed out", wantWhen, "ok", "no response", "slack-alerts"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "2026-08-15T09:12:04Z") {
		t.Errorf("WHEN should render local-formatted, not the raw ISO string:\n%s", got)
	}
}
