package proposal

import (
	"errors"
	"fmt"
	"sort"
	"strings"
)

// ruleID is the one rule 3b drafts. The catalogue holds one rule today; a second would be a
// second decision, not a table lookup waiting to happen.
const ruleID = "stalled-documents@1"

var (
	ErrNoLead       = errors.New("the dossier has no " + ruleID + " lead — nothing to draft")
	ErrLeadRepeated = errors.New("the dossier carries the " + ruleID + " lead more than once — refusing to choose between them")
)

// Draft writes the start of a kind:Workflow manifest. It returns the YAML, and the rules of the
// leads it did not draft, already flattened to one safe line each (they are dossier text that the
// caller prints). A field it was not given is left out and marked by a comment: the file
// stays a draft until a person completes it, because it never carries a states graph.
func Draft(d *Dossier, t Target) (string, []string, error) {
	lead, skipped, err := selectLead(d)
	if err != nil {
		return "", nil, err
	}
	var b strings.Builder
	w := func(format string, a ...any) {
		b.WriteString("# ")
		b.WriteString(oneLine(fmt.Sprintf(format, a...)))
		b.WriteByte('\n')
	}
	blank := func() { b.WriteString("#\n") }

	w("DRAFT — lm proposal draft. Not an applicable manifest: it stays refused until every required")
	w("field and the states graph are present. lm never applies a draft and never contacted the hub.")
	w("Nothing here says the target below exists or is a lifecycle: that is what apply reports.")
	w("Evidence targets are what the dossier says the hub replayed; they are not compared with the flags below.")
	blank()
	w("From the dossier of %s (catalogue %s), lead %s, rule %s.", d.Created, d.Catalogue, lead.ID, lead.Rule)
	if d.Words != "" {
		w("In the prospect's words: %s", d.Words)
	}
	blank()
	w("Proposal: %s", lead.Title)
	w("  %s", lead.Says)
	w("  Proposes a %s, using %s.", lead.ProposesKind, strings.Join(lead.Uses, ", "))
	w("  Does not say:")
	for _, s := range lead.DoesNotSay {
		w("  - %s", s)
	}
	blank()

	settle, confirmed := split(lead.Conditions)
	if len(settle) > 0 {
		w("Still to settle (the dossier's own checks, those that need you first):")
		for _, c := range settle {
			conditionLines(w, c)
		}
	}
	if len(confirmed) > 0 {
		if len(settle) > 0 {
			blank()
		}
		w("Carried as confirmed:")
		for _, c := range confirmed {
			conditionLines(w, c)
		}
	}

	b.WriteString("apiVersion: apps.lumnik.io/v1\nkind: Workflow\nmetadata:\n")
	setting(&b, 1, "name", t.Name, "yours to choose (a-z, 0-9, -); apply updates the workflow of this name")
	b.WriteString("spec:\n")
	setting(&b, 1, "scope", t.Scope, "yours to choose; the scope that may read this lifecycle")
	b.WriteString("  provenance: declared\n  on:\n")
	setting(&b, 2, "table", t.Table, "the document table, as in connector.t_orders")
	setting(&b, 2, "column", t.Column, "the status column")
	setting(&b, 2, "key", t.Key, "the column that identifies one document (required once a dormancy is declared)")
	if t.After != "" {
		b.WriteString("  dormancy:\n    after: " + yamlString(t.After) + "\n")
	}
	b.WriteString("  # states: and transitions: go here. Exactly one state is initial; a terminal state has no\n")
	b.WriteString("  # outgoing transition. See docs/apps/workflows.md#the-manifest\n")
	return b.String(), skipped, nil
}

func selectLead(d *Dossier) (Lead, []string, error) {
	var found []Lead
	var skipped []string
	for _, l := range d.Leads {
		if l.Rule == ruleID {
			found = append(found, l)
		} else {
			skipped = append(skipped, oneLine(l.Rule)) // dossier text, on its way to a terminal
		}
	}
	switch len(found) {
	case 0:
		return Lead{}, nil, ErrNoLead
	case 1:
		return found[0], skipped, nil
	}
	return Lead{}, nil, ErrLeadRepeated
}

func rank(check string) int {
	switch check {
	case "refuted":
		return 0
	case "missing":
		return 1
	case "unverified":
		return 2
	}
	return 3
}

// split orders conditions refuted, missing, unverified, confirmed (position within each) and cuts
// the confirmed ones off: what still needs the integrator comes first.
func split(in []Condition) (settle, confirmed []Condition) {
	all := append([]Condition(nil), in...)
	sort.SliceStable(all, func(i, j int) bool { return rank(all[i].Check) < rank(all[j].Check) })
	for _, c := range all {
		if c.Check == "confirmed" {
			confirmed = append(confirmed, c)
		} else {
			settle = append(settle, c)
		}
	}
	return settle, confirmed
}

// statement is the whole of lm's wording about a condition (spec §4). It describes what the dossier
// carries; it never says what is true now.
func statement(c Condition) string {
	if c.Verifier == "none" {
		s := "to establish yourself; no check verifies it"
		if c.Check == "missing" {
			s += ". Also missing — not backed by a prospect's yes (unanswered, unknown or not asked)"
		}
		return s
	}
	switch c.Check {
	case "confirmed":
		return "confirmed — the dossier carries a hub confirmation (schema 1 does not date it)"
	case "refuted":
		return "REFUTED — the dossier carries a hub refutation; settle it before applying"
	case "unverified":
		return "unverified — no hub confirmation in this dossier (not checked, or the hub abstained)"
	}
	return "missing — not backed by a prospect's yes (unanswered, unknown or not asked)"
}

func conditionLines(w func(string, ...any), c Condition) {
	w("  %s %s: %s", c.ID, c.Needs, statement(c))
	if c.Note != "" {
		w("     note: %s", c.Note)
	}
	if len(c.Evidence) == 0 {
		return
	}
	if c.Check == "confirmed" || c.Check == "refuted" {
		w("     evidence the dossier carries (not necessarily from this run):")
	} else {
		w("     history carried, not verified by this file:")
	}
	for _, e := range c.Evidence {
		w("       via %s on %s: %s", e.Via, e.Target, e.Observed)
	}
}

// setting writes `key: "value"`, or — when the integrator gave no value — only a comment saying
// where it goes. An absent field is never given a stand-in value.
func setting(b *strings.Builder, depth int, key, value, gap string) {
	pad := strings.Repeat("  ", depth)
	if value == "" {
		b.WriteString(pad + "# " + key + ": " + gap + "\n")
		return
	}
	b.WriteString(pad + key + ": " + yamlString(value) + "\n")
}
