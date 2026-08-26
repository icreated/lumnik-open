//go:build otk

package otk

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/cucumber/godog"
	otkauth "github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

const (
	stepViewGiven      = `^a view manifest for variant "([^"]*)"(?: \[seed=(-?\d+)\])?$`
	stepViewWhenRender = `^I render the view(?: with filter "([^"]*)")?( as cross-tenant| as viewer)?$`
	stepViewStatus     = `^the view render response status is (\d+)$`
	stepViewSafe       = `^the view render response is safe$`
	stepViewCleanup    = `^wrong-role-view-render must not return 200$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepViewGiven, stepViewWhenRender, stepViewStatus, stepViewSafe, stepViewCleanup)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepViewGiven, func(variant, seedStr string) error { return viewGiven(st, variant, seedStr) })
		ctx.Step(stepViewWhenRender, func(filter, suffix string) error { return viewWhenRender(st, filter, suffix) })
		ctx.Step(stepViewStatus, func(code int) error { return viewStatusStep(st, code) })
		ctx.Step(stepViewSafe, func() error { return viewSafe(st) })
		ctx.Step(stepViewCleanup, func() error { return viewWrongRoleCleanup(st) })
	})
}

func viewGiven(st *scenarioState, variant, seedStr string) error {
	manifest, filter, crossTenant, wrongRole, _, safeRows, found := generator.ViewVariantByName(variant)
	if !found {
		return fmt.Errorf("view lane: unknown variant %q", variant)
	}
	st.viewScen = generator.ViewScenario{
		Seed:         parseSeed(seedStr),
		Variant:      variant,
		ManifestYAML: manifest,
		Filter:       filter,
		CrossTenant:  crossTenant,
		WrongRole:    wrongRole,
		SafeRows:     safeRows,
	}
	return nil
}

func viewWhenRender(st *scenarioState, filter, suffix string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("view lane: LUMNIK_URL is not set")
	}
	// The step text IS the executable spec: the filter captured from the When line
	// is authoritative (the corpus is generated from the variant catalog, so the two
	// always agree; taking the step text means a hand-edited corpus is honoured, not
	// silently overridden by the variant).

	var token string
	switch strings.TrimSpace(suffix) {
	case "as cross-tenant":
		if otkKey == nil {
			fmt.Printf("  OTK view lane DORMANT (cross-tenant): set OTK_OIDC_ISSUER + boot %%otkauth\n")
			st.viewStatus = -1
			return nil
		}
		var err error
		if token, err = otkKey.Forge(otkauth.CrossTenant, 0); err != nil {
			return fmt.Errorf("view lane: forge cross-tenant token: %w", err)
		}
	case "as viewer":
		if otkKey == nil {
			fmt.Printf("  OTK view lane DORMANT (wrong-role): set OTK_OIDC_ISSUER + boot %%otkauth\n")
			st.viewStatus = -1
			return nil
		}
		var err error
		if token, err = otkKey.Forge(otkauth.WrongRole, 0); err != nil {
			return fmt.Errorf("view lane: forge viewer token: %w", err)
		}
	default:
		var err error
		if token, err = currentToken(); err != nil {
			return fmt.Errorf("view lane: mint integrator token: %w", err)
		}
	}

	// The render read variants that require the seeded otk scope card are dormant
	// without OTK_DB_DSN (like the entity read lane): unknown-scope/wrong-kind still
	// resolve to their status without a card, but a card-backed variant would 404.
	if needsCard(st.viewScen.Variant) && seedDSNAbsent() {
		fmt.Printf("  OTK view lane DORMANT (variant=%q needs the seeded otk card): set OTK_DB_DSN\n", st.viewScen.Variant)
		st.viewStatus = -1
		return nil
	}

	path := "/api/views/render"
	if filter != "" {
		path += "?filter=" + url.QueryEscape(filter)
	}
	status, body, err := inboxHTTPBody("POST", path, token, []byte(st.viewScen.ManifestYAML), "application/yaml")
	if err != nil {
		return fmt.Errorf("view lane: POST /api/views/render: %w", err)
	}
	st.viewStatus = status
	st.viewBody = body
	return nil
}

// needsCard reports whether a variant resolves against the seeded otk scope card
// (and so is dormant without the seed). These variants reach their verdict without
// the card: unknown-scope (404, no card by name), wrong-kind (400, parser), wrong-role
// (403, before body work), and cross-tenant-no-card (404 — a foreign tenant has no
// card for the scope whether or not tenant 1 seeded one, which is the whole point).
func needsCard(variant string) bool {
	switch variant {
	case "unknown-scope", "wrong-kind", "wrong-role", "cross-tenant-no-card":
		return false
	default:
		return true
	}
}

func viewStatusStep(st *scenarioState, code int) error {
	if st.viewStatus == -1 {
		return nil // dormant
	}
	if st.viewStatus == 0 {
		return fmt.Errorf("view lane: render was never called (status=0)")
	}
	if st.viewStatus != code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security, Seed: st.viewScen.Seed,
			Detail: fmt.Sprintf("view render status: got %d, want %d", st.viewStatus, code),
		})
	}
	return nil
}

func viewSafe(st *scenarioState) error {
	if st.viewStatus == -1 {
		return nil // dormant
	}
	if strings.Contains(st.viewBody, "DROP TABLE") {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security, Seed: st.viewScen.Seed,
			Detail: "view render response reflects injected SQL content (DROP TABLE)",
		})
	}
	return nil
}

func viewWrongRoleCleanup(st *scenarioState) error {
	if st.viewStatus == -1 {
		return nil // dormant
	}
	if st.viewStatus == 200 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security, Seed: st.viewScen.Seed,
			Detail: "wrong-role view render returned 200 (role escalation — must be 403)",
		})
	}
	return nil
}
