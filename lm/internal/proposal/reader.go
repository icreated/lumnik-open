package proposal

import (
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Reason is one of the contract's refusal codes (spec 2026-10-03, section 4). Which of several
// violations is reported is not part of the contract: every invalid fixture carries exactly one.
type Reason string

const (
	MalformedHeader            Reason = "malformed-header"
	DuplicateKey               Reason = "duplicate-key"
	UnknownSchemaVersion       Reason = "unknown-schema-version"
	UnknownField               Reason = "unknown-field"
	MissingField               Reason = "missing-field"
	NullValue                  Reason = "null-value"
	WrongType                  Reason = "wrong-type"
	BadDate                    Reason = "bad-date"
	AnswerSourceNotProspect    Reason = "answer-source-not-prospect"
	UnknownAnswerKind          Reason = "unknown-answer-kind"
	UnknownAnswerValue         Reason = "unknown-answer-value"
	LabelMismatch              Reason = "label-mismatch"
	LeadSourceNotCatalogue     Reason = "lead-source-not-catalogue"
	DuplicateID                Reason = "duplicate-id"
	OrphanBackedBy             Reason = "orphan-backed-by"
	UnderivableAnswer          Reason = "underivable-answer"
	UnknownCheck               Reason = "unknown-check"
	UnknownVerifier            Reason = "unknown-verifier"
	DerivationMismatch         Reason = "derivation-mismatch"
	OutcomeWithoutEvidence     Reason = "outcome-without-evidence"
	OutcomeOnUnverifiable      Reason = "outcome-on-unverifiable"
	EmptyEvidence              Reason = "empty-evidence"
	EvidenceOnUnverifiable     Reason = "evidence-on-unverifiable"
	EvidenceViaMismatch        Reason = "evidence-via-mismatch"
	EvidenceSourceNotDiscovery Reason = "evidence-source-not-discovery"
)

// Refusal is a dossier the contract refuses, carrying the contract's reason.
type Refusal struct{ Reason Reason }

func (r *Refusal) Error() string { return "dossier refused: " + string(r.Reason) }

func refuse(r Reason) { panic(&Refusal{r}) }

// Dossier is what a draft needs from a validated header. Answers are validated, not kept.
type Dossier struct {
	Catalogue, Created, Words string
	Leads                     []Lead
}

type Lead struct {
	ID, Rule, Title, Says string
	DoesNotSay            []string
	ProposesKind          string
	Uses                  []string
	Conditions            []Condition
}

type Condition struct {
	ID, Needs, Verifier, Check, Note string
	Evidence                         []Evidence
}

type Evidence struct{ Via, Target, Observed string }

// Decode is the file's bytes as text, strictly: a Latin-1 copy must not be read with its accents
// silently replaced, so invalid UTF-8 is a header that cannot be read.
func Decode(b []byte) (string, error) {
	if !utf8.Valid(b) {
		return "", &Refusal{MalformedHeader}
	}
	return string(b), nil
}

// Read is the dossier as a model, or a *Refusal carrying the contract's reason.
func Read(file string) (*Dossier, error) {
	d, _, err := read(file)
	return d, err
}

// read is the one place a refusal is caught: the helpers below abort by panicking with a *Refusal,
// as the hub's reader does by throwing, and nothing else is ever recovered. It also reports
// whether some condition carries evidence (what the corpus calls valid-recheck).
func read(file string) (d *Dossier, recheck bool, err error) {
	defer func() {
		if p := recover(); p != nil {
			r, ok := p.(*Refusal)
			if !ok {
				panic(p)
			}
			d, recheck, err = nil, false, r
		}
	}()
	root := header(file)
	recheck = validate(root)
	return build(root), recheck, nil
}

type typ int

const (
	tText typ = iota
	tInt
	tList
	tObject
)

type field struct {
	name     string
	t        typ
	required bool
}

type shape []field

var (
	rootShape = shape{{"lumnik-dossier", tInt, true}, {"catalogue", tText, true}, {"created", tText, true},
		{"language", tText, true}, {"words", tText, false}, {"answers", tList, true}, {"leads", tList, true}}
	answerShape = shape{{"id", tText, true}, {"kind", tText, true}, {"asked", tText, true},
		{"value", tText, false}, {"label", tText, false}, {"source", tText, true}}
	leadShape = shape{{"id", tText, true}, {"rule", tText, true}, {"source", tText, true}, {"title", tText, true},
		{"says", tText, true}, {"does-not-say", tList, true}, {"proposes", tObject, true}, {"conditions", tList, true}}
	proposesShape  = shape{{"kind", tText, true}, {"uses", tList, true}}
	conditionShape = shape{{"id", tText, true}, {"needs", tText, true}, {"verifier", tText, true}, {"label", tText, true},
		{"backed-by", tText, false}, {"check", tText, true}, {"note", tText, false}, {"evidence", tList, false}}
	evidenceShape = shape{{"source", tText, true}, {"via", tText, true}, {"target", tText, true}, {"observed", tText, true}}
)

var (
	dateRx   = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	intRx    = regexp.MustCompile(`^-?(0|[1-9][0-9]*)$`)
	verifier = map[string]bool{"workflow-data-truth": true, "workflow-apply-door": true, "none": true}
	checks   = map[string]bool{"unverified": true, "missing": true, "confirmed": true, "refuted": true}
	yesNo    = map[string]bool{"yes": true, "no": true, "unknown": true}
)

func header(file string) *node {
	// One leading byte order mark is ignored (editors on Windows write it); a second one is not part of the contract.
	text := strings.ReplaceAll(strings.TrimPrefix(file, "\ufeff"), "\r\n", "\n")
	if !strings.HasPrefix(text, "---\n") {
		refuse(MalformedHeader)
	}
	end := -1
	if i := strings.Index(text[3:], "\n---\n"); i >= 0 {
		end = i + 3
	}
	if end < 0 && strings.HasSuffix(text, "\n---") { // a closing line with no body after it
		end = len(text) - 4
	}
	if end < 0 {
		refuse(MalformedHeader)
	}
	root, err := parseStrict(text[4 : end+1])
	if errors.Is(err, errDuplicateKey) {
		refuse(DuplicateKey)
	}
	if err != nil || root.kind != kObject {
		refuse(MalformedHeader)
	}
	return root
}

// validate returns true when some condition carries evidence the importer must re-verify.
func validate(root *node) bool {
	// The version first: a schema-2 file is expected to carry fields schema 1 does not know.
	v := root.get("lumnik-dossier")
	if v == nil {
		refuse(MissingField)
	}
	if !isInt32(v) {
		refuse(WrongType)
	}
	if v.str != "1" {
		refuse(UnknownSchemaVersion)
	}
	checkShape(root, rootShape)
	checkDate(root.get("created").str)

	answers := map[string]*node{}
	for _, a := range root.get("answers").items {
		checkAnswer(a)
		id := a.get("id").str
		if _, dup := answers[id]; dup {
			refuse(DuplicateID)
		}
		answers[id] = a
	}
	ids := map[string]bool{}
	recheck := false
	for _, lead := range root.get("leads").items {
		checkShape(lead, leadShape)
		checkShape(lead.get("proposes"), proposesShape)
		texts(lead.get("does-not-say"))
		texts(lead.get("proposes").get("uses"))
		if lead.get("source").str != "catalogue" {
			refuse(LeadSourceNotCatalogue)
		}
		if ids[lead.get("id").str] {
			refuse(DuplicateID)
		}
		ids[lead.get("id").str] = true
		for _, c := range lead.get("conditions").items {
			checkShape(c, conditionShape)
			if ids[c.get("id").str] {
				refuse(DuplicateID)
			}
			ids[c.get("id").str] = true
			if checkCondition(c, answers) {
				recheck = true
			}
		}
	}
	return recheck
}

func isInt32(n *node) bool {
	if n.kind != kNumber || !intRx.MatchString(n.str) {
		return false
	}
	_, err := strconv.ParseInt(n.str, 10, 32)
	return err == nil
}

func checkDate(created string) {
	if !dateRx.MatchString(created) {
		refuse(BadDate)
	}
	if _, err := time.Parse("2006-01-02", created); err != nil { // strict: 2026-02-30 is refused
		refuse(BadDate)
	}
}

func checkAnswer(a *node) {
	checkShape(a, answerShape)
	if a.get("source").str != "prospect" {
		refuse(AnswerSourceNotProspect)
	}
	value, label := a.get("value"), a.get("label")
	switch a.get("kind").str {
	case "yes-no-unknown":
		if value != nil && !yesNo[value.str] {
			refuse(UnknownAnswerValue)
		}
		if label != nil {
			refuse(LabelMismatch)
		}
	case "choice":
		if (value != nil) != (label != nil) {
			refuse(LabelMismatch)
		}
	default:
		refuse(UnknownAnswerKind)
	}
}

// checkCondition returns true when the condition carries evidence (kept history or an outcome).
func checkCondition(c *node, answers map[string]*node) bool {
	ver, check := c.get("verifier").str, c.get("check").str
	if !verifier[ver] {
		refuse(UnknownVerifier)
	}
	if !checks[check] {
		refuse(UnknownCheck)
	}
	derived := derive(c, answers)
	evidence := c.get("evidence")
	if evidence != nil {
		// One spelling for "no evidence": the key is absent — [] would be a second one.
		if len(evidence.items) == 0 {
			refuse(EmptyEvidence)
		}
		if ver == "none" {
			refuse(EvidenceOnUnverifiable)
		}
		for _, e := range evidence.items {
			checkShape(e, evidenceShape)
			if e.get("source").str != "discovery" {
				refuse(EvidenceSourceNotDiscovery)
			}
			if e.get("via").str != ver {
				refuse(EvidenceViaMismatch)
			}
		}
	}
	if check == "confirmed" || check == "refuted" {
		if ver == "none" {
			refuse(OutcomeOnUnverifiable)
		}
		if evidence == nil {
			refuse(OutcomeWithoutEvidence)
		}
	} else if check != derived {
		// Evidence beside an unverified/missing check is kept history: allowed, never believed —
		// but the check itself must be exactly what the answers give.
		refuse(DerivationMismatch)
	}
	return evidence != nil
}

// derive is the table of the contract (section 2): a pure function of the backing answer.
func derive(c *node, answers map[string]*node) string {
	backed := c.get("backed-by")
	if backed == nil {
		return "missing"
	}
	a := answers[backed.str]
	if a == nil {
		refuse(OrphanBackedBy)
	}
	if a.get("kind").str != "yes-no-unknown" {
		refuse(UnderivableAnswer)
	}
	value := a.get("value")
	if value == nil || value.str == "unknown" {
		return "missing"
	}
	return "unverified"
}

func checkShape(n *node, s shape) {
	if n == nil || n.kind != kObject {
		refuse(WrongType)
	}
	known := map[string]bool{}
	for _, f := range s {
		known[f.name] = true
	}
	for k := range n.vals {
		if !known[k] {
			refuse(UnknownField)
		}
	}
	for _, f := range s {
		v := n.get(f.name)
		if v == nil {
			if f.required {
				refuse(MissingField)
			}
			continue
		}
		if v.kind == kNull {
			refuse(NullValue)
		}
		ok := false
		switch f.t {
		case tText:
			ok = v.kind == kString
		case tInt:
			ok = isInt32(v)
		case tList:
			ok = v.kind == kArray
		case tObject:
			ok = v.kind == kObject
		}
		if !ok {
			refuse(WrongType)
		}
	}
}

func texts(list *node) {
	for _, item := range list.items {
		if item.kind != kString {
			refuse(WrongType)
		}
	}
}

func build(root *node) *Dossier {
	d := &Dossier{
		Catalogue: root.get("catalogue").str, Created: root.get("created").str,
		Words: opt(root, "words"),
	}
	for _, l := range root.get("leads").items {
		lead := Lead{
			ID: l.get("id").str, Rule: l.get("rule").str, Title: l.get("title").str, Says: l.get("says").str,
			DoesNotSay:   strs(l.get("does-not-say")),
			ProposesKind: l.get("proposes").get("kind").str,
			Uses:         strs(l.get("proposes").get("uses")),
		}
		for _, c := range l.get("conditions").items {
			cond := Condition{
				ID: c.get("id").str, Needs: c.get("needs").str, Verifier: c.get("verifier").str,
				Check: c.get("check").str, Note: opt(c, "note"),
			}
			if ev := c.get("evidence"); ev != nil {
				for _, e := range ev.items {
					cond.Evidence = append(cond.Evidence, Evidence{
						Via: e.get("via").str, Target: e.get("target").str, Observed: e.get("observed").str,
					})
				}
			}
			lead.Conditions = append(lead.Conditions, cond)
		}
		d.Leads = append(d.Leads, lead)
	}
	return d
}

func opt(n *node, key string) string {
	if v := n.get(key); v != nil {
		return v.str
	}
	return ""
}

func strs(list *node) []string {
	out := []string{}
	for _, item := range list.items {
		out = append(out, item.str)
	}
	return out
}
