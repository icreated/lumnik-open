//go:build otk

package otk

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/checks"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

const stepAuthReject = `^the hub rejects the adversarial token for seed (\d+)$`

func init() {
	stepPatterns = append(stepPatterns, stepAuthReject)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepAuthReject, func(seed int) error { return st.authProbe(int64(seed)) })
	})
}

// defectMap maps the generator's defect name to the forge defect and the boundary
// expectation it implies.
var defectMap = map[string]struct {
	d      auth.Defect
	expect checks.Expectation
}{
	"wrong-role":   {auth.WrongRole, checks.Reject403},
	"expired":      {auth.Expired, checks.Reject401},
	"bad-audience": {auth.BadAudience, checks.Reject401},
	"bad-issuer":   {auth.BadIssuer, checks.Reject401},
	"tampered-sig": {auth.TamperedSig, checks.Reject401},
	"cross-tenant": {auth.CrossTenant, checks.EmptyResult},
	"garbage":      {auth.Garbage, checks.Reject401},
}

func (s *scenarioState) authProbe(seed int64) (retErr error) {
	if otkKey == nil {
		// Dormant under dev-mode: the auth lane needs a real-auth hub (OTK_OIDC_ISSUER).
		fmt.Printf("  OTK auth lane DORMANT (seed=%d): set OTK_OIDC_ISSUER + boot %%otkauth\n", seed)
		return nil
	}
	sc := generator.GenerateAuth(seed)
	m, ok := defectMap[sc.DefectName]
	if !ok {
		return fmt.Errorf("auth lane: unknown defect %q (seed=%d)", sc.DefectName, seed)
	}

	// Cross-tenant needs a baseline connector in tenant 1 to attempt to read across.
	foreignName := ""
	if m.expect == checks.EmptyResult {
		name, err := s.seedTenantOneBaseline(seed)
		if err != nil {
			return fmt.Errorf("auth lane: seeding tenant-1 baseline: %w", err)
		}
		foreignName = name
		// Fail loud if teardown fails: a leaked baseline connector flakes later
		// scenarios. Don't clobber a real probe error if one already occurred (the
		// After-hook safeguard is the backstop for the leak in that case).
		defer func() {
			if cerr := s.dropTenantOneBaseline(); cerr != nil && retErr == nil {
				retErr = fmt.Errorf("auth lane: tearing down tenant-1 baseline: %w", cerr)
			}
		}()
	}

	token, err := otkKey.Forge(m.d, seed)
	if err != nil {
		return fmt.Errorf("auth lane: forge %s: %w", sc.DefectName, err)
	}
	status, body := httpProbe(token)

	// A status of 0 means the HTTP request never reached the hub. checks.AuthOutcome
	// treats status 0 as "no finding" (its reachability precondition for hub-free
	// unit runs), so under a real-auth run an unreachable hub would silently make
	// every auth finding vanish — a false green. Fail loud instead.
	if status == 0 {
		return fmt.Errorf("auth lane: hub unreachable: %s", body)
	}

	s.findings = append(s.findings, checks.AuthOutcome(status, body, m.expect, sc.DefectName, seed)...)
	if m.expect == checks.EmptyResult {
		s.findings = append(s.findings, checks.CrossTenantLeak(body, foreignName, seed)...)
	}
	return nil
}

// httpProbe issues GET /api/platform/connectors with the given bearer token and
// returns the status and body. The lane probes over raw HTTP (not via lm) on
// purpose: grading the auth boundary needs the exact status code (401 vs 403 vs
// 200), which lm collapses into a generic exit code. Bounded so a hung hub never
// blocks the harness.
func httpProbe(token string) (int, string) {
	url := strings.TrimRight(os.Getenv("LUMNIK_URL"), "/") + "/api/platform/connectors"
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		// A malformed LUMNIK_URL would otherwise nil the request and panic in Do;
		// route it through the same (0, err) transport contract the lane guards on.
		return 0, err.Error()
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	cl := &http.Client{Timeout: 15 * time.Second}
	resp, err := cl.Do(req)
	if err != nil {
		return 0, err.Error()
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

// seedTenantOneBaseline creates a benign connector under a valid integrator token
// (tenant 1) and returns its name, so the cross-tenant probe has something to try
// (and fail) to read. It uses the run lane's BENIGN manifest (GenerateRun via
// givenRunForSeed), not the apply lane's adversarial generator: the latter may
// intentionally yield a manifest that creates no connector, which would flake the
// cross-tenant probe before the auth boundary is ever exercised. (The run manifest
// also writes a source CSV we never ingest — harmless; the After-hook removes it.)
func (s *scenarioState) seedTenantOneBaseline(seed int64) (string, error) {
	if err := s.givenRunForSeed(seed); err != nil {
		return "", err
	}
	if err := s.whenApply(); err != nil {
		return "", err
	}
	// whenApply treats a non-zero apply exit as a graded outcome (the apply lane's
	// AtomicityApply job). Here the baseline is a hard PRECONDITION, so a non-zero
	// exit (or partial write) must fail loud rather than feed the probe an
	// inconsistent baseline.
	if err := applyMustSucceed(s.result, lmBinary()); err != nil {
		return "", err
	}
	names := newNames(s.beforeAPI, s.afterAPI)
	if len(names) != 1 {
		return "", fmt.Errorf("baseline created %d connectors, want 1", len(names))
	}
	return names[0], nil
}

// dropTenantOneBaseline tears down the cross-tenant baseline. cleanup() deletes the
// diff between s.beforeAPI and s.afterAPI (both set by whenApply above), so it is
// name-agnostic. The error is surfaced (not swallowed) so a failed teardown fails
// the scenario rather than leaking a connector into later ones.
func (s *scenarioState) dropTenantOneBaseline() error {
	return s.cleanup() // delete the diff set, then verify gone
}
