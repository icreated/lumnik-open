//go:build otk

package otk

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

// The export lane (#684): the managed kind:Export doors and the one-shot entity download,
// driven through the lm binary so what is graded is what an integrator sees — exit status,
// stdout, stderr and the files left on disk. Raw HTTP is used only for what lm cannot answer
// itself: the reference bytes a download must reproduce, and storing the sentinel secret.
const (
	stepExportGiven         = `^the export case "([^"]*)" is prepared(?: \[seed=(-?\d+)\])?$`
	stepExportApply         = `^I apply its export manifest with lm$`
	stepExportRun           = `^I run the applied export with lm and wait for its outcome$`
	stepExportScopeRun      = `^a scope-bound identity lacking scope "([^"]*)" reads and runs the applied export with lm$`
	stepExportDownload      = `^I download entity "([^"]*)" with lm to (a new file|stdout|an existing file)$`
	stepExportScopeDownload = `^a scope-bound identity lacking scope "([^"]*)" downloads entity "([^"]*)" with lm to a new file$`
	stepExportRefused       = `^the export apply is refused and nothing is stored$`
	stepExportApplied       = `^the export apply succeeds and lm lists and gets it$`
	stepExportMasked        = `^lm export get masks the endpoint credential$`
	stepExportFailed        = `^the export run ended Failed with failure code "([^"]*)"$`
	stepExportCompleted     = `^the export run ended Completed with a receipt$`
	stepExportScopeRefusal  = `^lm reported the uniform scope refusal$`
	stepExportNoRun         = `^no export run was recorded by the refused identity$`
	stepExportExactBytes    = `^the download holds the hub's exact snapshot bytes$`
	stepExportUntouched     = `^lm refused the download and the existing file is untouched$`
	stepExportNoArtifact    = `^lm refused the download and left no file behind$`
	stepExportNoSentinel    = `^no lm output echoed the export sentinel$`
)

// exportTarget is the file name every download aims at, inside the scenario's own directory.
const exportTarget = "customers.csv"

// exportExisting is what the collision case plants at the target before lm runs.
const exportExisting = "OTK precious file — must survive the download\n"

// The variables that declare a real SFTP destination for managed/delivers (OTK_EXPORT_SFTP_PORT
// is optional and defaults to 22).
var exportSFTPEnv = []string{
	"OTK_EXPORT_SFTP_HOST", "OTK_EXPORT_SFTP_USER", "OTK_EXPORT_SFTP_DIR",
	"OTK_EXPORT_SFTP_FINGERPRINT", "OTK_EXPORT_SFTP_PASSWORD",
}

// exportState is the export lane's per-scenario state.
type exportState struct {
	c          generator.ExportCase
	seed       int64
	dormant    bool
	dir        string   // the scenario's scratch directory, removed by the After hook
	exportName string   // the export the scenario applied (delivers names its own)
	manifest   string   // the manifest applied (delivers builds its own)
	secrets    []string // every credential this case planted: none may appear in any output
	outputs    []string // stdout+stderr of every lm command the case ran

	apply    runner.Result
	run      runner.Result
	refusals []runner.Result // commands run by a refused identity

	newestRunBefore string // newest run id before the refused identity acted

	download runner.Result
	toStdout bool
}

func init() {
	stepPatterns = append(stepPatterns,
		stepExportGiven, stepExportApply, stepExportRun, stepExportScopeRun, stepExportDownload,
		stepExportScopeDownload, stepExportRefused, stepExportApplied, stepExportMasked,
		stepExportFailed, stepExportCompleted, stepExportScopeRefusal, stepExportNoRun,
		stepExportExactBytes, stepExportUntouched, stepExportNoArtifact, stepExportNoSentinel)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepExportGiven, func(name, seed string) error { return exportGiven(st, name, seed) })
		ctx.Step(stepExportApply, func() error { return exportApply(st) })
		ctx.Step(stepExportRun, func() error { return exportRun(st) })
		ctx.Step(stepExportScopeRun, func(scope string) error { return exportScopeRun(st, scope) })
		ctx.Step(stepExportDownload, func(entity, to string) error { return exportDownload(st, entity, to) })
		ctx.Step(stepExportScopeDownload, func(scope, entity string) error { return exportScopeDownload(st, scope, entity) })
		ctx.Step(stepExportRefused, func() error { return exportRefused(st) })
		ctx.Step(stepExportApplied, func() error { return exportApplied(st) })
		ctx.Step(stepExportMasked, func() error { return exportMasked(st) })
		ctx.Step(stepExportFailed, func(code string) error { return exportFailed(st, code) })
		ctx.Step(stepExportCompleted, func() error { return exportCompleted(st) })
		ctx.Step(stepExportScopeRefusal, func() error { return exportScopeRefusal(st) })
		ctx.Step(stepExportNoRun, func() error { return exportNoRun(st) })
		ctx.Step(stepExportExactBytes, func() error { return exportExactBytes(st) })
		ctx.Step(stepExportUntouched, func() error { return exportUntouched(st) })
		ctx.Step(stepExportNoArtifact, func() error { return exportNoArtifact(st) })
		ctx.Step(stepExportNoSentinel, func() error { return exportNoSentinel(st) })
		ctx.After(func(ctx context.Context, _ *godog.Scenario, _ error) (context.Context, error) {
			if st.exp.dir != "" {
				_ = os.RemoveAll(st.exp.dir)
			}
			return ctx, nil
		})
	})
}

func exportFinding(st *scenarioState, sev oracle.Severity, fam oracle.Family, format string, args ...any) {
	st.findings = append(st.findings, oracle.Finding{
		Severity: sev, Family: fam, CaseID: st.exp.c.CaseID, Seed: st.exp.seed,
		Detail: fmt.Sprintf(format, args...),
	})
}

// exportLm runs lm, keeps its output for the sentinel sweep and grades its robustness. env
// entries appended after lmEnv() win (os/exec keeps the last value of a repeated key).
func exportLm(st *scenarioState, extraEnv []string, args ...string) runner.Result {
	r := runner.Run(append(lmEnv(), extraEnv...), lmBinary(), args...)
	st.exp.outputs = append(st.exp.outputs, r.Stdout, r.Stderr)
	st.findings = append(st.findings, checks.Robustness(r, st.exp.c.CaseID, st.exp.seed)...)
	return r
}

func exportGiven(st *scenarioState, name, seed string) error {
	c, ok := generator.ExportCaseByName(name)
	if !ok {
		return fmt.Errorf("export lane: unknown case %q", name)
	}
	st.exp = exportState{c: c, seed: parseSeed(seed), exportName: c.ExportName, manifest: c.Manifest,
		secrets: []string{generator.ExportSentinel}}
	if entityBaseURL() == "" {
		return fmt.Errorf("export lane: LUMNIK_URL is not set")
	}
	switch {
	case c.NeedsSeed && seedDSNAbsent():
		st.exp.dormant = true
		recordDormant(c.CaseID, "needs the seeded otk_customer entity: set OTK_DB_DSN")
	case c.NeedsKey && otkKey == nil:
		st.exp.dormant = true
		recordDormant(c.CaseID, "scope-bound identity: set OTK_OIDC_ISSUER + boot %otkauth")
	case c.NeedsSFTP && missingEnv(exportSFTPEnv) != "":
		st.exp.dormant = true
		recordDormant(c.CaseID, "needs a real SFTP destination: set "+missingEnv(exportSFTPEnv))
	}
	if st.exp.dormant {
		return nil
	}
	if c.NeedsSFTP {
		st.exp.exportName, st.exp.manifest = deliveringManifest()
		st.exp.secrets = append(st.exp.secrets, os.Getenv("OTK_EXPORT_SFTP_PASSWORD"))
	}
	dir, err := os.MkdirTemp("", "otk-export-*")
	if err != nil {
		return fmt.Errorf("export lane: scratch directory: %w", err)
	}
	st.exp.dir = dir
	return nil
}

func missingEnv(names []string) string {
	var missing []string
	for _, n := range names {
		if os.Getenv(n) == "" {
			missing = append(missing, n)
		}
	}
	return strings.Join(missing, ", ")
}

// deliveringManifest builds managed/delivers' manifest from its declared destination. The export
// keeps one fixed name (re-applying it is an upsert); the remote file path is what changes on
// every run: destinations never overwrite, so a fixed path would deliver once and collide on
// every later battery.
func deliveringManifest() (name, manifest string) {
	name = "otk-export-delivers"
	port := envOr("OTK_EXPORT_SFTP_PORT", "22")
	path := strings.TrimRight(os.Getenv("OTK_EXPORT_SFTP_DIR"), "/") +
		fmt.Sprintf("/otk-customers-%d.csv", time.Now().UnixNano())
	manifest = fmt.Sprintf(`apiVersion: apps.lumnik.io/v1
kind: Export
metadata:
  name: %s
spec:
  scope: otk
  source:
    entity: %s
  columns: [id, name]
  order: [id]
  format:
    kind: csv
  mode: snapshot
  destination:
    kind: sftp
    host: %q
    port: %s
    user: %q
    path: %q
    password_env: OTK_EXPORT_SFTP_PASSWORD
    host_key_fingerprint: %q
`, name, generator.ExportEntity, os.Getenv("OTK_EXPORT_SFTP_HOST"), port,
		os.Getenv("OTK_EXPORT_SFTP_USER"), path, os.Getenv("OTK_EXPORT_SFTP_FINGERPRINT"))
	return name, manifest
}

// ---- managed ----

func exportApply(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	// The destination authenticates with a stored secret; plant the sentinel (or the declared
	// real password) under its name before any run can read it.
	if strings.Contains(st.exp.manifest, "password_env: "+generator.ExportPasswordSecret) {
		if err := storeSecret(generator.ExportPasswordSecret, generator.ExportSentinel); err != nil {
			return err
		}
	}
	if st.exp.c.NeedsSFTP {
		if err := storeSecret("OTK_EXPORT_SFTP_PASSWORD", os.Getenv("OTK_EXPORT_SFTP_PASSWORD")); err != nil {
			return err
		}
	}
	path := filepath.Join(st.exp.dir, "export.yaml")
	if err := os.WriteFile(path, []byte(st.exp.manifest), 0o600); err != nil {
		return fmt.Errorf("export lane: write manifest: %w", err)
	}
	st.exp.apply = exportLm(st, nil, "export", "apply", "-f", path)
	return nil
}

// storeSecret creates or rotates an lm secret over the platform API (lm secret set reads its
// value from a terminal or stdin, neither of which the runner gives it).
func storeSecret(name, value string) error {
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("export lane: mint integrator token: %w", err)
	}
	body, _ := json.Marshal(map[string]string{"name": name, "value": value, "description": "OTK export lane"})
	status, resp, err := inboxHTTPBody("POST", "/api/platform/secrets", token, body, "application/json")
	if err != nil {
		return fmt.Errorf("export lane: store secret %s: %w", name, err)
	}
	if status == http.StatusConflict {
		body, _ = json.Marshal(map[string]string{"value": value, "description": "OTK export lane"})
		status, resp, err = inboxHTTPBody("PUT", "/api/platform/secrets/"+url.PathEscape(name), token, body, "application/json")
		if err != nil {
			return fmt.Errorf("export lane: rotate secret %s: %w", name, err)
		}
	}
	if status < 200 || status > 299 {
		return fmt.Errorf("export lane: store secret %s: status %d: %s", name, status, resp)
	}
	return nil
}

// listedExports reads `lm export list` — an observation, so a failure is a broken precondition.
func listedExports(st *scenarioState) (map[string]bool, error) {
	r := exportLm(st, nil, "export", "list", "-o", "json")
	if r.Exit != 0 {
		return nil, fmt.Errorf("export lane: lm export list exited %d: %s", r.Exit, r.Stderr)
	}
	var rows []client.ExportSummary
	if err := json.Unmarshal([]byte(r.Stdout), &rows); err != nil {
		return nil, fmt.Errorf("export lane: lm export list is not JSON: %w", err)
	}
	names := map[string]bool{}
	for _, e := range rows {
		names[e.Name] = true
	}
	return names, nil
}

func exportRefused(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.apply.Exit == 0 {
		exportFinding(st, oracle.High, oracle.Security,
			"invalid kind:Export manifest accepted: lm export apply exited 0 (%s)", strings.TrimSpace(st.exp.apply.Stdout))
	}
	names, err := listedExports(st)
	if err != nil {
		return err
	}
	if names[st.exp.exportName] {
		exportFinding(st, oracle.High, oracle.Atomicity,
			"refused manifest %q is listed by lm export list — the refusal stored it anyway", st.exp.exportName)
	}
	return nil
}

func exportApplied(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.apply.Exit != 0 {
		exportFinding(st, oracle.High, oracle.Robustness,
			"valid kind:Export manifest refused: exit %d: %s", st.exp.apply.Exit, strings.TrimSpace(st.exp.apply.Stderr))
		return nil
	}
	names, err := listedExports(st)
	if err != nil {
		return err
	}
	if !names[st.exp.exportName] {
		exportFinding(st, oracle.High, oracle.Robustness, "applied export %q is missing from lm export list", st.exp.exportName)
	}
	get := exportLm(st, nil, "export", "get", st.exp.exportName)
	if get.Exit != 0 || !strings.Contains(get.Stdout, "kind: Export") ||
		!strings.Contains(get.Stdout, "name: "+st.exp.exportName) {
		exportFinding(st, oracle.High, oracle.Robustness,
			"lm export get %s: exit %d, want the stored manifest (stdout=%q)", st.exp.exportName, get.Exit, get.Stdout)
	}
	return nil
}

func exportMasked(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.apply.Exit != 0 {
		return fmt.Errorf("export lane: the fixture did not apply (exit %d): %s", st.exp.apply.Exit, st.exp.apply.Stderr)
	}
	get := exportLm(st, nil, "export", "get", st.exp.exportName)
	if get.Exit != 0 {
		return fmt.Errorf("export lane: lm export get exited %d: %s", get.Exit, get.Stderr)
	}
	if !strings.Contains(get.Stdout, "://***@") {
		exportFinding(st, oracle.Medium, oracle.Security,
			"lm export get did not mask the endpoint's userinfo (stdout=%q)", get.Stdout)
	}
	return nil // the sentinel itself is graded by the shared sentinel step
}

func exportRun(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.apply.Exit != 0 {
		return fmt.Errorf("export lane: the fixture did not apply (exit %d): %s", st.exp.apply.Exit, st.exp.apply.Stderr)
	}
	// --timeout stays under the runner's own 30s bound, so lm answers before it is killed.
	st.exp.run = exportLm(st, nil, "export", "run", st.exp.exportName,
		"--wait", "--timeout", "25s", "--interval", "500ms", "-o", "json")
	return nil
}

// runOutcome parses the run `lm export run --wait -o json` printed, and re-reads it from the
// ledger with `lm export status`: the two must agree, or lm reported what the hub did not record.
func runOutcome(st *scenarioState) (*client.ExportRun, error) {
	var run client.ExportRun
	if err := json.Unmarshal([]byte(st.exp.run.Stdout), &run); err != nil || run.ID == "" {
		return nil, fmt.Errorf("export lane: lm export run printed no run (exit %d, stdout=%q, stderr=%q)",
			st.exp.run.Exit, st.exp.run.Stdout, st.exp.run.Stderr)
	}
	status := exportLm(st, nil, "export", "status", st.exp.exportName, run.ID, "-o", "json")
	var ledger client.ExportRun
	if status.Exit != 0 || json.Unmarshal([]byte(status.Stdout), &ledger) != nil {
		return nil, fmt.Errorf("export lane: lm export status %s exited %d: %s", run.ID, status.Exit, status.Stderr)
	}
	if ledger.Status != run.Status || ledger.FailureCode != run.FailureCode {
		exportFinding(st, oracle.High, oracle.Atomicity,
			"lm export run reported %s/%s but the ledger holds %s/%s for run %s",
			run.Status, run.FailureCode, ledger.Status, ledger.FailureCode, run.ID)
	}
	return &ledger, nil
}

func exportFailed(st *scenarioState, code string) error {
	if st.exp.dormant {
		return nil
	}
	run, err := runOutcome(st)
	if err != nil {
		return err
	}
	switch {
	case run.Status == client.ExportRunCompleted:
		exportFinding(st, oracle.High, oracle.Atomicity,
			"run %s to an unreachable destination was recorded Completed", run.ID)
	case run.Status != client.ExportRunFailed:
		exportFinding(st, oracle.High, oracle.Robustness,
			"run %s did not reach a terminal status within the wait (status %s)", run.ID, run.Status)
	case run.FailureCode != code:
		exportFinding(st, oracle.Medium, oracle.Robustness,
			"run %s failed with code %q, want %q (reason %q)", run.ID, run.FailureCode, code, run.FailureReason)
	}
	if run.Status == client.ExportRunFailed && st.exp.run.Exit == 0 {
		exportFinding(st, oracle.High, oracle.Robustness, "lm export run --wait exited 0 on a Failed run %s", run.ID)
	}
	return nil
}

func exportCompleted(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	run, err := runOutcome(st)
	if err != nil {
		return err
	}
	if run.Status != client.ExportRunCompleted {
		exportFinding(st, oracle.High, oracle.Robustness,
			"run %s to the declared destination ended %s (%s: %s)", run.ID, run.Status, run.FailureCode, run.FailureReason)
		return nil
	}
	if st.exp.run.Exit != 0 {
		exportFinding(st, oracle.High, oracle.Robustness, "lm export run --wait exited %d on a Completed run", st.exp.run.Exit)
	}
	if run.Sha256 == "" || run.Location == "" || run.ByteCount == nil || *run.ByteCount <= 0 || run.RowCount == nil {
		exportFinding(st, oracle.Medium, oracle.Robustness, "Completed run %s carries an incomplete receipt: %+v", run.ID, run)
	}
	return nil
}

func scopeBoundEnv(scope string) ([]string, error) {
	token, err := otkKey.ScopeBound(otkTenant, otkTenant, "not-"+scope)
	if err != nil {
		return nil, fmt.Errorf("export lane: forge scope-bound token: %w", err)
	}
	return []string{"LUMNIK_TOKEN=" + token}, nil
}

// newestRun is the id of the export's newest run, "" when it has none.
func newestRun(st *scenarioState) (string, error) {
	r := exportLm(st, nil, "export", "runs", st.exp.exportName, "--limit", "1", "-o", "json")
	if r.Exit != 0 {
		return "", fmt.Errorf("export lane: lm export runs exited %d: %s", r.Exit, r.Stderr)
	}
	var runs []client.ExportRun
	if err := json.Unmarshal([]byte(r.Stdout), &runs); err != nil {
		return "", fmt.Errorf("export lane: lm export runs is not JSON: %w", err)
	}
	if len(runs) == 0 {
		return "", nil
	}
	return runs[0].ID, nil
}

func exportScopeRun(st *scenarioState, scope string) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.apply.Exit != 0 {
		return fmt.Errorf("export lane: the fixture did not apply (exit %d): %s", st.exp.apply.Exit, st.exp.apply.Stderr)
	}
	before, err := newestRun(st)
	if err != nil {
		return err
	}
	st.exp.newestRunBefore = before
	env, err := scopeBoundEnv(scope)
	if err != nil {
		return err
	}
	st.exp.refusals = append(st.exp.refusals,
		exportLm(st, env, "export", "get", st.exp.exportName),
		exportLm(st, env, "export", "run", st.exp.exportName))
	return nil
}

func exportScopeRefusal(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if len(st.exp.refusals) == 0 {
		return fmt.Errorf("export lane: no command ran as the refused identity")
	}
	for _, r := range st.exp.refusals {
		switch {
		case r.Exit == 0:
			exportFinding(st, oracle.Critical, oracle.Security,
				"an identity without scope otk was served: exit 0 (stdout=%q)", r.Stdout)
		case !strings.Contains(r.Stderr, "scope not granted"):
			exportFinding(st, oracle.Medium, oracle.Security,
				"the scope refusal did not reach the terminal as the hub worded it (stderr=%q)", r.Stderr)
		}
	}
	return nil
}

func exportNoRun(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	after, err := newestRun(st)
	if err != nil {
		return err
	}
	if after != st.exp.newestRunBefore {
		exportFinding(st, oracle.Critical, oracle.Security,
			"a refused identity started export run %s", after)
	}
	return nil
}

// ---- one-shot ----

func exportDownload(st *scenarioState, entity, to string) error {
	if st.exp.dormant {
		return nil
	}
	target := filepath.Join(st.exp.dir, exportTarget)
	switch to {
	case "stdout":
		st.exp.toStdout = true
		target = "-"
	case "an existing file":
		if err := os.WriteFile(target, []byte(exportExisting), 0o600); err != nil {
			return fmt.Errorf("export lane: plant the existing file: %w", err)
		}
	}
	st.exp.download = exportLm(st, nil, "entity", "export", entity, "-o", target)
	return nil
}

func exportScopeDownload(st *scenarioState, scope, entity string) error {
	if st.exp.dormant {
		return nil
	}
	env, err := scopeBoundEnv(scope)
	if err != nil {
		return err
	}
	st.exp.download = exportLm(st, env, "entity", "export", entity, "-o", filepath.Join(st.exp.dir, exportTarget))
	st.exp.refusals = append(st.exp.refusals, st.exp.download)
	return nil
}

// snapshotReference fetches the bytes the one-shot door serves, straight from the hub.
func snapshotReference(entity string) ([]byte, error) {
	token, err := currentToken()
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest("GET", entityBaseURL()+"/api/exports/entities/"+url.PathEscape(entity)+"/download", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "text/csv")
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)
	resp, err := entityHTTPClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d: %s", resp.StatusCode, body)
	}
	return body, nil
}

// exportRowsReadBack reads the same entity through `lm entity get` (#866) and holds it to the
// snapshot: as many JSON rows as the CSV has records. The read is paged to the hub's cap, so an
// extent beyond it is not compared rather than reported as a mismatch.
func exportRowsReadBack(st *scenarioState, snapshot []byte) {
	cr := csv.NewReader(bytes.NewReader(snapshot))
	records := -1 // the header is not a row
	for {
		if _, err := cr.Read(); err == io.EOF {
			break
		} else if err != nil {
			exportFinding(st, oracle.High, oracle.Robustness, "the hub's entity snapshot is not valid CSV: %v", err)
			return
		}
		if records++; records > client.EntityRowsPageCap {
			return // past the page cap: not compared, not reported
		}
	}
	if records < 0 {
		exportFinding(st, oracle.High, oracle.Robustness, "the hub's entity snapshot is empty: not even a CSV header row")
		return
	}
	r := exportLm(st, nil, "entity", "get", generator.ExportEntity, "--limit", strconv.Itoa(client.EntityRowsPageCap), "-o", "json")
	if r.Exit != 0 {
		exportFinding(st, oracle.High, oracle.Robustness, "lm entity get exited %d on an entity the hub just exported: %s",
			r.Exit, strings.TrimSpace(r.Stderr))
		return
	}
	var rows []json.RawMessage
	if err := json.Unmarshal([]byte(r.Stdout), &rows); err != nil {
		exportFinding(st, oracle.High, oracle.Robustness, "lm entity get -o json did not print a JSON array of rows: %v", err)
		return
	}
	if want := records; len(rows) != want {
		exportFinding(st, oracle.High, oracle.Atomicity, "lm entity get returned %d row(s), the snapshot holds %d", len(rows), want)
	}
}

// dirEntries names everything in the scenario directory — a partial download included.
func dirEntries(st *scenarioState) ([]string, error) {
	entries, err := os.ReadDir(st.exp.dir)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)
	return names, nil
}

func exportExactBytes(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	ref, err := snapshotReference(generator.ExportEntity)
	if err != nil {
		return fmt.Errorf("export lane: reference download: %w", err)
	}
	d := st.exp.download
	if d.Exit != 0 {
		exportFinding(st, oracle.High, oracle.Robustness, "lm entity export exited %d: %s", d.Exit, strings.TrimSpace(d.Stderr))
		return nil
	}
	if st.exp.toStdout {
		if d.Stdout != string(ref) {
			exportFinding(st, oracle.High, oracle.Atomicity,
				"lm entity export -o - wrote %d byte(s) that differ from the hub's %d", len(d.Stdout), len(ref))
		}
		exportRowsReadBack(st, ref)
		return nil
	}
	got, err := os.ReadFile(filepath.Join(st.exp.dir, exportTarget))
	if err != nil {
		exportFinding(st, oracle.High, oracle.Atomicity, "lm entity export exited 0 but wrote no file: %v", err)
		return nil
	}
	if string(got) != string(ref) {
		exportFinding(st, oracle.High, oracle.Atomicity,
			"the downloaded file holds %d byte(s) that differ from the hub's %d", len(got), len(ref))
	}
	sum := sha256.Sum256(ref)
	if !strings.Contains(d.Stdout, hex.EncodeToString(sum[:])) {
		exportFinding(st, oracle.Medium, oracle.Robustness,
			"lm entity export did not report the snapshot's sha256 (stdout=%q)", d.Stdout)
	}
	names, err := dirEntries(st)
	if err != nil {
		return err
	}
	if len(names) != 1 || names[0] != exportTarget {
		exportFinding(st, oracle.High, oracle.Atomicity,
			"a completed download left more than its file behind: %v", names)
	}
	return nil
}

func exportUntouched(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.download.Exit == 0 {
		exportFinding(st, oracle.High, oracle.Atomicity, "lm entity export exited 0 over an existing file without --force")
	}
	got, err := os.ReadFile(filepath.Join(st.exp.dir, exportTarget))
	if err != nil || string(got) != exportExisting {
		exportFinding(st, oracle.High, oracle.Atomicity, "the existing file was replaced or removed (read err=%v)", err)
	}
	names, err := dirEntries(st)
	if err != nil {
		return err
	}
	if len(names) != 1 {
		exportFinding(st, oracle.High, oracle.Atomicity, "a refused download left files behind: %v", names)
	}
	return nil
}

func exportNoArtifact(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	if st.exp.download.Exit == 0 {
		exportFinding(st, oracle.High, oracle.Security,
			"lm entity export exited 0 on a download the hub must refuse (stdout=%q)", st.exp.download.Stdout)
	}
	names, err := dirEntries(st)
	if err != nil {
		return err
	}
	if len(names) != 0 {
		exportFinding(st, oracle.High, oracle.Atomicity, "a refused download left an artifact behind: %v", names)
	}
	return nil
}

func exportNoSentinel(st *scenarioState) error {
	if st.exp.dormant {
		return nil
	}
	all := strings.Join(st.exp.outputs, "\n")
	for _, s := range st.exp.secrets {
		st.findings = append(st.findings, checks.SecretEcho(all, s, st.exp.c.CaseID, st.exp.seed)...)
	}
	return nil
}
