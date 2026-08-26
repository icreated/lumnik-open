//go:build otk

package otk

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestOTK(t *testing.T) {
	goldM, gerr := goldSeedsFromEnv()
	if gerr != nil {
		// A present-but-invalid OTK_GOLD_SEEDS must fail, not silently fall back to
		// the Silver corpus path (which would be a false-green "gold" run).
		t.Fatalf("OTK gold mode misconfigured: %v", gerr)
	}
	gold := goldM > 0

	if os.Getenv("LUMNIK_URL") == "" {
		// Gold mode must never pass by skipping — that would make the badge a lie.
		// A missing hub in gold mode is a hard failure, not a skip.
		if gold {
			t.Fatalf("OTK gold mode requires LUMNIK_URL (a live real-auth hub); " +
				"set it or unset OTK_GOLD_SEEDS")
		}
		t.Skip("set LUMNIK_URL to run OTK (hub must use the non-owner app DB role)")
	}
	if gold {
		// Gold must exercise every lane through real-auth + DB truth with the SHARED
		// signing key (not an ephemeral one), or it is not Gold. Enforce the full
		// precondition up front — before any key minting or scenario runs — so an
		// incomplete env fails fast with a clear message instead of late and noisy.
		var missing []string
		if os.Getenv("OTK_DB_DSN") == "" {
			missing = append(missing, "OTK_DB_DSN (run-lane DB truth)")
		}
		if os.Getenv("OTK_OIDC_ISSUER") == "" {
			missing = append(missing, "OTK_OIDC_ISSUER (real-auth)")
		}
		if os.Getenv("OTK_KEY_FILE") == "" {
			missing = append(missing, "OTK_KEY_FILE (shared signing key; an ephemeral key would not match the hub)")
		}
		if len(missing) > 0 {
			t.Fatalf("OTK gold mode requires %s; set them or unset OTK_GOLD_SEEDS",
				strings.Join(missing, ", "))
		}
	}
	// Make the run hermetic: lm resolves a context from ~/.lm/config.yaml and
	// errors without a current-context even when LUMNIK_URL is set.
	writeHermeticConfig(t)
	allFindings = nil

	// Real-auth battery: when the hub trusts our key (OTK_OIDC_ISSUER set by the
	// harness/CI), mint a valid integrator token for the functional lanes. The key
	// is loaded from the shared private-key file (OTK_KEY_FILE) so the harness signs
	// with the exact key the hub verifies against; absent that file a fresh,
	// non-matching key is generated and a warning is logged (see below).
	if iss := os.Getenv("OTK_OIDC_ISSUER"); iss != "" {
		aud := envOr("OTK_OIDC_AUDIENCE", "lumnik-backend")
		var (
			k   *auth.ControlledKey
			err error
		)
		if kf := os.Getenv("OTK_KEY_FILE"); kf != "" {
			b, rerr := os.ReadFile(kf)
			if rerr != nil {
				t.Fatalf("read OTK_KEY_FILE: %v", rerr)
			}
			k, err = auth.NewControlledKeyFromPEM(b, iss, aud)
		} else {
			// The harness always drives an EXTERNAL hub, so a fresh key here will NOT
			// match a hub booted from `otkkey` output → every lane mass-fails. Warn
			// loudly; this branch is only for constructing a key in isolation.
			t.Logf("OTK WARNING: OTK_OIDC_ISSUER set but OTK_KEY_FILE unset — generated an " +
				"ephemeral key that will NOT match a hub booted from otkkey output; set " +
				"OTK_KEY_FILE to the shared private key for a real-auth battery")
			k, err = auth.NewControlledKey(iss, aud)
		}
		if err != nil {
			t.Fatalf("controlled key: %v", err)
		}
		otkKey = k
	}

	var tier oracle.Tier
	if gold {
		tier = oracle.Gold // gold preconditions already enforced above
	} else {
		var err error
		tier, err = resolveTier()
		if err != nil {
			t.Fatalf("OTK tier misconfigured: %v", err)
		}
	}

	// Surface the hub-side intactness state once, up front. A precondition error
	// here (psql present but failing) fails hard; pure dormancy only logs.
	if _, live, err := hubSnapshot(os.Getenv("OTK_DB_DSN")); err != nil {
		t.Fatalf("hub-side snapshot precondition failed at startup: %v", err)
	} else if !live {
		t.Logf("OTK hub-side intactness DORMANT: set OTK_DB_DSN (and have psql on PATH) " +
			"to enable the schema-diff + API/DB-divergence security families")
	}

	// The entity read variants target a pre-seeded otk_customer entity; the harness
	// seeds it itself (dormant without OTK_DB_DSN, like the variants).
	ensureEntityReadSeed(t)

	opts := &godog.Options{Format: "pretty", Paths: []string{"corpus"}}
	if gold {
		// Validate the scenario count (overflow-guarded) BEFORE building the slice,
		// so a misconfigured OTK_GOLD_SEEDS fails with a clear message instead of a
		// huge/overflowing allocation.
		want, cerr := expectedGoldScenarios(goldM)
		if cerr != nil {
			t.Fatalf("OTK gold: %v", cerr)
		}
		if !enoughForGold(want) {
			t.Fatalf("OTK gold: %d scenarios < %d floor — raise OTK_GOLD_SEEDS",
				want, goldMinScenarios)
		}
		feats := goldFeatures(goldM)
		opts = &godog.Options{Format: "progress", FeatureContents: feats}
		t.Logf("OTK gold mode: %d scenarios (%d seeds x %d lanes), tier=GOLD", want, goldM, goldLanes)
	}
	suite := godog.TestSuite{
		ScenarioInitializer: InitializeScenario,
		Options:             opts,
	}
	// The status is collected, NOT acted on yet: a hard step error must never return before the
	// verdict is computed, or it discards every graded finding the battery just produced. See
	// gateFailures — this ordering is the whole point.
	status := suite.Run()

	v := oracle.AggregateTier(tier, allFindings)
	t.Logf("OTK verdict: tier=%s passed=%v findings=%d", v.Tier, v.Passed, len(v.Findings))
	for _, f := range v.Findings {
		t.Logf("  %s", f)
	}

	switch best, ok := oracle.HighestPassingTier(allFindings); {
	case ok && best > tier:
		t.Logf("OTK: tier %s achievable, declared %s — consider bumping lm/test/otk/tier", best, tier)
	case ok:
		t.Logf("OTK: highest achievable tier = %s (declared %s)", best, tier)
	default:
		t.Logf("OTK: no tier passes — Bronze unmet")
	}

	for _, reason := range gateFailures(v, status) {
		t.Error(reason)
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

const tierFile = "tier"

// resolveTier returns the tier the gate must enforce: OTK_TIER overrides the
// committed lm/test/otk/tier file. Any read/parse problem is returned as an
// error so the caller can fail the test hard.
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

func TestRlsScoped_prefixesTenantSet(t *testing.T) {
	got := rlsScoped("SELECT 1")
	want := "SET app.current_tenant = '1'; SELECT 1"
	if got != want {
		t.Fatalf("rlsScoped = %q, want %q", got, want)
	}
}

func TestHermeticConfig_DeclaresTenant(t *testing.T) {
	writeHermeticConfig(t)
	b, err := os.ReadFile(filepath.Join(os.Getenv("HOME"), ".lm", "config.yaml"))
	if err != nil {
		t.Fatalf("read hermetic config: %v", err)
	}
	cfg := string(b)
	if !strings.Contains(cfg, `tenant: "1"`) {
		t.Fatalf("hermetic config must declare tenant \"1\" so lm sends X-Tenant-ID:\n%s", cfg)
	}
	if !strings.Contains(cfg, `user: "1"`) {
		t.Fatalf("hermetic config must declare user \"1\" so lm sends X-User-ID:\n%s", cfg)
	}
}

// writeHermeticConfig points HOME at a temp dir containing a minimal lm config
// with a current-context, so context resolution succeeds without any ambient
// ~/.lm/config.yaml. Server/token are placeholders (LUMNIK_URL / LUMNIK_TOKEN
// override them at resolve time); tenant/user are fixed at "1" so lm sends
// X-Tenant-ID / X-User-ID, giving the dev-bypass hub a tenant context for
// RLS-scoped writes.
func writeHermeticConfig(t *testing.T) {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	dir := filepath.Join(home, ".lm")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	// tenant/user make lm send X-Tenant-ID / X-User-ID under the hub's dev-bypass,
	// so RLS-scoped writes (connector apply) run under tenant 1 instead of being
	// rejected for an empty tenant context.
	cfg := fmt.Sprintf("current-context: otk\ncontexts:\n  otk:\n    server: %q\n    token: %q\n    tenant: %q\n    user: %q\n",
		os.Getenv("LUMNIK_URL"), envOr("LUMNIK_TOKEN", "dev-token"), otkTenant, otkTenant)
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
}
