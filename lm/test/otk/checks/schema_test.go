package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestSchemaDiff_IdenticalIsClean(t *testing.T) {
	s := SchemaSnapshot{Tables: []string{"core.connector"}, ConnectorRows: 2}
	if got := SchemaDiff(s, s, testCaseID, 0); len(got) != 0 {
		t.Errorf("identical snapshots must be clean, got %v", got)
	}
}

func TestSchemaDiff_NewTableIsCritical(t *testing.T) {
	b := SchemaSnapshot{Tables: []string{"core.connector"}}
	a := SchemaSnapshot{Tables: []string{"core.connector", "core.evil"}}
	got := SchemaDiff(b, a, testCaseID, 0)
	if len(got) != 1 || got[0].Severity != oracle.Critical || got[0].Family != oracle.Security || got[0].CaseID != testCaseID {
		t.Fatalf("new table must be CRITICAL/security, got %v", got)
	}
}

func TestSchemaDiff_DroppedTableIsCritical(t *testing.T) {
	b := SchemaSnapshot{Tables: []string{"core.connector", "core.audit"}}
	a := SchemaSnapshot{Tables: []string{"core.connector"}}
	if got := SchemaDiff(b, a, testCaseID, 0); len(got) != 1 || got[0].Severity != oracle.Critical {
		t.Fatalf("dropped table must be CRITICAL, got %v", got)
	}
}

func TestSchemaDiff_RowDeltaIsCritical(t *testing.T) {
	b := SchemaSnapshot{Tables: []string{"core.connector"}, ConnectorRows: 1}
	a := SchemaSnapshot{Tables: []string{"core.connector"}, ConnectorRows: 2}
	if got := SchemaDiff(b, a, testCaseID, 0); len(got) != 1 || got[0].Severity != oracle.Critical {
		t.Fatalf("connector row delta must be CRITICAL, got %v", got)
	}
}
