package cli

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// `lm dlq list` and `lm run list` print ids through shortID — the first 8 characters, with no
// mark that anything was cut (unlike truncate, which appends "…"). So an operator copies what the
// ID column shows and hands it to `lm dlq get`. The hub binds @PathParam("id") UUID, which cannot
// parse 8 characters. What the tool displays has to be what the tool accepts.
func TestResolveIDPrefix(t *testing.T) {
	ids := []string{
		"a1b2c3d4-1111-1111-1111-111111111111",
		"a1b2c3d4-2222-2222-2222-222222222222",
		"ffffffff-3333-3333-3333-333333333333",
	}
	for _, tc := range []struct {
		name, arg, want, wantErr string
	}{
		{name: "the full id passes through", arg: ids[2], want: ids[2]},
		{name: "a unique prefix resolves", arg: "ffffffff", want: ids[2]},
		{name: "an ambiguous prefix is refused", arg: "a1b2c3d4", wantErr: "matches 2"},
		{name: "an unknown prefix is refused", arg: "deadbeef", wantErr: "no dead letter"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := resolveIDPrefix("dead letter", "lm dlq list", ids, tc.arg)
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("want error containing %q, got %v", tc.wantErr, err)
				}
				return
			}
			if err != nil || got != tc.want {
				t.Fatalf("want %q, got %q (err %v)", tc.want, got, err)
			}
		})
	}
}

// The wiring, not just the helper: what `dlq list` displayed must work in `dlq get`.
func TestDlqGet_acceptsTheIdThatListDisplayed(t *testing.T) {
	full := "a1b2c3d4-1111-1111-1111-111111111111"
	var fetched string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/dlq") {
			_, _ = io.WriteString(w, `[{"id":"`+full+`","connectorId":"c","runId":"r","skipReason":"Malformed","attempts":1,"errorMessage":"boom"}]`)
			return
		}
		fetched = strings.TrimPrefix(r.URL.Path, "/api/platform/dlq/")
		_, _ = io.WriteString(w, `{"id":"`+full+`","skipReason":"Malformed","errorMessage":"boom"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"dlq", "get", full[:8]})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("the id `dlq list` displayed was refused: %v (out=%s)", err, out.String())
	}
	if fetched != full {
		t.Errorf("fetched %q, want the full id %q", fetched, full)
	}
}

// The dlq wiring is covered above; run get is the other half and had none, so a mis-wired
// resolveRunID would have gone unnoticed.
func TestRunGet_acceptsTheIdThatListDisplayed(t *testing.T) {
	full := "abababab-0000-4000-8000-0000000000ab"
	var fetched string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/runs") {
			_, _ = io.WriteString(w, `[{"id":"`+full+`","status":"Completed","recordsIn":1,"recordsOut":1}]`)
			return
		}
		fetched = strings.TrimPrefix(r.URL.Path, "/api/platform/runs/")
		_, _ = io.WriteString(w, `{"id":"`+full+`","status":"Completed","recordsIn":1,"recordsOut":1}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"run", "get", full[:8], "-o", "json"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("the id `run list` displayed was refused: %v (out=%s)", err, out.String())
	}
	if fetched != full {
		t.Errorf("fetched %q, want the full id %q", fetched, full)
	}
}

// The filters are part of the resolution: an operator copies from a FILTERED list, so the lookup
// has to use the same filter. Against the unfiltered set the same 8 characters can be ambiguous.
func TestDlqResolve_usesTheFiltersTheOperatorListedWith(t *testing.T) {
	var gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/dlq") {
			gotQuery = r.URL.RawQuery
			_, _ = io.WriteString(w, `[{"id":"cdcdcdcd-0000-4000-8000-0000000000cd","skipReason":"Malformed"}]`)
			return
		}
		_, _ = io.WriteString(w, `{"id":"cdcdcdcd-0000-4000-8000-0000000000cd","skipReason":"Malformed"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"dlq", "get", "cdcdcdcd", "--run", "99999999-0000-4000-8000-000000000099"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	if !strings.Contains(gotQuery, "99999999-0000-4000-8000-000000000099") {
		t.Errorf("resolution listed without the --run filter the operator used: %q", gotQuery)
	}
}
