package generator

import (
	"regexp"
	"sort"
	"testing"
)

// The per-lane counts are read off the tables; the total is the contract this whole
// lot rests on. If a lane's count moves, the catalogue changed — bump the pinned
// fingerprint below in the same commit, deliberately.
func TestCatalogueCountsPerLane(t *testing.T) {
	want := map[string]int{
		"apply": 45, "run": 26, "rest": 24, "auth": 7,
		"entity": 15, "process": 11, "view": 9, "entityres": 3,
		"export": 17,
	}
	got := map[string]int{}
	for _, id := range Catalogue() {
		got[id.Lane()]++
	}
	total := 0
	for lane, n := range want {
		if got[lane] != n {
			t.Errorf("lane %s: catalogue has %d cases, want %d", lane, got[lane], n)
		}
		total += n
	}
	for lane := range got {
		if _, ok := want[lane]; !ok {
			t.Errorf("unexpected lane %q in the catalogue", lane)
		}
	}
	if len(Catalogue()) != total {
		t.Errorf("catalogue holds %d cases, want %d", len(Catalogue()), total)
	}
}

func TestCatalogueIsSortedAndUnique(t *testing.T) {
	c := Catalogue()
	if !sort.SliceIsSorted(c, func(i, j int) bool { return c[i] < c[j] }) {
		t.Error("Catalogue() must be sorted so the corpus order is stable")
	}
	seen := map[CaseID]bool{}
	for _, id := range c {
		if seen[id] {
			t.Errorf("duplicate CaseID %q in the catalogue", id)
		}
		seen[id] = true
	}
}

// The fingerprint IS the catalogue version: there is no second value to keep in sync.
// A red here means the catalogue changed. On a legitimate addition the witness sweep
// and Catalogue() move together, so the guardian (TestWitnessedSetEqualsCatalogue)
// stays green and prints nothing — read TestCatalogueCountsPerLane instead, which
// names the lane and count that moved, then paste the new fingerprint below. That
// paste is the corpus-version bump, and it is the moment a human looks at what moved.
func TestCatalogueFingerprintIsPinned(t *testing.T) {
	const pinned = "3fe6325f76d4"
	got := CatalogueFingerprint()
	if !regexp.MustCompile(`^[0-9a-f]{12}$`).MatchString(got) {
		t.Fatalf("fingerprint %q is not 12 hex characters", got)
	}
	if got != pinned {
		t.Fatalf("catalogue fingerprint = %q, pinned %q.\n"+
			"The catalogue changed. Confirm the change is intended, then pin %q here.", got, pinned, got)
	}
}

func TestCatalogueFingerprintIsStableAcrossCalls(t *testing.T) {
	if a, b := CatalogueFingerprint(), CatalogueFingerprint(); a != b {
		t.Fatalf("fingerprint is not stable: %q vs %q", a, b)
	}
}

// assertReorderMovesFingerprintOnly runs one reorder mutation, checks the fingerprint
// moved, and checks the sorted Catalogue() did NOT — a reorder changes no ID, only
// what an index or a position means, so the sorted set is the wrong place to see it
// and asserting it stays put is what proves the fingerprint carries information the
// sorted set cannot.
func assertReorderMovesFingerprintOnly(t *testing.T, mutate func()) {
	t.Helper()
	before := CatalogueFingerprint()
	beforeSet := Catalogue()

	mutate()

	after := CatalogueFingerprint()
	afterSet := Catalogue()

	if before == after {
		t.Fatal("the reorder left the fingerprint unchanged")
	}
	if len(beforeSet) != len(afterSet) {
		t.Fatalf("reorder changed the catalogue's size: %d vs %d", len(beforeSet), len(afterSet))
	}
	for i := range beforeSet {
		if beforeSet[i] != afterSet[i] {
			t.Fatalf("reorder changed the sorted catalogue set at index %d: %q vs %q", i, beforeSet[i], afterSet[i])
		}
	}
}

// Spec §2 counts reordering a variant or a payload as a structural mutation, on the
// same footing as adding or removing one. Hashing the sorted CaseID set would miss
// both: reordering authDefects leaves the sorted set (and its size) unchanged while
// remapping which entry a positional read would name.
func TestCatalogueFingerprintMovesOnVariantReorder(t *testing.T) {
	assertReorderMovesFingerprintOnly(t, func() {
		authDefects[0], authDefects[1] = authDefects[1], authDefects[0]
		t.Cleanup(func() { authDefects[0], authDefects[1] = authDefects[1], authDefects[0] })
	})
}

// A CaseID for apply and rest encodes a payload's INDEX, not its content
// (applyCaseID(cat, v.name, i)), so swapping two payloads within one variant produces
// the identical ID sequence before and after — a payload reorder is invisible to any
// fingerprint built from IDs alone. This is the case TestCatalogueFingerprintMovesOnVariantReorder
// cannot detect: authDefects is a flat []string where the ID IS the payload, so that
// test proves nothing about a payload dimension.
func TestCatalogueFingerprintMovesOnPayloadReorder(t *testing.T) {
	vs := variantsByCategory["logical"]
	var target *variant
	for i := range vs {
		if vs[i].name == "chunk-out-of-range" {
			target = &vs[i]
			break
		}
	}
	if target == nil {
		t.Fatal(`variant "chunk-out-of-range" not found in variantsByCategory["logical"]`)
	}
	if len(target.payloads) < 2 {
		t.Fatalf("chunk-out-of-range has %d payload(s), need >= 2 to reorder", len(target.payloads))
	}

	assertReorderMovesFingerprintOnly(t, func() {
		target.payloads[0], target.payloads[1] = target.payloads[1], target.payloads[0]
		t.Cleanup(func() { target.payloads[0], target.payloads[1] = target.payloads[1], target.payloads[0] })
	})
}
