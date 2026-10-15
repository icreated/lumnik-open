package calibration

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseSQLFault_SplitsApplyAndUndo(t *testing.T) {
	apply, undo, err := ParseSQLFault(
		"ALTER POLICY tenant_isolation ON connector.connector USING (true);\n" +
			"-- undo:\n" +
			"ALTER POLICY tenant_isolation ON connector.connector USING (tenant_id = current_setting('app.current_tenant', true)::bigint);\n")
	if err != nil {
		t.Fatalf("ParseSQLFault: %v", err)
	}
	if apply != "ALTER POLICY tenant_isolation ON connector.connector USING (true);" {
		t.Errorf("apply = %q", apply)
	}
	if !strings.HasPrefix(undo, "ALTER POLICY tenant_isolation ON connector.connector USING (tenant_id") {
		t.Errorf("undo = %q", undo)
	}
}

func TestParseSQLFault_MissingMarker_Errors(t *testing.T) {
	_, _, err := ParseSQLFault("ALTER POLICY x ON y USING (true);\n")
	if err == nil {
		t.Fatal("a fault with no undo marker must be refused: it could never be restored")
	}
	if !strings.Contains(err.Error(), "-- undo:") {
		t.Errorf("the refusal must name the marker, got: %v", err)
	}
}

func TestParseSQLFault_EmptyApply_Errors(t *testing.T) {
	_, _, err := ParseSQLFault("\n-- undo:\nALTER POLICY x ON y USING (true);\n")
	if err == nil {
		t.Fatal("an empty apply half must be refused: it is not a fault at all")
	}
}

func TestParseSQLFault_EmptyUndo_Errors(t *testing.T) {
	_, _, err := ParseSQLFault("ALTER POLICY x ON y USING (true);\n-- undo:\n")
	if err == nil {
		t.Fatal("an empty undo half must be refused: this runner could never restore it")
	}
}

func TestParseSQLFault_MarkerMustBeItsOwnLine(t *testing.T) {
	// A marker embedded inside a statement (not its own trimmed line) must not be
	// mistaken for the split point — otherwise a SQL comment containing this exact
	// text anywhere would silently truncate the apply half.
	_, _, err := ParseSQLFault("SELECT '-- undo: not a marker';\n")
	if err == nil {
		t.Fatal("an inline occurrence of the marker text must not satisfy the split requirement")
	}
}

func TestReadSQLFault_MissingFile_Errors(t *testing.T) {
	if _, _, err := readSQLFault(filepath.Join(t.TempDir(), "nope.sql")); err == nil {
		t.Fatal("reading a missing artefact must be an error")
	}
}

// Every declared KindSQL fault's own artefact must parse — the SQL-kind analog of
// TestFaultPatchesApplyCleanly, which proves the same thing for a KindPatch
// fault's artefact against the tree it faults. A .sql file cannot go stale the way
// a patch can (there is no "context that no longer matches" to drift out from
// under it), but it can still be malformed — missing its own undo marker — and
// that must be caught here, not forty seconds into a live control phase.
func TestSQLFaultArtefactsParse(t *testing.T) {
	faults, err := Faults()
	if err != nil {
		t.Fatalf("Faults(): %v", err)
	}
	for _, f := range faults {
		if f.Kind != KindSQL {
			continue
		}
		t.Run(f.Name, func(t *testing.T) {
			path := filepath.Join(FaultsDir, f.ArtefactName())
			apply, undo, err := readSQLFault(path)
			if err != nil {
				t.Fatalf("%s does not parse: %v", f.ArtefactName(), err)
			}
			if apply == "" || undo == "" {
				t.Fatalf("%s parsed to an empty half: apply=%q undo=%q", f.ArtefactName(), apply, undo)
			}
		})
	}
}

func TestReadSQLFault_ReadsAndParses(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "f.sql")
	if err := os.WriteFile(path, []byte("SELECT 1;\n-- undo:\nSELECT 2;\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	apply, undo, err := readSQLFault(path)
	if err != nil {
		t.Fatalf("readSQLFault: %v", err)
	}
	if apply != "SELECT 1;" || undo != "SELECT 2;" {
		t.Errorf("got apply=%q undo=%q", apply, undo)
	}
}
