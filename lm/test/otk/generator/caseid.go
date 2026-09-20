package generator

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
)

// CaseID names one catalogue entry: the finest behaviour the generator can express.
// It is built from the lane's own selection chain, where the variant and the payload
// index are known — never recovered by diffing the text a case renders, which is how
// two payloads that happen to share a substring get miscounted as one.
//
// The shape is per-lane because the catalogues are: apply and rest select a payload
// within a variant, run does not (sourceVariant.build yields a single content), auth
// is a flat defect list, entity and process are sub-typed, view is bare.
//
//	apply/hack/sqli#0
//	run/textual/rtl-in-data-cell
//	rest/hack/path-traversal-endpoint#1
//	auth/bad-audience
//	entity/read/cross-tenant-read
//	process/inbox/huge-limit
//	view/unknown-scope
//
// A CaseID is unique and deterministic within a build. It is NOT a cross-version
// contract: renaming a variant renames its ID. Replay travels on the seed, and the
// pair is only meaningful within one CatalogueFingerprint.
type CaseID string

// Lane returns the lane segment of an ID ("apply", "run", …).
func (c CaseID) Lane() string {
	for i := 0; i < len(c); i++ {
		if c[i] == '/' {
			return string(c[:i])
		}
	}
	return string(c)
}

// The payload arrives as an INDEX, not as its text: two payloads of one variant can
// never collapse into one ID, because distinct ints format distinctly. That used to be
// pinned by a seed sweep; the signature enforces it now, and TestWitnessedSetEqualsCatalogue
// proves every index is actually reached.
func applyCaseID(category, variant string, payloadIdx int) CaseID {
	return CaseID(fmt.Sprintf("apply/%s/%s#%d", category, variant, payloadIdx))
}

func runCaseID(category, variant string) CaseID {
	return CaseID(fmt.Sprintf("run/%s/%s", category, variant))
}

func restCaseID(category, variant string, payloadIdx int) CaseID {
	return CaseID(fmt.Sprintf("rest/%s/%s#%d", category, variant, payloadIdx))
}

func authCaseID(defect string) CaseID {
	return CaseID("auth/" + defect)
}

func entityCaseID(subType, variant string) CaseID {
	return CaseID(fmt.Sprintf("entity/%s/%s", subType, variant))
}

func processCaseID(subType, variant string) CaseID {
	return CaseID(fmt.Sprintf("process/%s/%s", subType, variant))
}

func viewCaseID(variant string) CaseID {
	return CaseID("view/" + variant)
}

// catalogueEntry is one case in declared order. payload carries the payload's own
// content for the two lanes that have one (apply, rest) — a CaseID alone only encodes
// that payload's index, not what it contains, so a payload reorder within a variant
// would remap indices without changing a single ID. Lanes with no payload dimension
// (run, auth, entity, process, view) leave payload empty.
type catalogueEntry struct {
	id      CaseID
	payload string
}

// catalogueEntries is the single enumeration every catalogue derivation reads: walks
// each catalogue table in the order it is declared — categories by sorted key (map
// iteration order is not stable in Go), then each category's variants and each
// variant's payloads in slice order. Catalogue() sorts its IDs; CatalogueFingerprint()
// hashes it as declared, id and payload both. There is no second walk to keep in sync
// with this one.
//
// storeVariants() is deliberately absent: the saved-view store round-trip is a
// committed-corpus smoke, not a Gold lane. Counting it would put a case in the
// expected set that Gold never runs.
func catalogueEntries() []catalogueEntry {
	var out []catalogueEntry
	for _, cat := range sortedKeys(variantsByCategory) {
		for _, v := range variantsByCategory[cat] {
			for i, p := range v.payloads {
				out = append(out, catalogueEntry{id: applyCaseID(cat, v.name, i), payload: p})
			}
		}
	}
	for _, cat := range sortedKeys(sourceVariantsByCategory) {
		for _, v := range sourceVariantsByCategory[cat] {
			out = append(out, catalogueEntry{id: runCaseID(cat, v.name)})
		}
	}
	for _, cat := range sortedKeys(restVariantsByCategory) {
		for _, v := range restVariantsByCategory[cat] {
			for i, p := range v.payloads {
				out = append(out, catalogueEntry{id: restCaseID(cat, v.name, i), payload: p})
			}
		}
	}
	for _, d := range authDefects {
		out = append(out, catalogueEntry{id: authCaseID(d)})
	}
	for _, v := range entityApplyVariants() {
		out = append(out, catalogueEntry{id: entityCaseID("apply", v.name)})
	}
	for _, v := range entityReadVariants() {
		out = append(out, catalogueEntry{id: entityCaseID("read", v.name)})
	}
	for _, v := range processApplyVariants() {
		out = append(out, catalogueEntry{id: processCaseID("apply", v.name)})
	}
	for _, v := range inboxVariants() {
		out = append(out, catalogueEntry{id: processCaseID("inbox", v.name)})
	}
	for _, v := range viewVariants() {
		out = append(out, catalogueEntry{id: viewCaseID(v.name)})
	}
	return out
}

// Catalogue returns every case the seven Gold lanes can produce, sorted — it does not
// deduplicate, because the tables catalogueEntries() reads never declare the same
// CaseID twice (TestCatalogueIsSortedAndUnique guards that). It is one of two views
// over catalogueEntries(), the OTHER derivation of the same identities being the seed
// sweep in Witnesses(). The guardian compares the two; that independence is what makes
// the comparison mean anything.
func Catalogue() []CaseID {
	entries := catalogueEntries()
	out := make([]CaseID, len(entries))
	for i, e := range entries {
		out[i] = e.id
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

// CatalogueFingerprint is the catalogue's version: 12 hex characters of SHA-256 over
// catalogueEntries() in DECLARED order — not the sorted order Catalogue() returns —
// hashing each entry's CaseID and payload content together.
//
// A structural mutation is adding, removing OR REORDERING a variant or a payload
// (spec §2). Two mutations would otherwise escape a hash of Catalogue()'s sorted ID
// set alone: reordering a table remaps which payload index a case names even though
// the sorted ID set is unchanged, and — for apply and rest, where a CaseID encodes a
// payload's index rather than its content — reordering the payloads *within* one
// variant leaves every ID (and hence the whole sorted set) byte-identical while
// silently renaming what each index means. Hashing catalogueEntries() in declared
// order, payload content included, moves the fingerprint on both.
//
// It cannot go stale, because it IS the catalogue — there is no second value to keep
// in sync. "Corpus-version bump" used to be prose in two comments with nothing behind
// it; this is the mechanism. A structural mutation of any catalogue moves this value,
// TestCatalogueFingerprintIsPinned goes red, and the bump becomes a commit where
// someone read the diff.
//
// It is also the third term of a replay address: a CaseID and its witness seed only
// identify a case within one fingerprint.
func CatalogueFingerprint() string {
	h := sha256.New()
	for _, e := range catalogueEntries() {
		h.Write([]byte(e.id))
		h.Write([]byte{'\n'})
		h.Write([]byte(e.payload))
		h.Write([]byte{'\n'})
	}
	return hex.EncodeToString(h.Sum(nil))[:12]
}

// sortedKeys returns a map's keys in sorted order, so a category walk is deterministic
// without imposing an order on the categories themselves — only Go's map iteration is
// what is being defended against here, not the category ordering.
func sortedKeys[V any](m map[string][]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
