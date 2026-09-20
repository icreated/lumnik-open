package calibration

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// FaultedArtefacts is the packaged output a repackaged fault leaves behind,
// relative to the repository root.
//
// It is the WHOLE module target, not only quarkus-app/: the compiled classes under
// it were built from faulted source too, and "the next build starts from clean
// source" is the guarantee. Today's only patch touches lumnik-hub; a future fault
// patching another module must widen this, which is why it is a named constant
// rather than a path buried in the runner.
const FaultedArtefacts = "lumnik-hub/target"

// TreeClean reports whether the working tree has no changes at all — UNTRACKED
// FILES INCLUDED — and what is dirty when it does.
//
// Untracked counts, and the reason is not revert safety. `git apply -R` indeed
// cannot discard an untracked file, so an earlier draft of this plan excluded them.
// That argued the wrong obligation: Maven compiles an untracked .java under
// src/main/java just as happily as a tracked one, so the control phase would then
// have been built from source nobody declared. Phase 1's whole job is to say "the
// unfaulted branch does not already exhibit this" — and it can only say that about a
// branch, not about a branch plus whatever is lying beside it.
//
// This is why otkcal writes its hub log OUTSIDE the repository: a guard that fails
// on its own exhaust is a guard people learn to ignore, and the fix is to move the
// exhaust, not to loosen the guard.
func TreeClean(root string) (bool, string, error) {
	cmd := exec.Command("git", "status", "--porcelain")
	cmd.Dir = root
	out, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return false, "", fmt.Errorf("git status in %s: %w\n%s", root, err, exitErr.Stderr)
		}
		return false, "", fmt.Errorf("git status in %s: %w", root, err)
	}
	dirt := strings.TrimSpace(string(out))
	return dirt == "", dirt, nil
}

// PatchApplies checks a patch against the tree WITHOUT touching it.
//
// A stale patch exits non-zero and says so, while a sed whose pattern no longer
// matches does nothing and reports success. For a harness whose entire subject is
// what fails to go red, that asymmetry is the reason this lot uses patches.
func PatchApplies(root, patch string) error { return gitApply(root, patch, "--check") }

// ApplyPatch injects the fault into the working tree.
func ApplyPatch(root, patch string) error { return gitApply(root, patch) }

// RevertPatch reverse-applies it. The runner asserts a clean tree afterwards: the
// source is the easy half of restoration, and the half that is easy to mistake for
// the whole.
func RevertPatch(root, patch string) error { return gitApply(root, patch, "-R") }

func gitApply(root, patch string, flags ...string) error {
	abs, err := filepath.Abs(patch)
	if err != nil {
		return fmt.Errorf("resolve patch path %s: %w", patch, err)
	}
	args := append([]string{"apply"}, flags...)
	cmd := exec.Command("git", append(args, abs)...)
	cmd.Dir = root
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("git %s %s: %w\n%s", strings.Join(args, " "), abs, err, out)
	}
	return nil
}

// RemoveAndVerify deletes each path and then checks it is gone.
//
// The contract here is ABSENCE, not restoration, and the difference is not pedantry:
// if a build existed before the run, packaging the faulted hub overwrote it, so
// "restore what was there" is a promise that cannot be kept. Rebuilding a clean jar
// instead would cost another package, could itself fail, and would leave a state
// nobody can tell apart from the faulted one at a glance.
//
// It verifies rather than assuming, because a removal that silently did nothing is
// the same defect as a patch that silently did not apply — and the residue here is
// a hub that under-reports the rows it wrote, waiting for whoever runs it next.
func RemoveAndVerify(paths ...string) error {
	var failed []string
	for _, p := range paths {
		if err := os.RemoveAll(p); err != nil {
			failed = append(failed, fmt.Sprintf("%s: %v", p, err))
			continue
		}
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			failed = append(failed, fmt.Sprintf("%s: still present after removal (stat: %v)", p, err))
		}
	}
	if len(failed) > 0 {
		return fmt.Errorf("FAULTED ARTEFACTS SURVIVED the run — the next build would not start from clean source:\n  %s",
			strings.Join(failed, "\n  "))
	}
	return nil
}
