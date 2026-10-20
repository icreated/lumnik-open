package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestMigrationStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/migrations" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{
				"schema":         "core",
				"currentVersion": "9",
				"pending":        0,
				"lastApplied":    time.Now().Format(time.RFC3339),
			},
			{
				"schema":         "identity",
				"currentVersion": "3",
				"pending":        1,
				"lastApplied":    time.Now().Format(time.RFC3339),
			},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"migration", "status"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	output := buf.String()
	if !strings.Contains(output, "core") || !strings.Contains(output, "identity") {
		t.Errorf("unexpected output: %s", output)
	}
}

// A schema with no applied migration comes from the hub as "lastApplied": "" (#1149) — and an older
// or leaner reply may leave the field out. Neither may fail the decode: the whole status is lost
// exactly on the fresh hub where it matters most. Both render as "never": an em dash.
func TestMigrationStatus_neverAppliedRendersADash(t *testing.T) {
	for name, body := range map[string]string{
		"empty":  `[{"schema":"rag","currentVersion":"","pending":3,"lastApplied":""}]`,
		"absent": `[{"schema":"rag","currentVersion":"","pending":3}]`,
	} {
		t.Run(name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(body))
			}))
			defer srv.Close()
			setupCfg(t, srv.URL)

			out := runRoot(t, "migration", "status")
			if !strings.Contains(out, "LAST APPLIED") || !strings.Contains(out, "rag") || !strings.Contains(out, "—") {
				t.Errorf("want the rag row with LAST APPLIED —, got:\n%s", out)
			}
		})
	}
}

// The hub has no door to apply a migration — it runs them at every startup (#1142). The command
// that POSTed to a door answering 404 is gone: `lm migration apply` is an unknown command now.
func TestMigrationApply_isNoLongerACommand(t *testing.T) {
	_, err := execArgs(t, "migration", "apply")
	if err == nil || !strings.Contains(err.Error(), `unknown command "apply"`) {
		t.Errorf("lm migration apply: want unknown command, got %v", err)
	}
}
