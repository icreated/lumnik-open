package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWorkflowApplyCmd_requiresFileAndReadsIt(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "w.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Workflow\n"), 0o644))

	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply", "-f", f})
	err := cmd.Execute()

	// no context configured → fails at client resolution, not flag parsing / file reading
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "required flag")
	assert.NotContains(t, err.Error(), "no such file")
}

func TestWorkflowApplyCmd_missingFileFlagFailsFast(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "required flag")
}

func TestWorkflowGetCmd_requiresNameArg(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"get"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "accepts 1 arg")
}

func TestWorkflowMutatingCmds_takeExactlyOneName(t *testing.T) {
	// The delete/enable/disable gestures #487 gave kind:Process, missing here (#507). Each
	// needs a name and nothing else: a missing one must be a usage error, not a call against
	// the whole tenant.
	for _, verb := range []string{"rm", "enable", "disable"} {
		cmd := newWorkflowCmd()
		var out bytes.Buffer
		cmd.SetOut(&out)
		cmd.SetErr(&out)
		cmd.SetArgs([]string{verb})
		require.Error(t, cmd.Execute(), verb)
		assert.Contains(t, out.String(), "accepts 1 arg", verb)

		named := newWorkflowCmd()
		named.SetOut(&out)
		named.SetErr(&out)
		named.SetArgs([]string{verb, "commande-lifecycle"})
		// no context configured → it reaches client resolution, so the args were accepted
		err := named.Execute()
		require.Error(t, err, verb)
		assert.NotContains(t, err.Error(), "accepts 1 arg", verb)
	}
}

// --- e2e contracts through the full root command against an httptest hub (the
// setupCfg pattern from entity_test.go / rag_test.go / view_store_test.go) ---

// applyWorkflow runs `lm workflow apply` against a hub that answers body.
func applyWorkflow(t *testing.T, body string, args ...string) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/workflows/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, body)
	}))
	defer srv.Close()
	f := filepath.Join(t.TempDir(), "w.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Workflow\n"), 0o600))
	out, _, err := runLm(t, srv, append([]string{"workflow", "apply", "-f", f}, args...)...)
	require.NoError(t, err)
	return out
}

const orderLifecycleApplied = `{"name":"acme-order-lifecycle","scope":"desk","table":"connector.t_acme_desk_orders",` +
	`"column":"status","dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2},{"value":"Terminé","rows":1}],` +
	`"unobserved":["ANNULE"],"nullOrBlank":1,"tableMissing":false,"keyColumnMissing":false,"keyNullOrBlank":0,` +
	`"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}}`

// The apply line, then what the declaration and the data disagree on — the report the hub sends,
// one line per finding, read without jq.
func TestWorkflowApply_printsTheAppliedLineAndTheDataTruthReport(t *testing.T) {
	assert.Equal(t, "Applied workflow \"acme-order-lifecycle\" → connector.t_acme_desk_orders.status, scope desk\n"+
		"  in the data, not declared: EN LITIGE (2 rows), Terminé (1 row)\n"+
		"  declared, not in the data: ANNULE\n"+
		"  status null or blank: 1 row\n",
		applyWorkflow(t, orderLifecycleApplied))
}

func TestWorkflowApply_reportSaysWhenTheDataAgrees(t *testing.T) {
	out := applyWorkflow(t, `{"name":"w","scope":"desk","table":"connector.t_x","column":"status","dataTruth":`+
		`{"undeclared":[],"unobserved":[],"nullOrBlank":0,"tableMissing":false,"keyColumnMissing":false,`+
		`"keyNullOrBlank":null,"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}}`)
	assert.Equal(t, "Applied workflow \"w\" → connector.t_x.status, scope desk\n"+
		"  the data matches the declaration\n", out)
}

// Each finding the report can carry has its line: a table not ingested yet, a check that could not
// run, a key column the table lacks, documents the sweep never follows, a due column it cannot read.
func TestWorkflowApply_reportNamesEveryFinding(t *testing.T) {
	const head = `{"name":"w","scope":"desk","table":"connector.t_x","column":"status","dataTruth":`
	for name, c := range map[string]struct{ truth, want string }{
		"table missing": {`{"undeclared":[],"unobserved":["OPEN"],"nullOrBlank":0,"tableMissing":true,` +
			`"keyColumnMissing":false,"keyNullOrBlank":null,"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}`,
			"  the table is not there yet: the data was not checked\n"},
		"check unavailable": {`{"undeclared":[],"unobserved":[],"nullOrBlank":0,"tableMissing":false,` +
			`"keyColumnMissing":false,"keyNullOrBlank":null,"checkUnavailable":true,` +
			`"unavailableReason":"SQLSTATE 57014: canceling statement due to statement timeout","dueProblem":null}`,
			"  the data could not be checked: SQLSTATE 57014: canceling statement due to statement timeout\n"},
		"key column missing": {`{"undeclared":[],"unobserved":[],"nullOrBlank":0,"tableMissing":false,` +
			`"keyColumnMissing":true,"keyNullOrBlank":null,"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}`,
			"  spec.on.key: the table has no such column\n"},
		"key null or blank": {`{"undeclared":[],"unobserved":[],"nullOrBlank":0,"tableMissing":false,` +
			`"keyColumnMissing":false,"keyNullOrBlank":3,"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}`,
			"  key null or blank: 3 rows, never watched\n"},
		"due problem": {`{"undeclared":[],"unobserved":[],"nullOrBlank":0,"tableMissing":false,` +
			`"keyColumnMissing":false,"keyNullOrBlank":0,"checkUnavailable":false,"unavailableReason":null,` +
			`"dueProblem":"spec.due.column due_on is text, not a date: no deadline can be read from it"}`,
			"  spec.due.column due_on is text, not a date: no deadline can be read from it\n"},
	} {
		out := applyWorkflow(t, head+c.truth+"}")
		assert.Equal(t, "Applied workflow \"w\" → connector.t_x.status, scope desk\n"+c.want, out, name)
	}
}

// -o json keeps the hub's answer whole, dataTruth included: `| jq .dataTruth` reads it.
func TestWorkflowApply_outputJSON_keepsTheHubAnswer(t *testing.T) {
	out := applyWorkflow(t, orderLifecycleApplied, "-o", "json")
	var got, want any
	require.NoError(t, json.Unmarshal([]byte(out), &got), out)
	require.NoError(t, json.Unmarshal([]byte(orderLifecycleApplied), &want))
	assert.Equal(t, want, got)
}

// The null-or-blank line names the workflow's own column: a lifecycle over `stage` is not over `status`.
func TestWorkflowApply_nullOrBlankLineNamesTheColumn(t *testing.T) {
	out := applyWorkflow(t, `{"name":"w","scope":"desk","table":"connector.t_x","column":"stage","dataTruth":`+
		`{"undeclared":[],"unobserved":[],"nullOrBlank":2,"tableMissing":false,"keyColumnMissing":false,`+
		`"keyNullOrBlank":null,"checkUnavailable":false,"unavailableReason":null,"dueProblem":null}}`)
	assert.Equal(t, "Applied workflow \"w\" → connector.t_x.stage, scope desk\n"+
		"  stage null or blank: 2 rows\n", out)
}

// A field the hub adds later reaches -o json and -o yaml: the machine output is the hub's answer, not
// what this lm version knows of it.
func TestWorkflowApply_machineOutputKeepsAFieldLmDoesNotKnow(t *testing.T) {
	body := `{"name":"w","scope":"desk","table":"connector.t_x","column":"status","dataTruth":null,"futureField":7}`
	assert.Contains(t, applyWorkflow(t, body, "-o", "json"), `"futureField": 7`)
	assert.Contains(t, applyWorkflow(t, body, "-o", "yaml"), "futureField: 7")
}

func TestWorkflowListCmd_e2e_rendersTheSummaryTable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/workflows" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `[{"name":"commande-lifecycle","table":"connector.t_commandes_csv","column":"statut","scope":"magasin","enabled":true}]`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"workflow", "list"})
	require.NoError(t, cmd.Execute())
	out := buf.String()
	for _, want := range []string{"NAME", "TABLE", "COLUMN", "SCOPE", "ENABLED",
		"commande-lifecycle", "connector.t_commandes_csv", "statut", "magasin", "true"} {
		assert.Contains(t, out, want)
	}
}

func TestWorkflowGetCmd_e2e_printsManifestYamlVerbatimAndEscapesName(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: Workflow\nmetadata:\n  name: commande-lifecycle\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/workflows/commande-lifecycle/manifest" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = fmt.Fprint(w, yaml)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"workflow", "get", "commande-lifecycle"})
	require.NoError(t, cmd.Execute())
	// Verbatim: the manifest and nothing else.
	assert.Equal(t, yaml, buf.String())
}

func TestWorkflowHelp_namesAllVerbs(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"--help"})
	require.NoError(t, cmd.Execute())

	for _, verb := range []string{"apply", "list", "get", "rm", "enable", "disable"} {
		assert.Contains(t, out.String(), verb)
	}
}
