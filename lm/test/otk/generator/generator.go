package generator

import "fmt"

// splitmix64 is a tiny, fixed PRNG so the seed→scenario mapping is stable across
// Go toolchain versions (math/rand makes no such cross-version guarantee). Any
// intentional change to this mapping is a corpus-version bump.
func splitmix64(x uint64) uint64 {
	x += 0x9e3779b97f4a7c15
	x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9
	x = (x ^ (x >> 27)) * 0x94d049bb133111eb
	return x ^ (x >> 31)
}

// Scenario is one generated adversarial case.
type Scenario struct {
	Seed         int64
	CaseID       CaseID // catalogue identity; see caseid.go
	Category     string // "logical" | "weird" | "textual" | "hack"
	Variant      string // e.g. "rtl-override"
	Field        string // the csv-file field the attack targets
	Name         string // the connector name actually submitted (may be a payload)
	ManifestYAML string // the adversarial csv-file manifest fed to `lm validate`
	Feature      string // human-readable Gherkin (the readable face)
}

// Generate deterministically synthesises one focused scenario from a seed by
// chaining splitmix64: category → variant → payload, then one field mutation.
func Generate(seed int64) Scenario {
	h := splitmix64(uint64(seed))
	cat := categories[int(h%uint64(len(categories)))]

	h = splitmix64(h)
	vs := variantsByCategory[cat]
	v := vs[int(h%uint64(len(vs)))]

	h = splitmix64(h)
	payloadIdx := int(h % uint64(len(v.payloads)))
	payload := v.payloads[payloadIdx]

	m := baseline(seed)
	v.mutate(&m, payload)

	return Scenario{
		Seed:         seed,
		CaseID:       applyCaseID(cat, v.name, payloadIdx),
		Category:     cat,
		Variant:      v.name,
		Field:        v.field,
		Name:         m.name,
		ManifestYAML: render(m),
		Feature:      renderFeature(seed, cat, v.name, v.field),
	}
}

func renderFeature(seed int64, cat, variant, field string) string {
	return fmt.Sprintf(`Feature: OTK adversarial apply (seed=%d)
  As OTK I persist an adversarial csv-file manifest through the hub
  and require it to create exactly the right connector, or nothing at all.

  Scenario: persist a csv-file manifest with a %s attack (%s) on field '%s' (seed=%d)
    Given the generated manifest for seed %d
    When I run "lm apply" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub state matches the apply outcome
    And the connector is cleaned up
    And the hub schema is intact
`, seed, cat, variant, field, seed, seed)
}

// RunScenario is one generated adversarial ingestion case: a benign baseline
// manifest pointing at a per-seed path, plus the adversarial source written there.
type RunScenario struct {
	Seed         int64
	CaseID       CaseID
	Category     string
	Variant      string
	Field        string
	ManifestYAML string // benign-but-valid csv-file manifest
	SourceCSV    string // adversarial source rows
	SourcePath   string // where the source must be written (baked into the manifest)
	Feature      string // human-readable Gherkin
}

// GenerateRun deterministically synthesises one ingestion scenario from a seed:
// the adversary lives in the source rows (GenerateSource); the manifest is a valid
// baseline pointing at a per-seed local path.
func GenerateRun(seed int64) RunScenario {
	sc := GenerateSource(seed)
	path := fmt.Sprintf("/tmp/otk/run/%d/in.csv", seed)
	m := baseline(seed)
	m.name = fmt.Sprintf("otk-run-%d", seed)
	m.path = path
	return RunScenario{
		Seed:         seed,
		CaseID:       runCaseID(sc.Category, sc.Variant),
		Category:     sc.Category,
		Variant:      sc.Variant,
		Field:        sc.Field,
		ManifestYAML: render(m),
		SourceCSV:    sc.CSV,
		SourcePath:   path,
		Feature:      renderRunFeature(seed, sc.Category, sc.Variant, sc.Field),
	}
}

func renderRunFeature(seed int64, cat, variant, field string) string {
	return fmt.Sprintf(`Feature: OTK adversarial ingestion (seed=%d)
  As OTK I ingest an adversarial csv-file source through the hub
  and require every source row to be accounted for, the table to match
  the run's claim, and nothing to leak or corrupt.

  Scenario: ingest a csv-file source with a %s attack (%s) in '%s' (seed=%d)
    Given the generated manifest and adversarial source for seed %d
    When I apply and run the connector
    Then the run reaches a terminal status
    And every source row is accounted for
    And the landed rows match the run's reported output
    And lm output is probed for robustness, text glitches, and leaks
    And the ingested data is cleaned up
    And the hub schema is intact
`, seed, cat, variant, field, seed, seed)
}

// authDefects are the adversarial token mutations the auth lane probes. The names
// are the contract between the generator (which picks one per seed) and the lane
// (which maps the name to an auth.Defect and an expected outcome).
var authDefects = []string{
	"wrong-role", "expired", "bad-audience", "bad-issuer",
	"tampered-sig", "cross-tenant", "garbage",
}

// AuthScenario is one generated adversarial auth case.
type AuthScenario struct {
	Seed       int64
	CaseID     CaseID
	DefectName string // one of authDefects
	Feature    string // human-readable Gherkin
}

// GenerateAuth deterministically selects one token defect per seed via the same
// splitmix64 chain as the other lanes.
func GenerateAuth(seed int64) AuthScenario {
	h := splitmix64(uint64(seed) ^ 0xA117) // distinct salt so auth ≠ apply selection
	defect := authDefects[int(h%uint64(len(authDefects)))]
	return AuthScenario{
		Seed:       seed,
		CaseID:     authCaseID(defect),
		DefectName: defect,
		Feature:    renderAuthFeature(seed, defect),
	}
}

func renderAuthFeature(seed int64, defect string) string {
	return fmt.Sprintf(`Feature: OTK adversarial auth (seed=%d)
  As OTK I present an adversarial JWT to the platform API
  and require the hub to reject it (or isolate the tenant) — never to leak or escalate.

  Scenario: present a token with the %s defect (seed=%d)
    Given the hub rejects the adversarial token for seed %d
`, seed, defect, seed, seed)
}

// GenerateRest deterministically synthesises one REST-connector adversarial case
// from a seed. The category routing uses the same splitmix64 chain and category
// order as Generate, so seed→category distributions are comparable.
func GenerateRest(seed int64) Scenario {
	h := splitmix64(uint64(seed))
	cat := categories[int(h%uint64(len(categories)))]

	h = splitmix64(h)
	vs := restVariantsByCategory[cat]
	v := vs[int(h%uint64(len(vs)))]

	h = splitmix64(h)
	payloadIdx := int(h % uint64(len(v.payloads)))
	payload := v.payloads[payloadIdx]

	m := restBaseline(seed)
	v.mutate(&m, payload)

	return Scenario{
		Seed:         seed,
		CaseID:       restCaseID(cat, v.name, payloadIdx),
		Category:     cat,
		Variant:      v.name,
		Field:        v.field,
		Name:         m.name,
		ManifestYAML: renderRest(m),
		Feature:      renderRestFeature(seed, cat, v.name, v.field),
	}
}

// GenerateEntity generates an adversarial entity scenario for the given seed.
// It alternates between apply and read sub-types based on the hashed seed parity.
func GenerateEntity(seed int64) EntityScenario {
	h := splitmix64(uint64(seed) ^ entitySalt)

	if h%2 == 0 {
		// apply sub-type
		applyVariants := entityApplyVariants()
		idx := int(splitmix64(h) % uint64(len(applyVariants)))
		v := applyVariants[idx]
		return EntityScenario{
			Seed:         seed,
			CaseID:       entityCaseID("apply", v.name),
			SubType:      "apply",
			ManifestYAML: v.manifestYAML,
			Variant:      v.name,
			UseUserRole:  v.useUserRole,
			Feature:      renderEntityApplyFeature(seed, v),
		}
	}
	// read sub-type
	readVariants := entityReadVariants()
	idx := int(splitmix64(h) % uint64(len(readVariants)))
	v := readVariants[idx]
	return EntityScenario{
		Seed:        seed,
		CaseID:      entityCaseID("read", v.name),
		SubType:     "read",
		Variant:     v.name, // was left empty: the read branch had no name at all
		ReadTarget:  v.readTarget,
		ReadQuery:   v.readQuery,
		CrossTenant: v.crossTenant,
		Feature:     renderEntityReadFeature(seed, v),
	}
}

// GenerateProcess generates an adversarial process-lane scenario for the given seed.
// It alternates between the apply (governance refusals) and inbox (attention surface)
// sub-types on the hashed seed parity, mirroring GenerateEntity.
func GenerateProcess(seed int64) ProcessScenario {
	h := splitmix64(uint64(seed) ^ processSalt)

	if h%2 == 0 {
		applyVariants := processApplyVariants()
		v := applyVariants[int(splitmix64(h)%uint64(len(applyVariants)))]
		return ProcessScenario{
			Seed:           seed,
			CaseID:         processCaseID("apply", v.name),
			SubType:        "apply",
			ManifestYAML:   v.manifestYAML,
			Variant:        v.name,
			UseUserRole:    v.useUserRole,
			ExpectedStatus: v.expectedStatus,
			Feature:        renderProcessApplyFeature(seed, v),
		}
	}
	inbox := inboxVariants()
	v := inbox[int(splitmix64(h)%uint64(len(inbox)))]
	return ProcessScenario{
		Seed:           seed,
		CaseID:         processCaseID("inbox", v.name),
		SubType:        "inbox",
		Variant:        v.name,
		InboxOp:        v.op,
		InboxQuery:     v.query,
		CrossTenant:    v.crossTenant,
		WrongRole:      v.wrongRole,
		ExpectedStatus: v.expectedStatus,
		MaxRows:        v.maxRows,
		Feature:        renderInboxFeature(seed, v),
	}
}

func renderRestFeature(seed int64, cat, variant, field string) string {
	return fmt.Sprintf(`Feature: OTK adversarial apply — REST (seed=%d)
  As OTK I submit an adversarial rest-generic manifest through the hub
  and require it to create exactly the right connector, or nothing at all.

  Scenario: apply a rest-generic manifest with a %s attack (%s) on field '%s' (seed=%d)
    Given the generated REST manifest for seed %d
    When I run "lm apply" on it
    Then lm is probed for robustness
    And lm output is probed for text glitches
    And lm output is probed for leaks
    And the hub state matches the apply outcome
    And the connector is cleaned up
    And the hub schema is intact
`, seed, cat, variant, field, seed, seed)
}
