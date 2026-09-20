package generator

import (
	"fmt"
	"sort"
	"strings"
	"sync"
)

// catalogueScanBound is how far the witness sweep looks for a seed reaching each
// catalogue case. The current catalogue is fully witnessed well inside it —
// TestScanBoundHasMargin pins that margin and fails if it thins below 2x — and the
// sweep is pure CPU with no hub, so the bound buys headroom for entries added later.
// The bound is not only reachability headroom: it also bounds STRAY detection. A
// CaseID the generators produce but no table declares (see sweepCaseIDs) is only
// visible to the guardian if some seed at or below the bound produces it — a stray
// first appearing at seed 2001 would sit outside the sweep entirely. The sweep costs
// around 0.7s, not "no measurable cost".
const catalogueScanBound = 2000

// Witness is one catalogue case and the first seed that produces it. The lane is not
// stored: it is the ID's first segment, and a field that can only ever equal
// ID.Lane() is a second copy to keep in sync plus a test to police the agreement.
type Witness struct {
	ID   CaseID
	Seed int64
}

// Witnesses sweeps seeds 1..catalogueScanBound and returns, for every catalogue case,
// the first seed that reaches it — sorted by ID so the emitted corpus order is stable.
//
// The witnesses are rediscovered rather than frozen into a checked-in table: a
// hardcoded list would go stale in silence the first time a payload was added. The
// result is memoised per CATALOGUE FINGERPRINT, not per process — the sweep is
// deterministic, so repeating it for each of the seven guards that want it buys
// nothing, while keying the cache on the fingerprint means a test that mutates a
// variant table (the reorder tests do exactly that) is swept afresh instead of being
// handed a stale answer. Nothing about the sweep itself is filtered or cut short.
//
// Reaching the bound with any case unwitnessed is an error naming the gap, not a
// shorter slice. A catalogue entry no seed can select is a dead entry, and this is
// the mechanism that says so.
func Witnesses() ([]Witness, error) {
	key := CatalogueFingerprint()

	witnessMu.Lock()
	defer witnessMu.Unlock()
	if !witnessValid || witnessKey != key {
		witnessCache, witnessErr = witnessesWithin(catalogueScanBound)
		witnessKey, witnessValid = key, true
	}
	if witnessErr != nil {
		return nil, witnessErr
	}
	// Hand back a copy: callers sort, slice and range over this, and a shared backing
	// array would let one caller's convenience become another's surprise.
	out := make([]Witness, len(witnessCache))
	copy(out, witnessCache)
	return out, nil
}

var (
	witnessMu    sync.Mutex
	witnessKey   string
	witnessValid bool
	witnessCache []Witness
	witnessErr   error
)

func witnessesWithin(bound int64) ([]Witness, error) {
	sweep := sweepCaseIDs(bound)
	want := map[CaseID]bool{}
	for _, id := range Catalogue() {
		want[id] = true
	}
	out := make([]Witness, 0, len(want))
	var missing []string
	matched := 0
	for id := range want {
		seed, ok := sweep[id]
		if !ok {
			missing = append(missing, string(id))
			continue
		}
		matched++
		out = append(out, Witness{ID: id, Seed: seed})
	}
	if len(missing) > 0 {
		sort.Strings(missing)
		return nil, fmt.Errorf(
			"seeds 1..%d witness %d of %d catalogue cases; %d unreached:\n  %s\n"+
				"either raise catalogueScanBound or fix the entry's reachability — a case no seed selects is dead",
			bound, matched, len(want), len(missing), strings.Join(missing, "\n  "))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

// sweepCaseIDs sweeps seeds 1..bound and records, for every CaseID the seven
// generators actually produce, the first seed that produced it.
//
// It is deliberately unfiltered by Catalogue() and has no early exit tied to a
// wanted count. Filtering candidates through Catalogue() before comparing the
// sweep against Catalogue() makes that comparison unable to fail — whatever the
// table declares, the sweep could only ever report a subset of it, so a stray
// CaseID the generators produce but no table declares could never surface. An
// early exit the moment every wanted case is found has the same blind spot from
// the other side: it stops looking before a stray that only appears at a later
// seed is ever seen. Both omissions are what let a broken table pass silently
// once already; this sweep is unfiltered and exhaustive so it cannot again.
func sweepCaseIDs(bound int64) map[CaseID]int64 {
	found := make(map[CaseID]int64, len(Catalogue()))
	for s := int64(1); s <= bound; s++ {
		for _, id := range caseIDsForSeed(s) {
			if _, ok := found[id]; !ok {
				found[id] = s
			}
		}
	}
	return found
}

// caseIDsForSeed is what one seed produces across every Gold lane. It calls the real
// generators through the lane table rather than replaying their selection arithmetic,
// so the sweep can never drift from what a scenario actually runs.
func caseIDsForSeed(seed int64) []CaseID {
	out := make([]CaseID, 0, len(lanes))
	for _, l := range lanes {
		out = append(out, l.CaseID(seed))
	}
	return out
}
