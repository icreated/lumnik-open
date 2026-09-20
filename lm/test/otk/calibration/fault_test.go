package calibration

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// The guardian is anchored on BOTH sides, and it reports what disagrees rather
// than two counts that happen to agree. The catalogue guardian took this shape
// after a version that compared cardinalities passed a broken table.
func TestFaultInventory(t *testing.T) {
	faults, err := Faults()
	if err != nil {
		t.Fatalf("Faults(): %v", err)
	}

	entries, err := os.ReadDir(FaultsDir)
	if err != nil {
		t.Fatalf("read %s: %v", FaultsDir, err)
	}
	var files []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		files = append(files, e.Name())
	}
	if issues := artefactIssues(faults, files); len(issues) > 0 {
		t.Fatalf("the fault set on disk and the declaration table disagree:\n  %s",
			strings.Join(issues, "\n  "))
	}

	// The shape is pinned too: one artefact per fault, of the declared kind.
	// Driven with synthetic listings so the refusals are WATCHED, not assumed.
	one := []Fault{{Name: "f", Kind: KindPatch}}
	for _, c := range []struct {
		name  string
		files []string
		want  string
	}{
		{"missing", nil, "missing artefact"},
		{"wrong kind", []string{"f.sql"}, "missing artefact"},
		{"undeclared", []string{"f.patch", "stray.sql"}, "undeclared artefact"},
		{"both kinds", []string{"f.patch", "f.sql"}, "exactly one artefact"},
	} {
		t.Run(c.name, func(t *testing.T) {
			issues := artefactIssues(one, c.files)
			if len(issues) == 0 {
				t.Fatalf("%v must be reported as a failure", c.files)
			}
			if !strings.Contains(strings.Join(issues, "\n"), c.want) {
				t.Fatalf("issues %v must mention %q", issues, c.want)
			}
		})
	}
}

// An invalid expression must be a hard error BEFORE any hub is packaged, and the
// message must name the fault — otherwise the reader has a regexp error and no
// idea which declaration produced it. Driven by a deliberately invalid entry.
func TestFaultPatternMustCompile(t *testing.T) {
	_, err := compileAll([]Fault{{Name: "deliberately-invalid", Pattern: "("}})
	if err == nil {
		t.Fatal("an uncompilable pattern must be a hard error at table load")
	}
	if !strings.Contains(err.Error(), "deliberately-invalid") {
		t.Errorf("the refusal must name the fault, got: %v", err)
	}

	if _, err := compileAll([]Fault{{Name: "empty-pattern", Pattern: ""}}); err == nil {
		t.Fatal("an empty pattern matches everything and must be refused")
	}
}

// Specificity is PROVED, not detected. `.*` matches every decoy and is refused by
// construction — there is no denylist of "obviously universal" expressions to keep
// current, because a regexp can be universal in unbounded ways.
func TestPatternMatchesItsSampleAndNoDecoy(t *testing.T) {
	faults, err := Faults()
	if err != nil {
		t.Fatalf("Faults(): %v", err)
	}
	if len(decoys) < 5 {
		t.Fatalf("the decoy list has %d entries; it must be broad enough to refuse a catch-all", len(decoys))
	}
	for _, f := range faults {
		t.Run(f.Name, func(t *testing.T) {
			if !f.re.MatchString(f.Sample) {
				t.Fatalf("pattern %q does not match its own sample %q", f.Pattern, f.Sample)
			}
			for _, d := range decoys {
				if f.re.MatchString(d) {
					t.Errorf("pattern %q also matches the decoy %q — it is not specific to this fault", f.Pattern, d)
				}
			}
		})
	}

	// The mechanism itself, watched: a catch-all must fail this check.
	catchAll, err := compileAll([]Fault{{Name: "catch-all", Pattern: ".*", Sample: decoys[0]}})
	if err != nil {
		t.Fatalf("compileAll: %v", err)
	}
	matched := false
	for _, d := range decoys {
		if catchAll[0].re.MatchString(d) {
			matched = true
		}
	}
	if !matched {
		t.Fatal("`.*` must match a decoy, or this whole check proves nothing")
	}
}

// Matching is family AND severity floor AND detail, never any two of the three.
func TestFaultMatches(t *testing.T) {
	f, err := ByName("atomicity-underreport-landed")
	if err != nil {
		t.Fatalf("ByName: %v", err)
	}
	hit := oracle.Finding{Severity: oracle.High, Family: oracle.Atomicity,
		Detail: "hub reported 0 row(s) written but the DB holds 1"}
	if !f.Matches(hit) {
		t.Fatal("the declared finding must match")
	}
	if f.Matches(oracle.Finding{Severity: oracle.High, Family: oracle.Security, Detail: hit.Detail}) {
		t.Error("another family must not match")
	}
	if f.Matches(oracle.Finding{Severity: oracle.Medium, Family: oracle.Atomicity, Detail: hit.Detail}) {
		t.Error("below the declared severity must not match")
	}
	if f.Matches(oracle.Finding{Severity: oracle.Critical, Family: oracle.Atomicity, Detail: decoys[0]}) {
		t.Error("a non-matching detail must not match, whatever the severity")
	}
}

// An unknown name must list what IS known, so a typo is one message away from fixed.
func TestByNameListsTheKnownFaults(t *testing.T) {
	_, err := ByName("no-such-fault")
	if err == nil {
		t.Fatal("an unknown fault name must be an error")
	}
	if !strings.Contains(err.Error(), "atomicity-underreport-landed") {
		t.Errorf("the refusal must list the known faults, got: %v", err)
	}
}

// A patch rots as the code beneath it moves. `git apply --check` is read-only and
// says so before any hub is packaged — which is the whole reason this lot chose a
// patch over a scripted edit: a stale patch exits non-zero, a stale sed reports
// success.
func TestFaultPatchesApplyCleanly(t *testing.T) {
	root, err := exec.Command("git", "rev-parse", "--show-toplevel").Output()
	if err != nil {
		t.Skipf("not in a git checkout: %v", err)
	}
	repo := strings.TrimSpace(string(root))
	faults, err := Faults()
	if err != nil {
		t.Fatalf("Faults(): %v", err)
	}
	for _, f := range faults {
		if f.Kind != KindPatch {
			continue
		}
		t.Run(f.Name, func(t *testing.T) {
			abs, aerr := filepath.Abs(filepath.Join(FaultsDir, f.ArtefactName()))
			if aerr != nil {
				t.Fatal(aerr)
			}
			cmd := exec.Command("git", "apply", "--check", abs)
			cmd.Dir = repo
			if out, cerr := cmd.CombinedOutput(); cerr != nil {
				t.Fatalf("%s no longer applies to the tree it faults: %v\n%s",
					f.ArtefactName(), cerr, out)
			}
		})
	}
}
