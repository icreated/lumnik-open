package generator

import (
	"sort"
	"strings"
	"testing"
)

// THE guardian. Set equality, not cardinality: one entry missing and one stray
// entry present leave the count untouched, and a size comparison would go green
// over a catalogue it is not covering. That shape is not hypothetical — it is what
// an identity key reading EntityScenario.Variant produced while this was measured.
func TestWitnessedSetEqualsCatalogue(t *testing.T) {
	sweep := sweepCaseIDs(catalogueScanBound)
	expected := map[CaseID]bool{}
	for _, id := range Catalogue() {
		expected[id] = true
	}
	var missing, unexpected []string
	for id := range expected {
		if _, ok := sweep[id]; !ok {
			missing = append(missing, string(id))
		}
	}
	for id := range sweep {
		if !expected[id] {
			unexpected = append(unexpected, string(id))
		}
	}
	sort.Strings(missing)
	sort.Strings(unexpected)
	if len(missing) > 0 {
		t.Errorf("%d catalogue case(s) no seed reaches:\n  %s", len(missing), strings.Join(missing, "\n  "))
	}
	if len(unexpected) > 0 {
		t.Errorf("%d witnessed case(s) no table declares:\n  %s", len(unexpected), strings.Join(unexpected, "\n  "))
	}
}

func TestWitnessesAreSorted(t *testing.T) {
	ws, err := Witnesses()
	if err != nil {
		t.Fatalf("Witnesses(): %v", err)
	}
	if !sort.SliceIsSorted(ws, func(i, j int) bool { return ws[i].ID < ws[j].ID }) {
		t.Error("Witnesses() must be sorted by ID so the corpus order is stable")
	}
	for _, w := range ws {
		if w.Seed <= 0 {
			t.Errorf("witness %q has a non-positive seed %d", w.ID, w.Seed)
		}
	}
}

// A witness must actually reproduce its case: the point of keeping the seed. It must
// also be exactly the catalogue — not merely as many witnesses as the catalogue has
// entries, which a duplicate-plus-omission pair would satisfy just as well.
func TestWitnessSeedReproducesItsCase(t *testing.T) {
	ws, err := Witnesses()
	if err != nil {
		t.Fatalf("Witnesses(): %v", err)
	}
	expected := map[CaseID]bool{}
	for _, id := range Catalogue() {
		expected[id] = true
	}
	seen := map[CaseID]bool{}
	for _, w := range ws {
		// Deliberately NOT routed through the lane table that caseIDsForSeed uses:
		// this assertion is worth having only because it spells the lane -> generator
		// mapping a second, independent time. Through the shared table it would be
		// checking the sweep against itself. The default arm below is what keeps the
		// duplication honest — an eighth lane reddens here loudly.
		var got CaseID
		switch w.ID.Lane() {
		case "apply":
			got = Generate(w.Seed).CaseID
		case "run":
			got = GenerateRun(w.Seed).CaseID
		case "rest":
			got = GenerateRest(w.Seed).CaseID
		case "auth":
			got = GenerateAuth(w.Seed).CaseID
		case "entity":
			got = GenerateEntity(w.Seed).CaseID
		case "process":
			got = GenerateProcess(w.Seed).CaseID
		case "view":
			got = GenerateView(w.Seed).CaseID
		default:
			t.Fatalf("witness %q has unknown lane %q", w.ID, w.ID.Lane())
		}
		if got != w.ID {
			t.Errorf("seed %d does not reproduce %q, it yields %q", w.Seed, w.ID, got)
		}
		if !expected[w.ID] {
			t.Errorf("witness %q is not a catalogue entry", w.ID)
		}
		seen[w.ID] = true
	}
	for id := range expected {
		if !seen[id] {
			t.Errorf("catalogue entry %q has no witness", id)
		}
	}
}

// Exhaustion is a hard error that NAMES what it could not reach. Silence on a
// partial sweep would be a false green: a catalogue entry the sweep never reached
// would ship as if it were covered.
func TestScanBoundExhaustionIsAnErrorThatNamesTheGap(t *testing.T) {
	_, err := witnessesWithin(3)
	if err == nil {
		t.Fatal("a bound of 3 cannot witness 136 cases; that must be an error")
	}
	if !strings.Contains(err.Error(), "apply/") && !strings.Contains(err.Error(), "rest/") {
		t.Errorf("exhaustion error must list the missing CaseIDs, got: %v", err)
	}
	if !strings.Contains(err.Error(), "seeds 1..3") {
		t.Errorf("exhaustion error must name the bound it hit, got: %v", err)
	}
}

// The declared bound must hold real margin over what the catalogue needs today.
func TestScanBoundHasMargin(t *testing.T) {
	ws, err := Witnesses()
	if err != nil {
		t.Fatalf("Witnesses(): %v", err)
	}
	var max int64
	for _, w := range ws {
		if w.Seed > max {
			max = w.Seed
		}
	}
	if max*2 > catalogueScanBound {
		t.Fatalf("the catalogue needs seeds up to %d against a bound of %d — less than 2x margin; raise catalogueScanBound",
			max, catalogueScanBound)
	}
	t.Logf("whole catalogue witnessed by seed %d (bound %d)", max, catalogueScanBound)
}

// Witnesses() memoises its sweep, keyed on the catalogue fingerprint. A cache whose
// invalidation nobody has watched work is the same untested guard this harness exists
// to refuse — so mutate a table and require the next call to see it. Without the
// fingerprint key this test reads the stale 136 and fails.
func TestWitnessesCacheInvalidatesOnCatalogueChange(t *testing.T) {
	before, err := Witnesses()
	if err != nil {
		t.Fatalf("Witnesses(): %v", err)
	}
	if len(before) != len(Catalogue()) {
		t.Fatalf("baseline: %d witnesses for %d catalogue cases", len(before), len(Catalogue()))
	}

	vs := variantsByCategory["logical"]
	idx := -1
	for i, v := range vs {
		if v.name == "chunk-out-of-range" {
			idx = i
			break
		}
	}
	if idx < 0 {
		t.Fatal("chunk-out-of-range is gone from variantsByCategory[\"logical\"] — repoint this test")
	}
	original := vs[idx].payloads
	vs[idx].payloads = append(append([]string{}, original...), "424242")
	t.Cleanup(func() { vs[idx].payloads = original })

	after, err := Witnesses()
	if err != nil {
		t.Fatalf("Witnesses() after the catalogue grew: %v", err)
	}
	if len(after) != len(before)+1 {
		t.Fatalf("the catalogue grew by one but Witnesses() returned %d, was %d — the cache went stale",
			len(after), len(before))
	}
	if len(after) != len(Catalogue()) {
		t.Fatalf("%d witnesses for %d catalogue cases after the change", len(after), len(Catalogue()))
	}
}
