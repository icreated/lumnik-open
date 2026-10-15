package checks

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestSecretEcho_LeakIsCritical(t *testing.T) {
	got := SecretEcho("resolved key = OTK-SENTINEL-x", "OTK-SENTINEL-x", testCaseID, 4)
	if len(got) != 1 || got[0].Severity != oracle.Critical || got[0].Family != oracle.Security || got[0].CaseID != testCaseID {
		t.Fatalf("sentinel echo must be CRITICAL/security, got %v", got)
	}
}

func TestSecretEcho_NoLeakIsClean(t *testing.T) {
	if got := SecretEcho("validation ok", "OTK-SENTINEL-x", testCaseID, 4); len(got) != 0 {
		t.Errorf("no echo must be clean, got %v", got)
	}
}

func TestSecretEcho_EmptySentinelNeverFires(t *testing.T) {
	if got := SecretEcho("anything at all", "", testCaseID, 1); len(got) != 0 {
		t.Errorf("empty sentinel must never fire, got %v", got)
	}
}

func TestFileDisclosure_PasswdIsCritical(t *testing.T) {
	got := FileDisclosure("root:x:0:0:root:/root:/bin/bash", testCaseID, 7)
	if len(got) != 1 || got[0].Severity != oracle.Critical || got[0].Family != oracle.Security {
		t.Fatalf("passwd signature must be CRITICAL/security, got %v", got)
	}
}

func TestFileDisclosure_RejectedTraversalIsClean(t *testing.T) {
	// The error echoes the PATH but not the file CONTENTS — that is the win.
	if got := FileDisclosure("error: path not allowed: ../../etc/passwd\n", testCaseID, 7); len(got) != 0 {
		t.Errorf("a cleanly rejected traversal must be clean, got %v", got)
	}
}

func TestRLSIsolation_ForeignRowVisibleIsCritical(t *testing.T) {
	got := RLSIsolation(1, testCaseID, 13)
	if len(got) != 1 || got[0].Severity != oracle.Critical || got[0].Family != oracle.Security || got[0].CaseID != testCaseID {
		t.Fatalf("a foreign-tenant row visible under RLS must be CRITICAL/security, got %v", got)
	}
}

func TestRLSIsolation_MultipleForeignRowsStillOneFinding(t *testing.T) {
	got := RLSIsolation(3, testCaseID, 13)
	if len(got) != 1 {
		t.Fatalf("multiple leaked rows are still ONE isolation breach, got %d findings", len(got))
	}
}

func TestRLSIsolation_NoForeignRowsIsClean(t *testing.T) {
	if got := RLSIsolation(0, testCaseID, 13); len(got) != 0 {
		t.Errorf("no foreign rows visible must be clean, got %v", got)
	}
}
