package calibration

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// sqlUndoMarker splits a .sql fault artefact into its apply half and its undo
// half. A SQL fault carries both in one file — unlike a patch, there is no
// `git apply -R` to reverse an arbitrary statement, so the artefact must state its
// own inverse.
const sqlUndoMarker = "-- undo:"

// ParseSQLFault splits a SQL fault artefact into its apply and undo halves at the
// FIRST line that is exactly sqlUndoMarker (trimmed). Both halves are required and
// non-empty once trimmed: an apply with no undo is a fault this runner could never
// restore, and an undo with nothing to apply is not a fault at all.
func ParseSQLFault(contents string) (apply, undo string, err error) {
	lines := strings.Split(contents, "\n")
	marker := -1
	for i, l := range lines {
		if strings.TrimSpace(l) == sqlUndoMarker {
			marker = i
			break
		}
	}
	if marker == -1 {
		return "", "", fmt.Errorf("SQL fault carries no %q marker line: a SQL fault must declare its own undo", sqlUndoMarker)
	}
	apply = strings.TrimSpace(strings.Join(lines[:marker], "\n"))
	undo = strings.TrimSpace(strings.Join(lines[marker+1:], "\n"))
	if apply == "" {
		return "", "", fmt.Errorf("SQL fault has no statement before %q", sqlUndoMarker)
	}
	if undo == "" {
		return "", "", fmt.Errorf("SQL fault has no statement after %q", sqlUndoMarker)
	}
	return apply, undo, nil
}

// runSQL executes one already-resolved statement against dsn via psql -c. Flag
// ordering matches hubSnapshot/tableRowCount's own (-X -At -w -d dsn -c) so
// BSD/macOS getopt does not misparse them and no interactive password prompt can
// hang CI.
func runSQL(dsn, stmt string) error {
	psql, err := exec.LookPath("psql")
	if err != nil {
		return fmt.Errorf("psql is not on PATH")
	}
	cmd := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c", stmt)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("psql -c %q failed: %w\n%s", stmt, err, out)
	}
	return nil
}

// ApplySQL parses the fault artefact at path and runs its apply half against dsn —
// the admin (BYPASSRLS) role, since altering a policy is DDL the app role cannot
// perform.
func ApplySQL(dsn, path string) error {
	apply, _, err := readSQLFault(path)
	if err != nil {
		return err
	}
	return runSQL(dsn, apply)
}

// UndoSQL parses the SAME artefact and runs its undo half. Re-parsing rather than
// caching the halves keeps ApplySQL and UndoSQL independent callers with no shared
// state to get out of sync — restoration must work even when it is invoked by a
// different code path than the one that applied the fault (the panic/signal
// handlers both reach it through session.restore(), never through calibrate()
// directly).
func UndoSQL(dsn, path string) error {
	_, undo, err := readSQLFault(path)
	if err != nil {
		return err
	}
	return runSQL(dsn, undo)
}

func readSQLFault(path string) (apply, undo string, err error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", "", fmt.Errorf("read SQL fault %s: %w", path, err)
	}
	return ParseSQLFault(string(b))
}
