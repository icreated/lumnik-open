//go:build otk

package otk

import (
	"os"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/calibration"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// clearEnv unsets every named variable, so a developer's shell cannot make a test
// pass or fail for a reason the test never named.
func clearEnv(t *testing.T, keys ...string) {
	t.Helper()
	for _, k := range keys {
		t.Setenv(k, "") // registers the cleanup
		os.Unsetenv(k)  // and actually unsets for the body
	}
}

// clearModeEnv unsets every variable the resolver reads.
func clearModeEnv(t *testing.T) {
	t.Helper()
	clearEnv(t, "OTK_GOLD_SEEDS", "OTK_GOLD", "OTK_FAULT", "OTK_TIER", "OTK_FAULT_PHASE")
}

// grammarClasses is the documented value classes of layer 1, one entry per arm of
// each variable's rule in the spec. The table below must cover exactly these — a
// rule added to the spec without a case would otherwise pass quietly, which is the
// defect this inventory exists to catch.
var grammarClasses = map[string][]string{
	"OTK_GOLD_SEEDS":  {"present"},
	"OTK_GOLD":        {"off", "on", "refused"},
	"OTK_FAULT":       {"off", "named", "refused"},
	"OTK_TIER":        {"file-default", "override", "refused"},
	"OTK_FAULT_PHASE": {"default", "control", "refused", "orphan"},
}

func TestEnvGrammars(t *testing.T) {
	type row struct {
		variable string
		class    string
		name     string
		set      bool
		value    string
		// with is the companion environment a row needs to be meaningful at all.
		// OTK_FAULT_PHASE only ever modulates a mode OTK_FAULT has already chosen, so
		// its non-refusal rows need a fault named beside them.
		with    map[string]string
		wantErr string // a substring the refusal must carry; "" means it must succeed
	}
	const theFault = "atomicity-underreport-landed"
	withFault := map[string]string{"OTK_FAULT": theFault}
	rows := []row{
		// OTK_GOLD_SEEDS — present at all is a hard error, including exported-but-empty.
		{"OTK_GOLD_SEEDS", "present", "value", true, "125", nil, "OTK_GOLD_SEEDS"},
		{"OTK_GOLD_SEEDS", "present", "empty", true, "", nil, "OTK_GOLD_SEEDS"},

		{"OTK_GOLD", "off", "unset", false, "", nil, ""},
		{"OTK_GOLD", "off", "empty", true, "", nil, ""},
		{"OTK_GOLD", "on", "one", true, "1", nil, ""},
		{"OTK_GOLD", "refused", "zero", true, "0", nil, "accepted value is 1"},
		{"OTK_GOLD", "refused", "true", true, "true", nil, "accepted value is 1"},
		{"OTK_GOLD", "refused", "padded", true, " 1", nil, "accepted value is 1"},

		{"OTK_FAULT", "off", "unset", false, "", nil, ""},
		{"OTK_FAULT", "off", "empty", true, "", nil, ""},
		{"OTK_FAULT", "named", "known", true, theFault, nil, ""},
		{"OTK_FAULT", "refused", "unknown", true, "nope", nil, theFault},
		// The prefix an earlier draft of the plan invented is NOT grammar: OTK_FAULT
		// takes a name in the table and nothing else, and this row keeps it that way.
		{"OTK_FAULT", "refused", "prefixed", true, "control:" + theFault, nil, theFault},

		{"OTK_TIER", "file-default", "unset", false, "", nil, ""},
		{"OTK_TIER", "file-default", "empty", true, "", nil, ""},
		{"OTK_TIER", "override", "silver", true, "silver", nil, ""},
		{"OTK_TIER", "refused", "garbage", true, "platinum", nil, "platinum"},

		{"OTK_FAULT_PHASE", "default", "unset", false, "", withFault, ""},
		{"OTK_FAULT_PHASE", "default", "empty", true, "", withFault, ""},
		{"OTK_FAULT_PHASE", "control", "control", true, "control", withFault, ""},
		{"OTK_FAULT_PHASE", "refused", "garbage", true, "phase-2", withFault, "OTK_FAULT_PHASE"},
		{"OTK_FAULT_PHASE", "refused", "injected-is-not-a-value", true, "injected", withFault, "OTK_FAULT_PHASE"},
		// Accepted-and-ignored is the defect this whole grammar exists to refuse, so a
		// phase with no fault to modulate is a hard error rather than a silent no-op.
		{"OTK_FAULT_PHASE", "orphan", "no fault", true, "control", nil, "OTK_FAULT"},
	}

	// Two-sided inventory: every documented class has a row, and no row invents a
	// class the spec does not document.
	covered := map[string]map[string]bool{}
	for _, r := range rows {
		if covered[r.variable] == nil {
			covered[r.variable] = map[string]bool{}
		}
		covered[r.variable][r.class] = true
	}
	var missing, unexpected []string
	for v, classes := range grammarClasses {
		for _, c := range classes {
			if !covered[v][c] {
				missing = append(missing, v+"/"+c)
			}
		}
	}
	for v, classes := range covered {
		declared := map[string]bool{}
		for _, c := range grammarClasses[v] {
			declared[c] = true
		}
		for c := range classes {
			if !declared[c] {
				unexpected = append(unexpected, v+"/"+c)
			}
		}
	}
	if len(missing) > 0 || len(unexpected) > 0 {
		t.Fatalf("the layer-1 table and its documented classes disagree:\n  missing: %v\n  unexpected: %v",
			missing, unexpected)
	}

	for _, r := range rows {
		t.Run(r.variable+"/"+r.name, func(t *testing.T) {
			clearModeEnv(t)
			for k, v := range r.with {
				t.Setenv(k, v)
			}
			if r.set {
				t.Setenv(r.variable, r.value)
			}
			_, err := resolveMode()
			if r.wantErr == "" {
				if err != nil {
					t.Fatalf("%s=%q (set=%v) must resolve, got: %v", r.variable, r.value, r.set, err)
				}
				return
			}
			if err == nil {
				t.Fatalf("%s=%q must be a hard error", r.variable, r.value)
			}
			if !strings.Contains(err.Error(), r.wantErr) {
				t.Errorf("the refusal must mention %q, got: %v", r.wantErr, err)
			}
		})
	}
}

// Layer 2 is SIX ORDERED rules, first match wins. They are deliberately not
// disjoint — writing them disjointly would mean repeating each earlier rule's
// negation in every later predicate, which is how a table stops being readable — so
// what a test can check is not disjointness but two properties of the resolver:
// totality (nothing falls through) and determinism (the order yields exactly one
// outcome). A disjointness assertion would fail on the very case the order exists
// to settle.
func TestModeRulesResolve(t *testing.T) {
	if len(modeRules) != 6 {
		t.Fatalf("layer 2 has %d rules, the spec documents 6", len(modeRules))
	}

	type want struct {
		rule int
		mode Mode
		err  bool
	}
	// Written independently of the table, from the spec's own rows.
	expected := map[state]want{
		{gold: true, fault: true, tierSet: true}:    {1, 0, true},
		{gold: true, fault: true, tierSet: false}:   {1, 0, true},
		{gold: true, fault: false, tierSet: true}:   {2, 0, true},
		{gold: false, fault: true, tierSet: true}:   {3, 0, true},
		{gold: true, fault: false, tierSet: false}:  {4, ModeCatalogue, false},
		{gold: false, fault: true, tierSet: false}:  {5, ModeCalibration, false},
		{gold: false, fault: false, tierSet: true}:  {6, ModeCorpus, false},
		{gold: false, fault: false, tierSet: false}: {6, ModeCorpus, false},
	}
	if len(expected) != 8 {
		t.Fatalf("the space is 2x2x2 = 8 combinations, the table enumerates %d", len(expected))
	}

	for _, gold := range []bool{false, true} {
		for _, fault := range []bool{false, true} {
			for _, tierSet := range []bool{false, true} {
				s := state{gold: gold, fault: fault, tierSet: tierSet}

				// Totality: at least one rule matches. Nothing falls through.
				matches := 0
				for _, r := range modeRules {
					if r.when(s) {
						matches++
					}
				}
				if matches == 0 {
					t.Errorf("%+v matches no rule — the table is not total", s)
					continue
				}

				// Determinism: the declared order yields exactly one outcome, and it is
				// the one the spec's rows say.
				got, ok := resolveRule(s)
				if !ok {
					t.Errorf("%+v: resolveRule found nothing while %d rule(s) match", s, matches)
					continue
				}
				w, declared := expected[s]
				if !declared {
					t.Errorf("%+v is not in the expectation table", s)
					continue
				}
				if got.n != w.rule {
					t.Errorf("%+v resolved to rule %d, want rule %d", s, got.n, w.rule)
				}
				if (got.err != "") != w.err {
					t.Errorf("%+v: rule %d error=%q, want error=%v", s, got.n, got.err, w.err)
				}
				if !w.err && got.mode != w.mode {
					t.Errorf("%+v resolved to mode %v, want %v", s, got.mode, w.mode)
				}
			}
		}
	}
}

// Overlapping error classes without a declared order is how the message comes to
// depend on which `if` a future reader writes first.
func TestModeConflictPrecedence(t *testing.T) {
	clearModeEnv(t)
	t.Setenv("OTK_GOLD", "1")
	t.Setenv("OTK_FAULT", "atomicity-underreport-landed")
	t.Setenv("OTK_TIER", "silver")

	_, err := resolveMode()
	if err == nil {
		t.Fatal("Gold with a fault and a tier must be a hard error")
	}
	if !strings.Contains(err.Error(), "rule 1") {
		t.Errorf("the mode conflict (rule 1) must be reported ahead of either tier conflict, got: %v", err)
	}
}

// The order of layer 1 is part of the contract, not an accident of which check
// happens to run first: a caller carrying the removed flag is operating under a
// contract that no longer exists, and telling them that is more useful than telling
// them about a second problem they would not have reached anyway.
func TestGrammarPrecedence(t *testing.T) {
	clearModeEnv(t)
	t.Setenv("OTK_GOLD_SEEDS", "125")
	t.Setenv("OTK_GOLD", "garbage")
	t.Setenv("OTK_TIER", "platinum")

	_, err := resolveMode()
	if err == nil {
		t.Fatal("a present OTK_GOLD_SEEDS must be a hard error")
	}
	if !strings.Contains(err.Error(), "OTK_GOLD_SEEDS") {
		t.Errorf("the removed-flag refusal must come first, got: %v", err)
	}
	if !strings.Contains(err.Error(), "OTK_GOLD=1") {
		t.Errorf("the refusal must name the replacement, got: %v", err)
	}
}

// The resolved shape, not only the absence of an error.
func TestResolveModeCarriesTheFaultAndPhase(t *testing.T) {
	for _, c := range []struct {
		name  string
		set   bool
		value string
		phase calibration.Phase
	}{
		{"unset is the injected phase", false, "", calibration.Injected},
		{"control", true, "control", calibration.Control},
	} {
		t.Run(c.name, func(t *testing.T) {
			clearModeEnv(t)
			t.Setenv("OTK_FAULT", "atomicity-underreport-landed")
			if c.set {
				t.Setenv("OTK_FAULT_PHASE", c.value)
			}
			res, err := resolveMode()
			if err != nil {
				t.Fatalf("resolveMode: %v", err)
			}
			if res.Mode != ModeCalibration {
				t.Fatalf("mode = %v, want ModeCalibration", res.Mode)
			}
			if res.Fault.Name != "atomicity-underreport-landed" {
				t.Fatalf("fault = %q", res.Fault.Name)
			}
			if res.Phase != c.phase {
				t.Fatalf("phase = %v, want %v", res.Phase, c.phase)
			}
		})
	}

	clearModeEnv(t)
	t.Setenv("OTK_GOLD", "1")
	res, err := resolveMode()
	if err != nil || res.Mode != ModeCatalogue || res.Tier != oracle.Gold {
		t.Fatalf("OTK_GOLD=1 -> (%+v, %v), want ModeCatalogue at Gold", res, err)
	}
}

func TestResolveTier(t *testing.T) {
	// Env override wins.
	t.Setenv("OTK_TIER", "silver")
	got, err := resolveTier()
	if err != nil || got != oracle.Silver {
		t.Fatalf("env override: got (%v,%v), want (Silver,nil)", got, err)
	}

	// Garbage env value is a hard error.
	t.Setenv("OTK_TIER", "garbage")
	if _, err := resolveTier(); err == nil {
		t.Fatal("garbage OTK_TIER must return an error")
	}

	// Empty env falls back to the committed lm/test/otk/tier file. Compare against
	// the file's own parsed value so this never goes stale when the gate is bumped.
	t.Setenv("OTK_TIER", "")
	got, err = resolveTier()
	if err != nil {
		t.Fatalf("file default returned error: %v", err)
	}
	raw, rerr := os.ReadFile(tierFile)
	if rerr != nil {
		t.Fatalf("read tier file %q: %v", tierFile, rerr)
	}
	want, perr := oracle.ParseTier(string(raw))
	if perr != nil {
		t.Fatalf("committed tier file %q is unparseable: %v", tierFile, perr)
	}
	if got != want {
		t.Fatalf("file default: got %v, want %v (from %s)", got, want, tierFile)
	}
}
