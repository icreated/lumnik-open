package oracle

import (
	"fmt"
	"strings"
)

// Tier is a certification level. The CI gate enforces the declared tier.
type Tier int

const (
	Bronze Tier = iota // 0 CRITICAL
	Silver             // 0 CRITICAL + 0 HIGH
	Gold               // 0 of any severity
)

func (t Tier) String() string {
	switch t {
	case Gold:
		return "GOLD"
	case Silver:
		return "SILVER"
	default:
		return "BRONZE"
	}
}

// threshold is the lowest severity that fails the given tier.
func threshold(t Tier) Severity {
	switch t {
	case Gold:
		return None // "0 of any severity": any finding (>= None) fails Gold
	case Silver:
		return High
	default:
		return Critical
	}
}

// Verdict is the aggregate result of a battery against a target tier.
type Verdict struct {
	Tier     Tier
	Passed   bool
	Findings []Finding
}

// AggregateTier fails iff any finding meets or exceeds the tier's threshold.
func AggregateTier(t Tier, findings []Finding) Verdict {
	limit := threshold(t)
	passed := true
	for _, f := range findings {
		if f.Severity >= limit {
			passed = false
			break
		}
	}
	return Verdict{Tier: t, Passed: passed, Findings: findings}
}

// ParseTier maps a case-insensitive, trimmed tier name to a Tier.
func ParseTier(s string) (Tier, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "bronze":
		return Bronze, nil
	case "silver":
		return Silver, nil
	case "gold":
		return Gold, nil
	default:
		return Bronze, fmt.Errorf("unknown tier %q (want bronze|silver|gold)", s)
	}
}

// HighestPassingTier returns the highest tier that passes for the given
// findings. ok is false when even Bronze fails (a Critical finding is present).
func HighestPassingTier(findings []Finding) (tier Tier, ok bool) {
	for _, t := range []Tier{Gold, Silver, Bronze} {
		if AggregateTier(t, findings).Passed {
			return t, true
		}
	}
	return Bronze, false
}
