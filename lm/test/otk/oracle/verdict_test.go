package oracle

import "testing"

func TestBronze_PassesWithNoCritical(t *testing.T) {
	v := AggregateTier(Bronze, []Finding{
		{Severity: High, Family: Robustness},
		{Severity: Low, Family: Text},
	})
	if !v.Passed {
		t.Errorf("Bronze must pass with no CRITICAL, got fail: %+v", v)
	}
}

func TestBronze_FailsOnCritical(t *testing.T) {
	v := AggregateTier(Bronze, []Finding{
		{Severity: Critical, Family: Security, Detail: "rls crossed"},
	})
	if v.Passed {
		t.Errorf("Bronze must fail on CRITICAL, got pass")
	}
	if v.Tier != Bronze {
		t.Errorf("tier got %v want Bronze", v.Tier)
	}
}

func TestSilver_FailsOnHigh(t *testing.T) {
	v := AggregateTier(Silver, []Finding{{Severity: High, Family: Atomicity}})
	if v.Passed {
		t.Errorf("Silver must fail on HIGH")
	}
}

// Gold means "0 of any severity": ANY finding fails it, even a None-severity one.
func TestGold_FailsOnAnyFinding(t *testing.T) {
	v := AggregateTier(Gold, []Finding{{Severity: None, Family: Text}})
	if v.Passed {
		t.Errorf("Gold must fail on any finding, including None severity")
	}
}

func TestGold_PassesOnNoFindings(t *testing.T) {
	v := AggregateTier(Gold, nil)
	if !v.Passed {
		t.Errorf("Gold must pass when there are no findings at all")
	}
}

func TestParseTier_validValues(t *testing.T) {
	cases := map[string]Tier{
		"bronze":   Bronze,
		"silver":   Silver,
		"gold":     Gold,
		"  GOLD  ": Gold,
		"Silver":   Silver,
	}
	for in, want := range cases {
		got, err := ParseTier(in)
		if err != nil {
			t.Errorf("ParseTier(%q) returned error: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("ParseTier(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestParseTier_invalidValueErrors(t *testing.T) {
	for _, in := range []string{"platinum", "", "  "} {
		if _, err := ParseTier(in); err == nil {
			t.Errorf("ParseTier(%q) must return an error", in)
		}
	}
}

func TestHighestPassingTier_noFindings_isGold(t *testing.T) {
	got, ok := HighestPassingTier(nil)
	if !ok || got != Gold {
		t.Fatalf("HighestPassingTier(nil) = (%v,%v), want (Gold,true)", got, ok)
	}
}

func TestHighestPassingTier_lowOrMedium_isSilver(t *testing.T) {
	for _, sev := range []Severity{Low, Medium} {
		got, ok := HighestPassingTier([]Finding{{Severity: sev, Family: Text}})
		if !ok || got != Silver {
			t.Errorf("HighestPassingTier(%v) = (%v,%v), want (Silver,true)", sev, got, ok)
		}
	}
}

func TestHighestPassingTier_high_isBronze(t *testing.T) {
	got, ok := HighestPassingTier([]Finding{{Severity: High, Family: Atomicity}})
	if !ok || got != Bronze {
		t.Fatalf("HighestPassingTier(High) = (%v,%v), want (Bronze,true)", got, ok)
	}
}

func TestHighestPassingTier_critical_noneOk(t *testing.T) {
	_, ok := HighestPassingTier([]Finding{{Severity: Critical, Family: Security}})
	if ok {
		t.Fatal("HighestPassingTier(Critical) ok must be false — even Bronze fails")
	}
}
