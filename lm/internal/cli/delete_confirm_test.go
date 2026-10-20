package cli

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// runDelete drives one of the three destructive deletes against a stub hub; it returns the DELETE
// calls the hub saw, what lm printed, and its error. The stub knows connector orders (csv-file),
// whose delete would take 1204 rows of connector.t_orders, and search index pool-products over
// entity Product. A dry run is not a delete: it is not in deletes.
func runDelete(t *testing.T, stdin string, args ...string) (deletes []string, out string, err error) {
	t.Helper()
	calls, out, err := runAgainstStub(t, http.StatusOK,
		`{"dryRun":true,"tables":[{"table":"connector.t_orders","rows":1204}]}`, stdin, args...)
	for _, c := range calls {
		if !strings.HasSuffix(c, "?dryRun=true") {
			deletes = append(deletes, c)
		}
	}
	return deletes, out, err
}

// runAgainstStub runs lm against the stub hub, whose dry runs answer dryRunStatus/dryRunBody; it
// returns every DELETE the hub saw, dry runs included.
func runAgainstStub(t *testing.T, dryRunStatus int, dryRunBody, stdin string, args ...string) (calls []string, out string, err error) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodDelete:
			calls = append(calls, r.URL.RequestURI())
			if r.URL.Query().Get("dryRun") == "true" {
				w.WriteHeader(dryRunStatus)
				_, _ = w.Write([]byte(dryRunBody))
				return
			}
			w.WriteHeader(http.StatusNoContent)
		case r.URL.Path == "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","name":"orders","typeId":"csv-file"}]`))
		case r.URL.Path == "/api/search-indexes":
			_, _ = w.Write([]byte(`[{"name":"pool-products","entity":"Product"},{"name":"pool-orders","entity":"Order"}]`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	root := NewRootCmd()
	root.SetIn(strings.NewReader(stdin))
	var b strings.Builder
	root.SetOut(&b)
	root.SetErr(&strings.Builder{})
	root.SetArgs(args)
	err = root.Execute()
	return calls, b.String(), err
}

var destructiveDeletes = []struct {
	args   []string
	prompt string // what lm asks, naming what goes
	path   string // the one DELETE a confirmed run sends
}{
	{[]string{"connector", "delete", "orders"},
		"Type 'delete' to delete connector orders (csv-file) — 1204 rows of connector.t_orders, its run history and RAG chunks: ",
		"/api/platform/connectors/c1"},
	{[]string{"entity", "delete", "product"},
		"Type 'delete' to delete entity product — its view, for every tenant, and search index pool-products: ",
		"/api/entities/product"},
	{[]string{"search", "rm", "pool-products"},
		"Type 'delete' to delete search index pool-products over entity Product — its projected documents are purged: ",
		"/api/search-indexes/pool-products"},
}

func TestDestructiveDelete_promptNamesWhatGoesThenDeletes(t *testing.T) {
	for _, tc := range destructiveDeletes {
		t.Run(strings.Join(tc.args[:2], " "), func(t *testing.T) {
			deletes, out, err := runDelete(t, "delete\n", tc.args...)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.HasPrefix(out, tc.prompt) {
				t.Errorf("out = %q, want it to open with %q", out, tc.prompt)
			}
			if len(deletes) != 1 || deletes[0] != tc.path {
				t.Errorf("deletes = %v, want [%s]", deletes, tc.path)
			}
		})
	}
}

func TestDestructiveDelete_declinedPromptDeletesNothing(t *testing.T) {
	for _, tc := range destructiveDeletes {
		t.Run(strings.Join(tc.args[:2], " "), func(t *testing.T) {
			deletes, _, err := runDelete(t, "yes\n", tc.args...)
			if err == nil || err.Error() != "aborted" {
				t.Fatalf("err = %v, want aborted", err)
			}
			if len(deletes) != 0 {
				t.Errorf("deletes = %v, want none", deletes)
			}
		})
	}
}

// A script with no stdin must learn why nothing happened, in endpoint purge's words.
func TestDestructiveDelete_noConfirmationReadSaysSo(t *testing.T) {
	for _, tc := range destructiveDeletes {
		t.Run(strings.Join(tc.args[:2], " "), func(t *testing.T) {
			deletes, _, err := runDelete(t, "", tc.args...)
			want := "aborted: no confirmation read (EOF) — pass --force in a script"
			if err == nil || err.Error() != want {
				t.Fatalf("err = %v, want %q", err, want)
			}
			if len(deletes) != 0 {
				t.Errorf("deletes = %v, want none", deletes)
			}
		})
	}
}

func TestDestructiveDelete_forceAsksNothing(t *testing.T) {
	for _, tc := range destructiveDeletes {
		t.Run(strings.Join(tc.args[:2], " "), func(t *testing.T) {
			deletes, out, err := runDelete(t, "", append(tc.args, "--force")...)
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(out, "Type 'delete'") {
				t.Errorf("asked under --force: %q", out)
			}
			if len(deletes) != 1 || deletes[0] != tc.path {
				t.Errorf("deletes = %v, want [%s]", deletes, tc.path)
			}
		})
	}
}

// An entity no search index covers names its view alone.
func TestEntityDelete_withoutAnIndexNamesItsViewAlone(t *testing.T) {
	_, out, _ := runDelete(t, "", "entity", "delete", "Customer")
	if want := "Type 'delete' to delete entity Customer — its view, for every tenant: "; !strings.HasPrefix(out, want) {
		t.Errorf("out = %q, want %q", out, want)
	}
}

// A connector lm cannot find is refused before any question, as before.
func TestConnectorDelete_unknownNameIsRefusedBeforeThePrompt(t *testing.T) {
	deletes, out, err := runDelete(t, "delete\n", "connector", "delete", "ghost")
	if err == nil || !strings.Contains(err.Error(), `connector "ghost" not found`) {
		t.Fatalf("err = %v", err)
	}
	if out != "" || len(deletes) != 0 {
		t.Errorf("out = %q, deletes = %v", out, deletes)
	}
}

// runConnectorDelete drives lm connector delete orders against the stub hub.
func runConnectorDelete(t *testing.T, dryRunStatus int, dryRunBody, stdin string, flags ...string) (calls []string, out string, err error) {
	t.Helper()
	return runAgainstStub(t, dryRunStatus, dryRunBody, stdin, append([]string{"connector", "delete", "orders"}, flags...)...)
}

const connectorDryRunURI = "/api/platform/connectors/c1?dryRun=true"

// #1218: the entity a table feeds blocks the delete before lm asks, not after the answer.
func TestConnectorDelete_refusalArrivesBeforeThePrompt(t *testing.T) {
	calls, out, err := runConnectorDelete(t, 409,
		`{"error":"table connector.t_orders feeds entity order — remove the entity first (lm entity delete order)"}`, "delete\n")
	if err == nil || !strings.Contains(err.Error(), "feeds entity order") {
		t.Fatalf("err = %v, want the hub's refusal", err)
	}
	if strings.Contains(out, "Type 'delete'") {
		t.Errorf("asked before the refusal: %q", out)
	}
	if len(calls) != 1 || calls[0] != connectorDryRunURI {
		t.Errorf("calls = %v, want the dry run only", calls)
	}
}

func TestConnectorDelete_promptNamesEveryTableAndItsRows(t *testing.T) {
	_, out, err := runConnectorDelete(t, 200,
		`{"dryRun":true,"tables":[{"table":"connector.t_orders","rows":2},{"table":"ext.order_lines","rows":1}]}`, "delete\n")
	if err != nil {
		t.Fatal(err)
	}
	want := "Type 'delete' to delete connector orders (csv-file) — 2 rows of connector.t_orders, 1 row of ext.order_lines, its run history and RAG chunks: "
	if !strings.HasPrefix(out, want) {
		t.Errorf("out = %q, want it to open with %q", out, want)
	}
}

// The last connector over a credential takes it along (#1250): the question names it.
func TestConnectorDelete_promptNamesTheCredentialThatGoesWithIt(t *testing.T) {
	_, out, err := runConnectorDelete(t, 200,
		`{"dryRun":true,"tables":[{"table":"connector.t_orders","rows":2}],"credential":"erp--credential"}`, "delete\n")
	if err != nil {
		t.Fatal(err)
	}
	want := "Type 'delete' to delete connector orders (csv-file) — 2 rows of connector.t_orders, credential erp--credential, its run history and RAG chunks: "
	if !strings.HasPrefix(out, want) {
		t.Errorf("out = %q, want it to open with %q", out, want)
	}
}

// A connector that never ran has no table: the question names what it has.
func TestConnectorDelete_withoutATableNamesItsRunHistoryAlone(t *testing.T) {
	_, out, err := runConnectorDelete(t, 200, `{"dryRun":true,"tables":[]}`, "delete\n")
	if err != nil {
		t.Fatal(err)
	}
	want := "Type 'delete' to delete connector orders (csv-file) — its run history and RAG chunks: "
	if !strings.HasPrefix(out, want) {
		t.Errorf("out = %q, want it to open with %q", out, want)
	}
}

func TestConnectorDelete_dryRunFlagDeletesNothing(t *testing.T) {
	for _, flags := range [][]string{{"--dry-run"}, {"--dry-run", "--force"}} {
		calls, out, err := runConnectorDelete(t, 200,
			`{"dryRun":true,"tables":[{"table":"connector.t_orders","rows":1204}]}`, "", flags...)
		if err != nil {
			t.Fatal(err)
		}
		want := "Would delete connector orders (csv-file) — 1204 rows of connector.t_orders, its run history and RAG chunks (dry run, nothing deleted)\n"
		if out != want {
			t.Errorf("%v: out = %q, want %q", flags, out, want)
		}
		if len(calls) != 1 || calls[0] != connectorDryRunURI {
			t.Errorf("%v: calls = %v, want the dry run only", flags, calls)
		}
	}
}
