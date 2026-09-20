package calibration

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// tempRepo builds a throwaway git checkout with one committed file, so the
// tree-state guards can be driven instead of described.
func tempRepo(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	for _, args := range [][]string{
		{"init", "-q"},
		{"config", "user.email", "otk@example.invalid"},
		{"config", "user.name", "OTK"},
	} {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	if err := os.WriteFile(filepath.Join(dir, "subject.txt"), []byte("one\ntwo\nthree\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	for _, args := range [][]string{{"add", "."}, {"commit", "-q", "-m", "base"}} {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	return dir
}

// The runner refuses to start on a dirty tree, so a revert can never discard work.
func TestTreeClean(t *testing.T) {
	dir := tempRepo(t)

	clean, dirt, err := TreeClean(dir)
	if err != nil {
		t.Fatalf("TreeClean: %v", err)
	}
	if !clean {
		t.Fatalf("a fresh checkout must be clean, got dirt: %q", dirt)
	}

	if err := os.WriteFile(filepath.Join(dir, "subject.txt"), []byte("edited\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	clean, dirt, err = TreeClean(dir)
	if err != nil {
		t.Fatalf("TreeClean: %v", err)
	}
	if clean {
		t.Fatal("an edited tree must not be reported clean")
	}
	if !strings.Contains(dirt, "subject.txt") {
		t.Errorf("the refusal must name what is dirty, got: %q", dirt)
	}

	// An UNTRACKED file counts. Maven compiles an untracked .java as happily as a
	// tracked one, so a control phase built beside one is not built from clean source
	// — and phase 1's whole claim is about the branch, not the branch plus whatever is
	// lying beside it.
	other := tempRepo(t)
	if err := os.WriteFile(filepath.Join(other, "Sneaky.java"), []byte("class Sneaky {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	clean, dirt, err = TreeClean(other)
	if err != nil {
		t.Fatalf("TreeClean: %v", err)
	}
	if clean {
		t.Fatal("an untracked source file must make the tree dirty: Maven would compile it")
	}
	if !strings.Contains(dirt, "Sneaky.java") {
		t.Errorf("the refusal must name the untracked file, got: %q", dirt)
	}
}

// TreeClean is the one function deciding whether a calibration run may start at
// all, so its failure must say why — not just that git exited non-zero. git writes
// its reason to STDERR, and cmd.Output() alone drops it; the error must carry it,
// the same way gitApply's CombinedOutput already does for apply/revert failures.
func TestTreeCleanErrorCarriesStderr(t *testing.T) {
	_, _, err := TreeClean(t.TempDir()) // not a git repository at all
	if err == nil {
		t.Fatal("git status outside a repository must fail")
	}
	if !strings.Contains(err.Error(), "git repository") {
		t.Errorf("the error must carry git's own stderr explanation, got: %v", err)
	}
}

// A patch applied and reverted must leave the tree byte-identical. This is the
// obligation the whole lot rests on.
func TestApplyAndRevertPatchRoundTrip(t *testing.T) {
	dir := tempRepo(t)
	before, err := os.ReadFile(filepath.Join(dir, "subject.txt"))
	if err != nil {
		t.Fatal(err)
	}

	patch := filepath.Join(t.TempDir(), "fault.patch")
	if err := os.WriteFile(patch, []byte(
		"--- a/subject.txt\n+++ b/subject.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := PatchApplies(dir, patch); err != nil {
		t.Fatalf("PatchApplies on a matching tree: %v", err)
	}
	if err := ApplyPatch(dir, patch); err != nil {
		t.Fatalf("ApplyPatch: %v", err)
	}
	faulted, err := os.ReadFile(filepath.Join(dir, "subject.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(faulted) == string(before) {
		t.Fatal("ApplyPatch changed nothing — a fault that does not apply proves nothing")
	}
	if err := RevertPatch(dir, patch); err != nil {
		t.Fatalf("RevertPatch: %v", err)
	}
	after, err := os.ReadFile(filepath.Join(dir, "subject.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatalf("revert did not restore the source:\n before: %q\n after:  %q", before, after)
	}
	if clean, dirt, _ := TreeClean(dir); !clean {
		t.Fatalf("the tree must be clean after a revert, got: %q", dirt)
	}
}

// A stale patch exits non-zero and says so. That asymmetry against a scripted edit
// — which would do nothing and report success — is why this lot chose a patch.
func TestPatchAppliesRefusesAStalePatch(t *testing.T) {
	dir := tempRepo(t)
	patch := filepath.Join(t.TempDir(), "stale.patch")
	if err := os.WriteFile(patch, []byte(
		"--- a/subject.txt\n+++ b/subject.txt\n@@ -1,3 +1,3 @@\n one\n-NOT THE LINE\n+TWO\n three\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := PatchApplies(dir, patch); err == nil {
		t.Fatal("a patch whose context no longer matches must be refused, not applied silently")
	}
}

// The contract is ABSENCE, not restoration: if a build existed before the run,
// packaging the faulted hub overwrote it, so "restore what was there" is a promise
// that cannot be kept. RemoveAndVerify deletes AND checks — an unlink that silently
// did nothing is the same defect as a patch that silently did not apply.
func TestRemoveAndVerify(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "quarkus-app")
	if err := os.MkdirAll(filepath.Join(target, "lib"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "quarkus-run.jar"), []byte("faulted"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := RemoveAndVerify(target); err != nil {
		t.Fatalf("RemoveAndVerify: %v", err)
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatalf("the faulted artefact survived the run: stat err = %v", err)
	}

	// Removing what is already absent is success: the contract is absence.
	if err := RemoveAndVerify(target); err != nil {
		t.Fatalf("removing an absent path must succeed, got: %v", err)
	}
}
