package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOutputsList_printsDeliveriesWithRawTimestamps(t *testing.T) {
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
	for _, want := range []string{"INTEGRATION", "n8n-prod", "decision.dormant.commande", "502",
		"connect timed out", "2026-08-15T09:12:04Z", "ok", "no response", "slack-alerts"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}
