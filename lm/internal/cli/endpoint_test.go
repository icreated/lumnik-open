package cli

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestEndpointCmd_parsesConnectorSlashEndpoint(t *testing.T) {
	c, e, err := splitConnectorEndpoint("stripe/charges")
	if err != nil {
		t.Fatal(err)
	}
	if c != "stripe" || e != "charges" {
		t.Errorf("got %s/%s", c, e)
	}
	if _, _, err := splitConnectorEndpoint("invalid"); err == nil {
		t.Error("expected error")
	}
}

// runPurge drives `lm endpoint purge orders/default` against a stub hub whose purge door answers
// status and body; it returns the purge calls the hub saw, what lm printed, and its error.
func runPurge(t *testing.T, status int, body, stdin string, flags ...string) (calls []string, out string, err error) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/purge"):
			calls = append(calls, r.URL.RequestURI())
			w.WriteHeader(status)
			_, _ = w.Write([]byte(body))
		case strings.HasSuffix(r.URL.Path, "/endpoints"):
			_, _ = w.Write([]byte(`[{"id":"e1","name":"default"}]`))
		default:
			_, _ = w.Write([]byte(`[{"id":"c1","name":"orders"}]`))
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	root := NewRootCmd()
	root.SetIn(strings.NewReader(stdin))
	var b strings.Builder
	root.SetOut(&b)
	root.SetErr(&strings.Builder{})
	root.SetArgs(append([]string{"endpoint", "purge", "orders/default"}, flags...))
	err = root.Execute()
	return calls, b.String(), err
}

const (
	dryRunURI = "/api/platform/connectors/c1/endpoints/e1/purge?dryRun=true"
	purgeURI  = "/api/platform/connectors/c1/endpoints/e1/purge"
)

// A jdbc, csv-file or kafka endpoint is refused by the hub: the refusal must come before any
// question, never after the integrator typed 'purge' (#1181).
func TestEndpointPurge_refusalArrivesBeforeThePrompt(t *testing.T) {
	calls, out, err := runPurge(t, 400, `{"error":"cannot be purged on its own"}`, "purge\n")
	if err == nil || !strings.Contains(err.Error(), "cannot be purged on its own") {
		t.Fatalf("err = %v, want the hub's refusal", err)
	}
	if strings.Contains(out, "Type 'purge'") {
		t.Errorf("asked for a confirmation before the refusal: %q", out)
	}
	if len(calls) != 1 || calls[0] != dryRunURI {
		t.Errorf("calls = %v, want the dry run only", calls)
	}
}

func TestEndpointPurge_promptStatesTheCountThenDeletes(t *testing.T) {
	calls, out, err := runPurge(t, 200, `{"deleted":1204}`, "purge\n")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "Type 'purge' to delete 1204 rows: ") || !strings.Contains(out, "Purged 1204 rows") {
		t.Errorf("out = %q", out)
	}
	if len(calls) != 2 || calls[0] != dryRunURI || calls[1] != purgeURI {
		t.Errorf("calls = %v, want the dry run then the purge", calls)
	}
}

func TestEndpointPurge_oneRowIsSingular(t *testing.T) {
	_, out, err := runPurge(t, 200, `{"deleted":1}`, "purge\n")
	if err != nil {
		t.Fatal(err)
	}
	if out != "Type 'purge' to delete 1 row: Purged 1 row\n" {
		t.Errorf("out = %q", out)
	}
	_, out, err = runPurge(t, 200, `{"deleted":1}`, "", "--dry-run")
	if err != nil {
		t.Fatal(err)
	}
	if out != "Would purge 1 row (dry run, nothing deleted)\n" {
		t.Errorf("dry run out = %q", out)
	}
}

func TestEndpointPurge_declinedPromptDeletesNothing(t *testing.T) {
	calls, _, err := runPurge(t, 200, `{"deleted":3}`, "no\n")
	if err == nil || err.Error() != "aborted" {
		t.Fatalf("err = %v, want aborted", err)
	}
	if len(calls) != 1 {
		t.Errorf("calls = %v, want the dry run only", calls)
	}
}

// A script with no stdin must learn why nothing happened, not read a bare "aborted".
func TestEndpointPurge_noConfirmationReadSaysSo(t *testing.T) {
	calls, _, err := runPurge(t, 200, `{"deleted":3}`, "")
	want := "aborted: no confirmation read (EOF) — pass --force in a script"
	if err == nil || err.Error() != want {
		t.Fatalf("err = %v, want %q", err, want)
	}
	if len(calls) != 1 || calls[0] != dryRunURI {
		t.Errorf("calls = %v, want the dry run only", calls)
	}
}

func TestEndpointPurge_nothingToPurgeDoesNotPrompt(t *testing.T) {
	calls, out, err := runPurge(t, 200, `{"deleted":0}`, "")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "Type 'purge'") || !strings.Contains(out, "Nothing to purge") {
		t.Errorf("out = %q", out)
	}
	if len(calls) != 1 {
		t.Errorf("calls = %v, want the dry run only", calls)
	}
}

func TestEndpointPurge_dryRunFlagDeletesNothing(t *testing.T) {
	for _, flags := range [][]string{{"--dry-run"}, {"--dry-run", "--force"}} {
		calls, out, err := runPurge(t, 200, `{"deleted":42}`, "", flags...)
		if err != nil {
			t.Fatal(err)
		}
		if out != "Would purge 42 rows (dry run, nothing deleted)\n" {
			t.Errorf("%v: out = %q", flags, out)
		}
		if len(calls) != 1 || calls[0] != dryRunURI {
			t.Errorf("%v: calls = %v, want the dry run only", flags, calls)
		}
	}
}

func TestEndpointPurge_forceIsOneDirectCall(t *testing.T) {
	calls, out, err := runPurge(t, 200, `{"deleted":5}`, "", "--force")
	if err != nil {
		t.Fatal(err)
	}
	if out != "Purged 5 rows\n" {
		t.Errorf("out = %q", out)
	}
	if len(calls) != 1 || calls[0] != purgeURI {
		t.Errorf("calls = %v, want the purge only", calls)
	}
}
