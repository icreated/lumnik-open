// Package calibration declares the faults OTK injects on purpose, and judges
// whether the battery noticed.
//
// A green OTK run means no check fired. It does not mean the checks CAN fire.
// Those are different statements and only one of them is worth a badge. This
// package is the second one.
//
// It carries no build tag: every declaration, pattern and verdict here is hub-free
// and must be reachable from `make otk-unit` and from the untagged `go test ./...`.
package calibration

import (
	"fmt"
	"regexp"
	"sort"
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// FaultsDir is where the artefacts live, relative to this package.
const FaultsDir = "faults"

// Kind is the shape a fault's artefact takes.
//
// A patch is applied to the working tree, exercised, reverted. A SQL statement is
// applied to the running hub's database with its own `-- undo:` counterpart, for
// invariants the database enforces rather than the code. They share the same
// declaration table, the same inverted gate and the same obligation to restore;
// they differ only in cost, and the difference is large — a SQL fault needs no
// repackaged hub.
type Kind string

const (
	KindPatch Kind = "patch"
	KindSQL   Kind = "sql"
)

// Ext is the file extension an artefact of this kind carries.
func (k Kind) Ext() string { return "." + string(k) }

// Capability is something that must be ALIVE for a fault's expectation to be
// observable at all.
//
// The family is the wrong axis for this. Atomicity is raised both by
// ReconcileCounts, which reads only the fields `lm run get` returns and needs no
// database, and by ReconcileLanded, which cannot fire without DB truth. "This fault
// expects Atomicity" therefore says nothing about what must be alive. Dormancy is a
// property of a capability, not of a family.
type Capability string

const (
	RequiresDBTruth  Capability = "RequiresDBTruth"
	RequiresRealAuth Capability = "RequiresRealAuth"
	RequiresAdminDB  Capability = "RequiresAdminDB"
)

// Capabilities is the closed set. An inventory test pins that every constant here
// has a probe and every probe is reachable from some constant: a capability with no
// probe would refuse nothing while looking like a guard.
func Capabilities() []Capability {
	return []Capability{RequiresDBTruth, RequiresRealAuth, RequiresAdminDB}
}

// RebuildTarget is what phase 2 must rebuild before running the injected battery,
// for a fault whose Kind needs a rebuild at all.
//
// It replaced a single NeedsRepackage bool the day a fault needed to rebuild the
// `lm` CLI instead of the hub: "does anything need rebuilding" was never the
// question otkcal's three-phase flow actually asks — WHICH artefact does is, and
// only RebuildHub means the hub itself must be stopped and rebooted. RebuildCLI
// leaves the hub running untouched; the next `lm` invocation just picks up the
// freshly-built binary from disk, the same way a KindSQL fault leaves the hub
// running untouched while the database changes underneath it.
type RebuildTarget string

const (
	RebuildNone RebuildTarget = "" // lives entirely in the database — nothing to build
	RebuildHub  RebuildTarget = "hub"
	RebuildCLI  RebuildTarget = "cli"
)

// Fault is one declared way to break the hub on purpose, and what the harness must
// then say about it.
//
// There is no Artefact field: the artefact is Name+Kind.Ext(), derived. A field that
// can only ever equal a derivation is a second copy to keep in sync plus a test to
// police the agreement — the lesson Witness.Lane already taught this harness.
type Fault struct {
	Name        string
	Kind        Kind
	Family      oracle.Family
	MinSeverity oracle.Severity

	// Pattern is a Go regexp matched UNANCHORED against Finding.Detail. Unanchored
	// because a detail carries live numbers ("reported 3 row(s) … the DB holds 4")
	// and anchoring would make every declaration a transcription exercise.
	Pattern string

	// Sample is a realistic detail this fault should produce. The inventory requires
	// the pattern to match it and to match no decoy, which refuses a catch-all by
	// construction.
	Sample string

	Capabilities []Capability
	Rebuild      RebuildTarget

	// re is Pattern, compiled when the table is loaded — not at match time, so an
	// invalid expression is a hard error before any hub is packaged rather than a
	// surprise forty seconds into a battery.
	re *regexp.Regexp
}

// faultTable is the declaration. One entry per fault, and the artefact beside it in
// faults/ must agree — asserted in both directions by TestFaultInventory.
var faultTable = []Fault{
	{
		Name:        "atomicity-underreport-landed",
		Kind:        KindPatch,
		Family:      oracle.Atomicity,
		MinSeverity: oracle.High,
		// checks.ReconcileLanded's own format, with its live numbers left open.
		Pattern:      `hub reported \d+ row\(s\) written but the DB holds \d+`,
		Sample:       "hub reported 0 row(s) written but the DB holds 1",
		Capabilities: []Capability{RequiresDBTruth},
		Rebuild:      RebuildHub,
	},
	{
		Name:        "auth-audience-not-verified",
		Kind:        KindPatch,
		Family:      oracle.Auth,
		MinSeverity: oracle.Critical,
		// checks.AuthOutcome's own format for a served rejection, with the defect name
		// (the generator's "bad-audience" case) pinned so this pattern cannot also match
		// a sibling defect's bypass (e.g. expired-token — see decoys).
		Pattern:      `auth bypass landed \(bad-audience\): expected 401, hub returned \d+`,
		Sample:       "auth bypass landed (bad-audience): expected 401, hub returned 200",
		Capabilities: []Capability{RequiresRealAuth},
		Rebuild:      RebuildHub,
	},
	{
		Name:        "security-rls-isolation-dropped",
		Kind:        KindSQL,
		Family:      oracle.Security,
		MinSeverity: oracle.Critical,
		// checks.RLSIsolation's own format, with its live count left open.
		Pattern: `RLS isolation crossed: \d+ foreign-tenant row\(s\) visible under a different tenant's DB session`,
		Sample:  "RLS isolation crossed: 1 foreign-tenant row(s) visible under a different tenant's DB session",
		// RequiresAdminDB applies the ALTER POLICY (DDL the app role cannot do);
		// RequiresDBTruth observes it (the same OTK_DB_DSN app-role session
		// checks.RLSIsolation's probe already runs through). Neither is new: both
		// were already probed for other faults.
		Capabilities: []Capability{RequiresAdminDB, RequiresDBTruth},
		// The fault lives entirely in the database — no source changes, so no
		// rebuild/reboot between phases.
		Rebuild: RebuildNone,
	},
	{
		Name:        "robustness-cli-manifest-label-panic",
		Kind:        KindPatch,
		Family:      oracle.Robustness,
		MinSeverity: oracle.High,
		// checks.Robustness's own Detail for a panic is a FIXED literal — it carries
		// no live data from the actual panic message, only runner.Result.Panicked's
		// bool (itself set by matching "panic:"/"goroutine "/"runtime error:" against
		// combined stdout+stderr, a layer below what reaches the Finding). The exact
		// string is what distinguishes this from the same family's HANG and
		// leaked-stack findings, which carry their own distinct fixed Details.
		Pattern:      `lm panicked / unhandled stack trace`,
		Sample:       "lm panicked / unhandled stack trace",
		Capabilities: nil, // a CLI-only fault: no DB, no auth, no admin role — it runs regardless
		// The fault lives in lm's own Go source, not the hub: no Java rebuild, but the
		// CLI binary itself must be rebuilt before phase 2's battery can exercise it.
		Rebuild: RebuildCLI,
	},
	{
		Name:        "security-terminal-run-hidden-from-api",
		Kind:        KindPatch,
		Family:      oracle.Security,
		MinSeverity: oracle.Critical,
		// checks.RunTerminalDivergence's own fixed-literal Detail.
		//
		// Named for the SYMPTOM, not a mechanism: the July 2026 otk-gold flakiness diary
		// (docs/studies/2026-07-27-otk-gold-flakiness-carnet.md) established that a run can go
		// terminal in DB truth before the API ever reports it, but never established WHY — the
		// diary's own conclusion was that the case needed a live recurrence to diagnose further,
		// and none came. This fault is a SYNTHETIC witness of that observed failure SHAPE, not a
		// reproduction of its root cause. If a future recurrence identifies the real mechanism,
		// the artefact can be replaced without touching this declaration or checks.RunTerminalDivergence.
		Pattern:      `run terminal in DB truth but the API never reported it within the poll deadline`,
		Sample:       "run terminal in DB truth but the API never reported it within the poll deadline",
		Capabilities: []Capability{RequiresDBTruth},
		Rebuild:      RebuildHub,
	},
}

// ArtefactName is the file this fault owns in faults/.
func (f Fault) ArtefactName() string { return f.Name + f.Kind.Ext() }

// Matches reports whether one finding is the one this fault declared: the family,
// at or above the severity, with a detail the pattern matches. All three, never any
// two of them — "red" is not the bar, the RIGHT red is.
func (f Fault) Matches(g oracle.Finding) bool {
	return f.re != nil && g.Family == f.Family && g.Severity >= f.MinSeverity && f.re.MatchString(g.Detail)
}

// compileAll returns the table with every pattern compiled, or the first invalid
// declaration as an error naming the fault.
//
// It takes its input rather than reading the package table so an invalid
// declaration can be DRIVEN by a test. A regexp.MustCompile in a var block would
// panic at package init, where no test can watch it fail.
func compileAll(in []Fault) ([]Fault, error) {
	out := make([]Fault, 0, len(in))
	for _, f := range in {
		if f.Pattern == "" {
			return nil, fmt.Errorf("fault %q declares an empty detail pattern, which matches every detail: "+
				"give it the wording the check actually emits", f.Name)
		}
		re, err := regexp.Compile(f.Pattern)
		if err != nil {
			return nil, fmt.Errorf("fault %q declares an invalid detail pattern %q: %w", f.Name, f.Pattern, err)
		}
		f.re = re
		out = append(out, f)
	}
	return out, nil
}

// Faults returns the declared faults, patterns compiled.
func Faults() ([]Fault, error) { return compileAll(faultTable) }

// ByName resolves one fault, listing the known names when it cannot.
func ByName(name string) (Fault, error) {
	faults, err := Faults()
	if err != nil {
		return Fault{}, err
	}
	known := make([]string, 0, len(faults))
	for _, f := range faults {
		if f.Name == name {
			return f, nil
		}
		known = append(known, f.Name)
	}
	return Fault{}, fmt.Errorf("unknown fault %q; the declared faults are: %s", name, strings.Join(known, ", "))
}

// artefactIssues compares the declared table against the artefact file names found
// in faults/, and returns every disagreement — never two counts that happen to
// agree.
//
// It takes the file list rather than reading the directory so the wrong-kind and
// both-kinds refusals can be driven with synthetic listings. A fault declaring
// `patch` whose only file is a `.sql` surfaces as two true statements — the expected
// artefact is missing, and an undeclared one is present — which reads as wrong-kind
// and needs no third message to say so.
func artefactIssues(faults []Fault, files []string) []string {
	onDisk := make(map[string]bool, len(files))
	for _, n := range files {
		onDisk[n] = true
	}

	var issues []string
	declared := make(map[string]bool, len(faults))
	for _, f := range faults {
		want := f.ArtefactName()
		declared[want] = true
		if !onDisk[want] {
			issues = append(issues, fmt.Sprintf("missing artefact for fault %q: expected %s", f.Name, want))
		}
		// Two shapes are a convenience; two shapes for one fault is an ambiguity, and
		// a resolution order to remember is exactly what this harness has already been
		// bitten by once.
		for _, k := range []Kind{KindPatch, KindSQL} {
			if k == f.Kind {
				continue
			}
			if other := f.Name + k.Ext(); onDisk[other] {
				issues = append(issues, fmt.Sprintf(
					"fault %q declares kind %s but %s is also on disk: a fault owns exactly one artefact",
					f.Name, f.Kind, other))
			}
		}
	}
	for _, n := range files {
		if !declared[n] {
			issues = append(issues, fmt.Sprintf("undeclared artefact on disk: %s", n))
		}
	}
	sort.Strings(issues)
	return issues
}
