package generator

import "fmt"

// entityResVariant is one of the three properties #622's ambiguity/review API must hold that a
// single request/response check cannot express (#643, part of #613's arc): each is a short
// scripted sequence — seed rows, resolve, assert, resolve again — rather than one call. Bare list
// like view (no sub-typing): every case exercises the same fixture entity (otk_res_customer,
// seed_entity_resolution.go) and differs only in which steps its Gherkin body chains.
type entityResVariant struct {
	name    string
	steps   []string // the Gherkin body's When/Then lines, in order (the Given line is common)
	feature string
}

const entityResSalt = uint64(0xE12E5)

func entityResVariants() []entityResVariant {
	return []entityResVariant{
		{
			name: "ambiguous-mailbox-never-merges",
			steps: []string{
				`    When I run a resolution pass on "otk_res_customer"`,
				`    Then the fixture's outcome is ambiguous, never merged`,
			},
			feature: "two independently-known identities sharing a mailbox must never silently merge into one",
		},
		{
			name: "distinct-assertion-survives-replay",
			steps: []string{
				`    When I run a resolution pass on "otk_res_customer"`,
				`    And I assert distinct on the fixture's linked pair`,
				`    And I run a resolution pass on "otk_res_customer"`,
				`    And I run a resolution pass on "otk_res_customer"`,
				`    Then the distinct assertion still holds after the replay`,
			},
			feature: "a distinct assertion must not be undone by a later replay",
		},
		{
			name: "stale-resolution-swap-refused",
			steps: []string{
				`    When I run a resolution pass on "otk_res_customer"`,
				`    And I corrupt the fixture's first row to collide with its second row's resolved key`,
				`    And I run a resolution pass on "otk_res_customer"`,
				`    Then the prior resolution is refused and recorded, not silently swapped`,
			},
			feature: "re-evaluation must never silently move an already-resolved canonical id onto another",
		},
	}
}

// GenerateEntityResolution deterministically selects one of the three cases from a seed.
func GenerateEntityResolution(seed int64) EntityResolutionScenario {
	h := splitmix64(uint64(seed) ^ entityResSalt)
	variants := entityResVariants()
	v := variants[int(splitmix64(h)%uint64(len(variants)))]
	return EntityResolutionScenario{
		Seed:    seed,
		CaseID:  entityResCaseID(v.name),
		Variant: v.name,
		Feature: renderEntityResFeature(seed, v),
	}
}

// EntityResolutionCaseID resolves a case's CaseID from its name — steps_entity_resolution.go
// looks this up at run time (the Given step only ever observes the case name the Gherkin line
// carries), mirroring EntityReadCaseID's role for the read lane.
func EntityResolutionCaseID(name string) CaseID {
	return entityResCaseID(name)
}

func renderEntityResFeature(seed int64, v entityResVariant) string {
	body := fmt.Sprintf(`    Given the resolution fixture %q is seeded [seed=%d]
`, v.name, seed)
	for _, line := range v.steps {
		body += line + "\n"
	}
	return fmt.Sprintf(`Feature: entity-resolution %s [seed=%d]
  Scenario: %s
%s`, v.name, seed, v.feature, body)
}

// EntityResolutionScenario holds the generated scenario for one seed.
type EntityResolutionScenario struct {
	Seed    int64
	CaseID  CaseID
	Variant string
	Feature string
}
