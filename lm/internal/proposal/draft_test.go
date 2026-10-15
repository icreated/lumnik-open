package proposal

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gopkg.in/yaml.v3"
)

func corpus(t *testing.T, rel string) *Dossier {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(repoRoot(t), "lumnik-hub/src/test/resources/proposal/fixtures", rel))
	require.NoError(t, err)
	d, err := Read(string(raw))
	require.NoError(t, err)
	return d
}

func draft(t *testing.T, d *Dossier, target Target) string {
	t.Helper()
	out, _, err := Draft(d, target)
	require.NoError(t, err)
	return out
}

var allFlags = Target{Name: "wf-orders", Scope: "sales", Table: "connector.t_commandes", Column: "statut", Key: "n_commande", After: "14d"}

// A golden is regenerated only on purpose (LM_UPDATE_GOLDEN=1) and then READ: the honesty
// properties below are asserted in code, so a golden never stands alone as the proof.
func golden(t *testing.T, name, got string) {
	t.Helper()
	path := filepath.Join("testdata", name+".yaml")
	if os.Getenv("LM_UPDATE_GOLDEN") == "1" {
		require.NoError(t, os.MkdirAll("testdata", 0o755))
		require.NoError(t, os.WriteFile(path, []byte(got), 0o644))
	}
	want, err := os.ReadFile(path)
	require.NoError(t, err, "golden missing: run once with LM_UPDATE_GOLDEN=1, then read the file")
	assert.Equal(t, string(want), got)
}

func TestGoldens(t *testing.T) {
	apply := &Dossier{Created: "2026-10-03", Catalogue: "2026.10.0", Leads: []Lead{{
		ID: "L1", Rule: ruleID, Title: "T", Says: "S", DoesNotSay: []string{"D"}, ProposesKind: "Workflow",
		Uses: []string{"spec.on.table"},
		Conditions: []Condition{{ID: "C5", Needs: "dormancy-target-accepted", Verifier: "workflow-apply-door",
			Check:    "refuted",
			Evidence: []Evidence{{Via: "workflow-apply-door", Target: "connector.t_x", Observed: "refused"}}}}}}}

	for name, c := range map[string]struct {
		d      *Dossier
		target Target
	}{
		"all-flags":          {corpus(t, "valid/minimal.md"), allFlags},
		"no-flag":            {corpus(t, "valid/minimal.md"), Target{}},
		"after-without-key":  {corpus(t, "valid/minimal.md"), Target{Table: "connector.t_commandes", Column: "statut", After: "14d"}},
		"no-evidence":        {corpus(t, "valid/unanswered.md"), allFlags},
		"refuted":            {corpus(t, "valid-recheck/refuted-over-unknown.md"), allFlags},
		"history-kept":       {corpus(t, "valid-recheck/downgraded-evidence-kept.md"), allFlags},
		"apply-door-refuted": {apply, allFlags},
	} {
		t.Run(name, func(t *testing.T) { golden(t, name, draft(t, c.d, c.target)) })
	}
}

func TestDraft_confirmedFixtureCarriesEvidenceAsCarried(t *testing.T) {
	out := draft(t, corpus(t, "valid-recheck/confirmed-with-evidence.md"), allFlags)
	assert.Contains(t, out, "Carried as confirmed:")
	assert.Contains(t, out, "confirmed — the dossier carries a hub confirmation (schema 1 does not date it)")
	assert.Contains(t, out, "evidence the dossier carries (not necessarily from this run):")
}

func TestStatement_isTheSpecTableAndNothingElse(t *testing.T) {
	for _, c := range []struct {
		name string
		cond Condition
		want string
	}{
		{"confirmed", Condition{Verifier: "workflow-data-truth", Check: "confirmed"},
			"confirmed — the dossier carries a hub confirmation (schema 1 does not date it)"},
		{"refuted data-truth", Condition{Verifier: "workflow-data-truth", Check: "refuted"},
			"REFUTED — the dossier carries a hub refutation; settle it before applying"},
		{"refuted apply-door", Condition{Verifier: "workflow-apply-door", Check: "refuted"},
			"REFUTED — the dossier carries a hub refutation; settle it before applying"},
		{"unverified", Condition{Verifier: "workflow-data-truth", Check: "unverified"},
			"unverified — no hub confirmation in this dossier (not checked, or the hub abstained)"},
		{"unverified none", Condition{Verifier: "none", Check: "unverified"},
			"to establish yourself; no check verifies it"},
		{"missing none", Condition{Verifier: "none", Check: "missing"},
			"to establish yourself; no check verifies it. Also missing — not backed by a prospect's yes (unanswered, unknown or not asked)"},
		{"missing", Condition{Verifier: "workflow-data-truth", Check: "missing"},
			"missing — not backed by a prospect's yes (unanswered, unknown or not asked)"},
	} {
		t.Run(c.name, func(t *testing.T) { assert.Equal(t, c.want, statement(c.cond)) })
	}
}

// No sentence of lm's own may claim a freshness or a safety it did not see. The dossier here carries
// no text of its own that could trip the scan.
func TestDraft_neverClaimsWhatItDidNotSee(t *testing.T) {
	banned := regexp.MustCompile(`(?i)\b(now|currently|just|freshly|verified|safe|guaranteed|proven)\b`)
	out := draft(t, &Dossier{Created: "2026-10-03", Catalogue: "c", Leads: []Lead{{ID: "L", Rule: ruleID,
		Conditions: []Condition{
			{ID: "A", Needs: "a", Verifier: "workflow-data-truth", Check: "confirmed",
				Evidence: []Evidence{{Via: "workflow-data-truth", Target: "t", Observed: "o"}}},
			{ID: "B", Needs: "b", Verifier: "none", Check: "unverified"},
			{ID: "C", Needs: "c", Verifier: "workflow-data-truth", Check: "unverified"},
			{ID: "D", Needs: "d", Verifier: "workflow-data-truth", Check: "missing"},
			{ID: "E", Needs: "e", Verifier: "workflow-data-truth", Check: "refuted",
				Evidence: []Evidence{{Via: "workflow-data-truth", Target: "t", Observed: "o"}}},
			// the "history carried, not verified by this file" sentence is only written for evidence on
			// a condition that is neither confirmed nor refuted
			{ID: "F", Needs: "f", Verifier: "workflow-data-truth", Check: "unverified",
				Evidence: []Evidence{{Via: "workflow-data-truth", Target: "t", Observed: "o"}}},
		}}}}, Target{})
	assert.Contains(t, out, "history carried", "the dossier must reach the sentence this scan exists to read")
	// "unverified" is lm's word for the absence of a check, and "not verified by this file" negates the
	// claim: neither says the evidence was seen, so both are scrubbed before the scan
	scrubbed := strings.ReplaceAll(strings.ReplaceAll(out, "unverified", ""), "not verified by this file", "")
	assert.Empty(t, banned.FindAllString(scrubbed, -1))
}

func TestDraft_orderIsRefutedMissingUnverifiedConfirmed(t *testing.T) {
	out := draft(t, &Dossier{Created: "2026-10-03", Catalogue: "c", Leads: []Lead{{ID: "L", Rule: ruleID,
		Conditions: []Condition{
			{ID: "K", Needs: "k", Verifier: "workflow-data-truth", Check: "confirmed",
				Evidence: []Evidence{{Via: "workflow-data-truth", Target: "t", Observed: "o"}}},
			{ID: "U", Needs: "u", Verifier: "workflow-data-truth", Check: "unverified"},
			{ID: "M", Needs: "m", Verifier: "workflow-data-truth", Check: "missing"},
			{ID: "R", Needs: "r", Verifier: "workflow-data-truth", Check: "refuted",
				Evidence: []Evidence{{Via: "workflow-data-truth", Target: "t", Observed: "o"}}},
		}}}}, Target{})
	at := func(id string) int { return strings.Index(out, "  "+id+" ") }
	assert.True(t, at("R") < at("M") && at("M") < at("U") && at("U") < at("K"), out)
	assert.True(t, strings.Index(out, "Still to settle") < at("R"))
	assert.True(t, at("U") < strings.Index(out, "Carried as confirmed"))
}

func TestDraft_historyOnAnUnconfirmedConditionIsNotPresentedAsFresh(t *testing.T) {
	out := draft(t, corpus(t, "valid-recheck/downgraded-evidence-kept.md"), allFlags)
	assert.Contains(t, out, "history carried, not verified by this file:")
	assert.NotContains(t, out, "evidence the dossier carries")
}

func TestDraft_cardinality(t *testing.T) {
	lead := func(rule string) Lead { return Lead{ID: rule, Rule: rule, ProposesKind: "Workflow"} }
	_, _, err := Draft(&Dossier{Leads: []Lead{lead("other@1")}}, Target{})
	assert.True(t, errors.Is(err, ErrNoLead))
	_, _, err = Draft(&Dossier{}, Target{})
	assert.True(t, errors.Is(err, ErrNoLead))
	_, _, err = Draft(&Dossier{Leads: []Lead{lead(ruleID), lead(ruleID)}}, Target{})
	assert.True(t, errors.Is(err, ErrLeadRepeated))
	_, skipped, err := Draft(&Dossier{Leads: []Lead{lead("other@1"), lead(ruleID)}}, Target{})
	require.NoError(t, err)
	assert.Equal(t, []string{"other@1"}, skipped)
}

// What a hostile dossier says may widen the comments, never the manifest: the output parses to
// exactly the keys of the same draft from a harmless dossier.
func TestDraft_noDossierStringBecomesYAML(t *testing.T) {
	hostile := "x\nspec:\n  scope: evil\u2028kind: Evil\n---\nkind: Other"
	bad := &Dossier{Created: "2026-10-03", Catalogue: hostile, Words: hostile, Leads: []Lead{{
		ID: hostile, Rule: ruleID, Title: hostile, Says: hostile, DoesNotSay: []string{hostile},
		ProposesKind: hostile, Uses: []string{hostile},
		Conditions: []Condition{{ID: hostile, Needs: hostile, Verifier: "workflow-data-truth",
			Check: "refuted", Note: hostile,
			Evidence: []Evidence{{Via: "workflow-data-truth", Target: hostile, Observed: hostile}}}}}}}
	harmless := &Dossier{Created: "2026-10-03", Catalogue: "c", Leads: []Lead{{ID: "L1", Rule: ruleID}}}

	parse := func(d *Dossier) map[string]any {
		var m map[string]any
		require.NoError(t, yaml.Unmarshal([]byte(draft(t, d, allFlags)), &m))
		return m
	}
	assert.Equal(t, parse(harmless), parse(bad))
}

func TestDraft_theYAMLIsReadableAndHasNoStatesKey(t *testing.T) {
	var m map[string]any
	require.NoError(t, yaml.Unmarshal([]byte(draft(t, corpus(t, "valid/minimal.md"), allFlags)), &m))
	spec := m["spec"].(map[string]any)
	assert.NotContains(t, spec, "states")
	assert.NotContains(t, spec, "transitions")
	assert.Equal(t, "declared", spec["provenance"])
	assert.Equal(t, map[string]any{"table": "connector.t_commandes", "column": "statut", "key": "n_commande"}, spec["on"])
	assert.Equal(t, map[string]any{"after": "14d"}, spec["dormancy"])
}

func TestDraft_unknownFieldsAreOmittedNeverFilled(t *testing.T) {
	out := draft(t, corpus(t, "valid/minimal.md"), Target{})
	assert.NotContains(t, strings.ToUpper(out), "TODO")
	var m map[string]any
	require.NoError(t, yaml.Unmarshal([]byte(out), &m))
	assert.Nil(t, m["metadata"], "no --name: metadata has no name, not a placeholder one")
	spec := m["spec"].(map[string]any)
	assert.NotContains(t, spec, "scope")
	assert.Nil(t, spec["on"])
	assert.NotContains(t, spec, "dormancy")
}

// Schema 1 cannot tell this run's evidence from imported history, and an evidence block is text a
// person can edit: lm must not state, as its own fact, that the hub replayed those targets.
func TestDraft_theFixedTextDoesNotVouchForTheHubsProvenance(t *testing.T) {
	out := draft(t, corpus(t, "valid-recheck/confirmed-with-evidence.md"), allFlags)
	assert.NotContains(t, out, "are what the hub replayed")
	assert.Contains(t, out, "what the dossier says the hub replayed")
}

// The rules of the leads that are not drafted are dossier text too, and they leave Draft for the
// terminal: they must come back already flattened and escaped, like every other dossier string.
func TestDraft_theRulesItNamesAsNotDraftedAreSafeToPrint(t *testing.T) {
	hostile := "other@1" + "\n" + "warning: applied" + "\u202e" + "!"
	_, skipped, err := Draft(&Dossier{Leads: []Lead{{ID: "A", Rule: hostile}, {ID: "B", Rule: ruleID}}}, Target{})
	require.NoError(t, err)
	require.Len(t, skipped, 1)
	for _, r := range skipped[0] {
		assert.False(t, unsafeRune(r) || lineBreak(r), "U+%04X reaches the terminal", r)
	}
	assert.Contains(t, skipped[0], "other@1")
	assert.Contains(t, skipped[0], "warning: applied", "flattened, not dropped: the reader still sees what the dossier said")
}
