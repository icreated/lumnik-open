package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestObservationsList_printsTapeWithRawTimestamps(t *testing.T) {
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
	for _, want := range []string{"WORKFLOW", "commande", "CMD-1", "draft", "2026-08-01T10:00:00Z"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "0001-01-01") {
		t.Error("a null alertedAt must print empty, not a zero time")
	}
}
