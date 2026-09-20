//go:build otk

package otk

import (
	"fmt"
	"os"

	"github.com/icreated/lumnik-open/lm/test/otk/calibration"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// Mode is what a resolved environment asks the battery to do.
type Mode int

const (
	ModeCorpus      Mode = iota // the committed corpus, at the resolved tier
	ModeCatalogue               // every catalogue case, Gold gate
	ModeCalibration             // every catalogue case, INVERTED gate
)

func (m Mode) String() string {
	switch m {
	case ModeCatalogue:
		return "catalogue"
	case ModeCalibration:
		return "calibration"
	default:
		return "corpus"
	}
}

// Resolution is the fully arbitrated environment.
type Resolution struct {
	Mode  Mode
	Tier  oracle.Tier       // the gate's tier; Gold in both catalogue and calibration
	Fault calibration.Fault // meaningful only in ModeCalibration
	Phase calibration.Phase // meaningful only in ModeCalibration
}

// resolveMode arbitrates the five environment variables in two declared layers.
//
// Three modes and five variables is exactly the shape that grows an undocumented
// resolution order, so the rules are declared rather than left to whoever reads the
// parser first. A single exhaustive table would be well over a hundred rows, which
// nobody reads and therefore nobody maintains; two layers are each small enough to
// enumerate, and each gets its own test.
//
// LAYER 1 is each variable's own grammar, judged in THIS order: OTK_GOLD_SEEDS,
// OTK_GOLD, OTK_FAULT, OTK_TIER, OTK_FAULT_PHASE. The order is declared because the
// message depends on it: a caller carrying OTK_GOLD_SEEDS is operating under a
// contract that no longer exists, and saying so beats telling them about a mode
// conflict they would not have reached anyway. OTK_FAULT_PHASE is judged last
// because it is the only one whose rule reads another variable.
func resolveMode() (Resolution, error) {
	if err := seedsRemoved(); err != nil {
		return Resolution{}, err
	}
	gold, err := goldFromEnv()
	if err != nil {
		return Resolution{}, err
	}
	fault, haveFault, err := faultFromEnv()
	if err != nil {
		return Resolution{}, err
	}
	tier, tierSet, err := tierFromEnv()
	if err != nil {
		return Resolution{}, fmt.Errorf("OTK tier misconfigured: %w", err)
	}
	phase, err := phaseFromEnv(haveFault)
	if err != nil {
		return Resolution{}, err
	}

	r, ok := resolveRule(state{gold: gold, fault: haveFault, tierSet: tierSet})
	if !ok {
		// Unreachable while rule 6 matches everything; kept because "unreachable" is a
		// claim a future edit can quietly falsify, and TestModeRulesResolve is what
		// keeps it true.
		return Resolution{}, fmt.Errorf(
			"OTK mode rules matched nothing for gold=%v fault=%v tier-set=%v", gold, haveFault, tierSet)
	}
	if r.err != "" {
		return Resolution{}, fmt.Errorf("OTK mode rule %d: %s", r.n, r.err)
	}

	switch r.mode {
	case ModeCatalogue:
		return Resolution{Mode: ModeCatalogue, Tier: oracle.Gold}, nil
	case ModeCalibration:
		return Resolution{Mode: ModeCalibration, Tier: oracle.Gold, Fault: fault, Phase: phase}, nil
	default:
		return Resolution{Mode: ModeCorpus, Tier: tier}, nil
	}
}

// seedsRemoved refuses a residual OTK_GOLD_SEEDS.
//
// It is refused rather than ignored: a flag accepted and then quietly given a new
// meaning is a defect this project has already paid for. A residual variable is a
// hard error even when it is set to the empty string — an exported-but-empty
// OTK_GOLD_SEEDS is still residual, and only an actually UNSET variable is a clean
// environment.
func seedsRemoved() error {
	v, ok := os.LookupEnv("OTK_GOLD_SEEDS")
	if !ok {
		return nil
	}
	return fmt.Errorf(
		"OTK_GOLD_SEEDS=%q is removed: the Gold gate is a coverage of the whole "+
			"catalogue, not a volume of seeds. Unset it and set OTK_GOLD=1", v)
}

// goldFromEnv reads OTK_GOLD's grammar: unset/empty -> off, "1" -> on, anything
// else -> hard error.
//
// "0" is refused rather than read as "off" on purpose: whoever wrote OTK_GOLD=0 was
// thinking about Gold, and the difference between "off" and "misspelled on" is not
// one to guess at in a certification gate.
func goldFromEnv() (bool, error) {
	v, ok := os.LookupEnv("OTK_GOLD")
	if !ok || v == "" {
		return false, nil
	}
	if v == "1" {
		return true, nil
	}
	return false, fmt.Errorf(
		"OTK_GOLD=%q is invalid: the only accepted value is 1 (unset or empty runs "+
			"the committed-corpus path)", v)
}

// faultFromEnv reads OTK_FAULT's grammar:
//
//	unset or empty -> off
//	<name>         -> that fault
//	anything else  -> hard error listing the known faults
//
// A name in the table and nothing else. The phase is NOT spelled here: OTK_FAULT is
// what an operator types, and folding a phase prefix into it would make every reader
// of this variable learn about a distinction otkcal sets for them.
func faultFromEnv() (calibration.Fault, bool, error) {
	v, ok := os.LookupEnv("OTK_FAULT")
	if !ok || v == "" {
		return calibration.Fault{}, false, nil
	}
	f, err := calibration.ByName(v)
	if err != nil {
		return calibration.Fault{}, false, fmt.Errorf("OTK_FAULT=%q is invalid: %w", v, err)
	}
	return f, true, nil
}

// phaseFromEnv reads OTK_FAULT_PHASE's grammar:
//
//	unset or empty          -> the INJECTED phase
//	"control"               -> the CONTROL phase
//	anything else           -> hard error
//	present with no fault   -> hard error
//
// It is a runner-internal variable: otkcal drives both phases and sets it itself.
// It selects no mode — it can only modulate one OTK_FAULT has already chosen — which
// is why layer 2 below knows nothing about it, and why setting it without a fault is
// refused rather than ignored. A flag accepted and then silently ignored is the
// defect this whole grammar exists to reject.
func phaseFromEnv(haveFault bool) (calibration.Phase, error) {
	v, ok := os.LookupEnv("OTK_FAULT_PHASE")
	set := ok && v != ""
	if set && !haveFault {
		return calibration.Injected, fmt.Errorf(
			"OTK_FAULT_PHASE=%q was set with no OTK_FAULT to modulate: the phase only means "+
				"something inside a calibration, so it is refused rather than ignored. "+
				"Set OTK_FAULT, or unset OTK_FAULT_PHASE", v)
	}
	if !set {
		return calibration.Injected, nil
	}
	if v == calibration.ControlValue {
		return calibration.Control, nil
	}
	return calibration.Injected, fmt.Errorf(
		"OTK_FAULT_PHASE=%q is invalid: the only accepted value is %q (unset runs the injected phase). "+
			"otkcal sets this itself; it is rarely one to set by hand",
		v, calibration.ControlValue)
}

// tierFromEnv reads OTK_TIER's grammar and reports whether it was SET, which is
// what layer 2 arbitrates on. A set-but-invalid value is a hard error; unset or
// empty falls back to the committed tier file.
//
// The file is now parsed in every mode, not only the corpus one. That is what the
// declared grammar says, and a corrupt committed tier file should fail everything
// rather than only the path that happens to read it.
func tierFromEnv() (oracle.Tier, bool, error) {
	v, ok := os.LookupEnv("OTK_TIER")
	t, err := resolveTier()
	return t, ok && v != "", err
}

const tierFile = "tier"

// resolveTier returns the tier the gate must enforce: OTK_TIER overrides the
// committed lm/test/otk/tier file. Any read/parse problem is returned as an error
// so the caller can fail the test hard.
func resolveTier() (oracle.Tier, error) {
	if v := os.Getenv("OTK_TIER"); v != "" {
		return oracle.ParseTier(v)
	}
	b, err := os.ReadFile(tierFile)
	if err != nil {
		return oracle.Bronze, fmt.Errorf("read %s: %w", tierFile, err)
	}
	return oracle.ParseTier(string(b))
}

// state is the post-layer-1 triple: each variable reduced to its meaningful states.
// Two each, so the space is 2x2x2 = eight combinations — small enough to enumerate
// exhaustively rather than argue about.
type state struct {
	gold    bool
	fault   bool
	tierSet bool
}

// rule is one row of layer 2. A non-empty err makes the row a refusal.
type rule struct {
	n    int
	when func(state) bool
	mode Mode
	err  string
}

// modeRules is layer 2: SIX ORDERED rules, evaluated top to bottom, FIRST MATCH
// WINS. They are deliberately NOT disjoint — writing them disjointly would mean
// repeating each earlier rule's negation in every later predicate, which is how a
// table stops being readable — and the order is what resolves the overlap.
//
// OTK_GOLD=1 with a fault AND a tier matches rules 1, 2 and 3 at once; the order is
// what stops the message from depending on which `if` a future reader writes first.
// The mode conflict is reported ahead of either tier conflict because it is the more
// fundamental contradiction: the caller asked for two incompatible modes, and which
// tier they meant cannot be answered until that is resolved.
//
// What must hold of these as a whole is therefore not disjointness but totality and
// determinism — see TestModeRulesResolve, which enumerates all eight combinations.
var modeRules = []rule{
	{1, func(s state) bool { return s.gold && s.fault }, 0,
		"OTK_GOLD=1 and OTK_FAULT are mutually exclusive modes. Calibration already runs the whole " +
			"catalogue, and the Gold gate and the inverted gate disagree about what a finding means; " +
			"accepting both would leave which gate applies to be inferred. Unset one of them"},
	{2, func(s state) bool { return s.gold && s.tierSet }, 0,
		"OTK_GOLD=1 forces the Gold tier, so OTK_TIER would be accepted and then silently ignored. " +
			"A flag accepted and then ignored is the defect this grammar exists to refuse. Unset OTK_TIER"},
	{3, func(s state) bool { return s.fault && s.tierSet }, 0,
		"OTK_TIER decides nothing under OTK_FAULT: the inverted gate replaces the tier gate entirely, " +
			"so the tier would be accepted and then silently ignored. Unset OTK_TIER"},
	{4, func(s state) bool { return s.gold }, ModeCatalogue, ""},
	{5, func(s state) bool { return s.fault }, ModeCalibration, ""},
	{6, func(s state) bool { return true }, ModeCorpus, ""},
}

// resolveRule returns the FIRST rule matching s. Totality is a property the table
// has by rule 6 and that TestModeRulesResolve proves rather than assumes, which is
// why the second return value exists at all.
func resolveRule(s state) (rule, bool) {
	for _, r := range modeRules {
		if r.when(s) {
			return r, true
		}
	}
	return rule{}, false
}
