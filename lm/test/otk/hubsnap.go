//go:build otk

package otk

import (
	"encoding/json"
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

// otkTenant is the tenant OTK operates as. lm sends it as X-Tenant-ID (see
// writeHermeticConfig) for writes; the non-owner app-role psql reads SET it so
// RLS shows those rows. One source of truth keeps the write- and read-tenant
// from drifting apart.
const otkTenant = "1"

// rlsScoped prefixes a SET so the non-owner app-role psql session sees the OTK
// tenant's rows under RLS. Reads run as lumnik_app, which otherwise sees no rows
// (app.current_tenant unset -> RLS admits nothing). DROPs use the admin DSN
// (BYPASSRLS) and do not need this.
func rlsScoped(query string) string {
	return "SET app.current_tenant = '" + otkTenant + "'; " + query
}

// psqlInt parses a single integer from psql -At output, tolerating a leading
// command tag. A rlsScoped read is "SET …; SELECT …", and psql >=15 echoes the
// "SET" tag before the value ("SET\n0") while psql <=14 prints only the last result
// ("0"). Taking the last non-empty line makes the parse client-version-independent.
func psqlInt(out []byte) (int, error) {
	s := strings.TrimSpace(string(out))
	if s == "" {
		return 0, fmt.Errorf("empty psql output")
	}
	lines := strings.Split(s, "\n")
	return strconv.Atoi(strings.TrimSpace(lines[len(lines)-1]))
}

// hubSnapshot takes a read-only DB fingerprint via the non-owner role:
//   - (snapshot, true, nil)  when dsn is set, psql is on PATH, and both queries succeed
//   - (zero, false, nil)     when dormant (no dsn, or psql not on PATH)
//   - (zero, false, err)     when psql ran but failed — a precondition the caller surfaces
//
// Flag ordering (-X -At -w -d <dsn> -c) keeps options before the DSN so BSD/macOS
// getopt does not misparse them; -w prevents an interactive password prompt that
// would hang CI; the BASE TABLE filter excludes views from the DDL diff.
func hubSnapshot(dsn string) (checks.SchemaSnapshot, bool, error) {
	if dsn == "" {
		return checks.SchemaSnapshot{}, false, nil
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		return checks.SchemaSnapshot{}, false, nil
	}
	tablesOut, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c",
		"SELECT table_schema||'.'||table_name FROM information_schema.tables "+
			"WHERE table_schema IN ('core','identity','rag','platform') "+
			"AND table_type = 'BASE TABLE' ORDER BY 1").Output()
	if err != nil {
		return checks.SchemaSnapshot{}, false, psqlErr("tables snapshot", err)
	}
	countOut, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c",
		rlsScoped("SELECT count(*) FROM connector.connector")).Output()
	if err != nil {
		return checks.SchemaSnapshot{}, false, psqlErr("connector-count snapshot", err)
	}
	rows, err := psqlInt(countOut)
	if err != nil {
		return checks.SchemaSnapshot{}, false,
			fmt.Errorf("psql connector-count returned non-numeric output %q: %w", string(countOut), err)
	}
	return checks.SchemaSnapshot{
		Tables:        splitNonEmpty(string(tablesOut)),
		ConnectorRows: rows,
	}, true, nil
}

// psqlErr surfaces psql stderr (captured by Output() into ExitError.Stderr) so
// auth / DSN failures are diagnosable without a shell.
func psqlErr(what string, err error) error {
	if ee, ok := err.(*exec.ExitError); ok && len(ee.Stderr) > 0 {
		return fmt.Errorf("psql %s failed: %v\npsql stderr: %s", what, err, ee.Stderr)
	}
	return fmt.Errorf("psql %s failed: %v", what, err)
}

func splitNonEmpty(s string) []string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		if t := strings.TrimSpace(line); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// tenantIDRe matches a bare non-negative integer tenant id: digits only, no
// quotes, no operators, no whitespace. Tenant ids reaching this guard come from Go
// constants (otkTenant, auth.ForeignTenantID), but they still pass through it
// before joining a raw SQL string handed to psql -c — the same posture
// validTableIdent already takes for identifiers: validate the shape server-side,
// never trust the caller, however trusted the caller looks today.
var tenantIDRe = regexp.MustCompile(`^[0-9]+$`)

func validTenantID(s string) bool { return tenantIDRe.MatchString(s) }

// crossTenantQuery builds a psql -c statement that pins the SESSION to asTenant and
// counts rows in connector.connector belonging to wantTenant — the direct
// RLS-policy analog of an HTTP request made under a different tenant's token. If
// the tenant_isolation policy holds, the count is always 0 regardless of what data
// exists: the policy admits only rows matching the SESSION's own tenant, never
// wantTenant's.
func crossTenantQuery(asTenant, wantTenant string) string {
	return "SET app.current_tenant = '" + asTenant + "'; " +
		"SELECT count(*) FROM connector.connector WHERE tenant_id = " + wantTenant
}

// crossTenantVisibleRows runs crossTenantQuery via the non-owner app-role psql
// session (DB truth) — tri-state like hubSnapshot/tableRowCount:
//   - (n, true, nil)    when dsn is set, psql is on PATH, and the query succeeds
//   - (0, false, nil)   when dormant (no dsn, or psql not on PATH)
//   - (0, false, err)   when psql ran but failed, or a tenant id fails validTenantID
func crossTenantVisibleRows(dsn, asTenant, wantTenant string) (int, bool, error) {
	if dsn == "" {
		return 0, false, nil
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		return 0, false, nil
	}
	if !validTenantID(asTenant) || !validTenantID(wantTenant) {
		return 0, false, fmt.Errorf("refusing an unsafe tenant id: as=%q want=%q", asTenant, wantTenant)
	}
	out, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c",
		crossTenantQuery(asTenant, wantTenant)).Output()
	if err != nil {
		return 0, false, psqlErr("cross-tenant RLS probe", err)
	}
	n, err := psqlInt(out)
	if err != nil {
		return 0, false,
			fmt.Errorf("psql cross-tenant RLS probe returned non-numeric output %q: %w", string(out), err)
	}
	return n, true, nil
}

// runIDRe matches a UUID shape. Run ids reaching this guard come from parseRunID (lm's own
// JSON output), but they still pass through it before joining a raw SQL string handed to
// psql -c — the same posture validTenantID/validTableIdent already take.
var runIDRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func validRunID(s string) bool { return runIDRe.MatchString(s) }

// runTerminalQuery builds a psql -c statement that pins the SESSION to otkTenant and asks
// whether run runID's row already carries a non-null ended_at — DB truth for "has this run
// reached a terminal status", independent of whatever the API reports.
func runTerminalQuery(runID string) string {
	return "SET app.current_tenant = '" + otkTenant + "'; " +
		"SELECT (ended_at IS NOT NULL) FROM connector.connector_run WHERE id = '" + runID + "'"
}

// psqlBool parses a single t/f boolean from psql -At output, tolerating a leading command tag —
// the boolean counterpart to psqlInt, for the same psql-version reason.
func psqlBool(out []byte) (bool, error) {
	s := strings.TrimSpace(string(out))
	if s == "" {
		return false, fmt.Errorf("empty psql output")
	}
	lines := strings.Split(s, "\n")
	switch last := strings.TrimSpace(lines[len(lines)-1]); last {
	case "t":
		return true, nil
	case "f":
		return false, nil
	default:
		return false, fmt.Errorf("psql output %q is not a boolean (t/f)", last)
	}
}

// runTerminalInDB asks DB truth whether run runID has already reached a terminal status —
// tri-state like tableRowCount/crossTenantVisibleRows:
//   - (terminal, true, nil)  when dsn is set, psql is on PATH, and the query succeeds
//   - (false, false, nil)    when dormant (no dsn, or psql not on PATH)
//   - (false, false, err)    when psql ran but failed, the run id fails validRunID, or the row
//     could not be read as a boolean (including: the run does not exist in DB truth at all)
func runTerminalInDB(dsn, runID string) (bool, bool, error) {
	if dsn == "" {
		return false, false, nil
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		return false, false, nil
	}
	if !validRunID(runID) {
		return false, false, fmt.Errorf("refusing an unsafe run id: %q", runID)
	}
	out, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c", runTerminalQuery(runID)).Output()
	if err != nil {
		return false, false, psqlErr("run-terminal DB-truth probe", err)
	}
	terminal, err := psqlBool(out)
	if err != nil {
		return false, false, fmt.Errorf("psql run-terminal probe for run %q: %w", runID, err)
	}
	return terminal, true, nil
}

// tableIdentRe matches a strict schema.table identifier: two dot-separated parts,
// each a letter/underscore followed by word chars. Anything else (extra dots,
// spaces, quotes, semicolons) is rejected before it reaches psql.
var tableIdentRe = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$`)

func validTableIdent(s string) bool { return tableIdentRe.MatchString(s) }

// parseDataTables turns `lm data tables -o json` output into the set of
// "schema.table" identifiers. Used for the name-agnostic set-diff.
func parseDataTables(jsonOut string) (map[string]bool, error) {
	var rows []struct {
		TableSchema string `json:"tableSchema"`
		TableName   string `json:"tableName"`
	}
	if err := json.Unmarshal([]byte(jsonOut), &rows); err != nil {
		return nil, fmt.Errorf("could not parse 'lm data tables' output: %w", err)
	}
	out := make(map[string]bool, len(rows))
	for _, r := range rows {
		out[r.TableSchema+"."+r.TableName] = true
	}
	return out, nil
}

// dataTableSet snapshots the dynamic tables the hub currently exposes (API view).
// A launch failure, non-zero exit, or unparseable output is an observation failure.
func dataTableSet() (map[string]bool, error) {
	r := runner.Run(lmEnv(), lmBinary(), "data", "tables", "-o", "json")
	if !r.Started {
		return nil, fmt.Errorf("could not launch lm at %q: %s", lmBinary(), r.Stderr)
	}
	if r.Exit != 0 {
		return nil, fmt.Errorf("could not observe data tables (exit %d): %s", r.Exit, r.Stderr)
	}
	return parseDataTables(r.Stdout)
}

// tableRowCount counts rows in a dynamic table via the non-owner psql role (DB
// truth). Tri-state like hubSnapshot: dormant (no dsn / no psql), or a hard error.
func tableRowCount(dsn, qualified string) (int, bool, error) {
	if dsn == "" {
		return 0, false, nil
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		return 0, false, nil
	}
	if !validTableIdent(qualified) {
		return 0, false, fmt.Errorf("refusing to count rows for unsafe table identifier %q", qualified)
	}
	out, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c",
		rlsScoped("SELECT count(*) FROM "+qualified)).Output()
	if err != nil {
		return 0, false, psqlErr("table-rowcount "+qualified, err)
	}
	n, err := psqlInt(out)
	if err != nil {
		return 0, false, fmt.Errorf("psql rowcount for %s returned non-numeric %q: %w", qualified, string(out), err)
	}
	return n, true, nil
}
