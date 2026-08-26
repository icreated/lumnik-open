package auth

import (
	"crypto/x509"
	"encoding/pem"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

func mustKey(t *testing.T) *ControlledKey {
	t.Helper()
	k, err := NewControlledKey("https://otk.test/issuer", "lumnik-backend")
	if err != nil {
		t.Fatalf("NewControlledKey: %v", err)
	}
	return k
}

// parse verifies a token against the key's own public key and returns its claims.
func (k *ControlledKey) parseForTest(t *testing.T, tok string) jwt.MapClaims {
	t.Helper()
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(tok, claims, func(*jwt.Token) (any, error) { return &k.priv.PublicKey, nil })
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	return claims
}

func TestIntegrator_HasIntegratorGroupAndTenant(t *testing.T) {
	k := mustKey(t)
	tok, err := k.Integrator("1", "1")
	if err != nil {
		t.Fatalf("Integrator: %v", err)
	}
	c := k.parseForTest(t, tok)
	if c["tenant_id"] != "1" || c["user_id"] != "1" {
		t.Fatalf("claims tenant/user wrong: %v", c)
	}
	groups, _ := c["groups"].([]any)
	found := false
	for _, g := range groups {
		if g == "lm_integrator" {
			found = true
		}
	}
	if !found {
		t.Fatalf("integrator token must carry groups=[lm_integrator], got %v", c["groups"])
	}
}

func TestForge_WrongRole_OmitsIntegratorGroup(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(WrongRole, 7)
	c := k.parseForTest(t, tok)
	if groups, ok := c["groups"].([]any); ok {
		for _, g := range groups {
			if g == "lm_integrator" {
				t.Fatalf("WrongRole token must not carry lm_integrator")
			}
		}
	}
}

func TestForge_Expired_IsInThePast(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(Expired, 7)
	_, err := jwt.Parse(tok, func(*jwt.Token) (any, error) { return &k.priv.PublicKey, nil })
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("Expired token must fail validation as expired, got %v", err)
	}
}

func TestForge_CrossTenant_IsTenantTwo(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(CrossTenant, 7)
	c := k.parseForTest(t, tok)
	if c["tenant_id"] != "2" {
		t.Fatalf("CrossTenant token must carry tenant_id=2, got %v", c["tenant_id"])
	}
}

func TestForge_TamperedSig_FailsValidation(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(TamperedSig, 7)
	_, err := jwt.Parse(tok, func(*jwt.Token) (any, error) { return &k.priv.PublicKey, nil })
	if err == nil {
		t.Fatalf("TamperedSig token must fail signature validation")
	}
}

func TestForge_Garbage_IsNotAJWT(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(Garbage, 7)
	if strings.Count(tok, ".") == 2 {
		t.Fatalf("Garbage token should not look like a JWT: %q", tok)
	}
}

func TestForge_BadAudience_DropsRealAudience(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(BadAudience, 7)
	c := k.parseForTest(t, tok)
	if c["aud"] == "lumnik-backend" {
		t.Fatalf("BadAudience must not keep the real audience")
	}
}

func TestForge_BadIssuer_DropsRealIssuer(t *testing.T) {
	k := mustKey(t)
	tok, _ := k.Forge(BadIssuer, 7)
	c := k.parseForTest(t, tok)
	if c["iss"] == "https://otk.test/issuer" {
		t.Fatalf("BadIssuer must not keep the real issuer")
	}
}

func TestNewControlledKeyFromPEM_RoundTrips(t *testing.T) {
	k := mustKey(t)
	// Emit this key's private PEM the way cmd/otkkey does, then reload it.
	privPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(k.priv),
	})
	k2, err := NewControlledKeyFromPEM(privPEM, "https://otk.test/issuer", "lumnik-backend")
	if err != nil {
		t.Fatalf("NewControlledKeyFromPEM: %v", err)
	}
	// A token signed by k2 must verify against k's public key (same keypair).
	tok, err := k2.Integrator("1", "1")
	if err != nil {
		t.Fatalf("Integrator: %v", err)
	}
	k.parseForTest(t, tok) // fails the test if verification fails
}

func TestNewControlledKeyFromPEM_RejectsGarbage(t *testing.T) {
	if _, err := NewControlledKeyFromPEM([]byte("not a pem"), "i", "a"); err == nil {
		t.Fatal("garbage PEM must error")
	}
}

func TestPublicKeyPEM_IsParseablePEM(t *testing.T) {
	k := mustKey(t)
	pem := k.PublicKeyPEM()
	if !strings.Contains(pem, "BEGIN PUBLIC KEY") {
		t.Fatalf("PublicKeyPEM must be PEM-encoded")
	}
}

// groupsOf extracts the groups claim as a string set.
func groupsOf(t *testing.T, c jwt.MapClaims) map[string]bool {
	t.Helper()
	raw, _ := c["groups"].([]any)
	set := map[string]bool{}
	for _, g := range raw {
		s, ok := g.(string)
		if !ok {
			t.Fatalf("non-string group in %v", c["groups"])
		}
		set[s] = true
	}
	return set
}

// The integrator persona must mirror the bundled realm's demo integrator
// ([lm_integrator, lm_admin, lm_user], proven on a from-zero realm re-import):
// entity apply needs lm_admin and entity read needs lm_user (no role hierarchy),
// so a lm_integrator-only token 403s the whole entity lane under real auth.
func TestIntegrator_CarriesRealmRoles(t *testing.T) {
	k := mustKey(t)
	tok, err := k.Integrator("1", "1")
	if err != nil {
		t.Fatalf("Integrator: %v", err)
	}
	got := groupsOf(t, k.parseForTest(t, tok))
	for _, want := range []string{"lm_integrator", "lm_admin", "lm_user"} {
		if !got[want] {
			t.Fatalf("integrator token must carry %s (realm parity), got %v", want, got)
		}
	}
}

// CrossTenant is a VALID integrator of another tenant: it must carry the same
// realm roles so the read reaches RLS (empty array) instead of dying at 403.
func TestCrossTenantForge_CarriesRealmRoles(t *testing.T) {
	k := mustKey(t)
	tok, err := k.Forge(CrossTenant, 0)
	if err != nil {
		t.Fatalf("Forge(CrossTenant): %v", err)
	}
	c := k.parseForTest(t, tok)
	if c["tenant_id"] != "2" {
		t.Fatalf("cross-tenant token must carry tenant_id=2, got %v", c["tenant_id"])
	}
	got := groupsOf(t, c)
	for _, want := range []string{"lm_integrator", "lm_admin", "lm_user"} {
		if !got[want] {
			t.Fatalf("cross-tenant token must carry %s (realm parity), got %v", want, got)
		}
	}
}

// ScopeBound must carry full realm roles (so it can pass the lm_integrator apply
// door and the lm_user render/list/get door) plus exactly the one scope: grant
// requested — never the scope the caller is about to probe (that's the caller's
// job, but the token itself must not silently carry an extra grant).
func TestScopeBoundForge_CarriesRealmRolesPlusOneScopeTag(t *testing.T) {
	k := mustKey(t)
	tok, err := k.ScopeBound("1", "1", "not-otk")
	if err != nil {
		t.Fatalf("ScopeBound: %v", err)
	}
	got := groupsOf(t, k.parseForTest(t, tok))
	for _, want := range []string{"lm_integrator", "lm_admin", "lm_user", "scope:not-otk"} {
		if !got[want] {
			t.Fatalf("scope-bound token must carry %s, got %v", want, got)
		}
	}
	if got["scope:otk"] {
		t.Fatalf("scope-bound token for 'not-otk' must not also carry scope:otk, got %v", got)
	}
}

// WrongRole must stay a viewer-only token — the 403 defect must not gain roles.
func TestWrongRoleForge_StaysViewerOnly(t *testing.T) {
	k := mustKey(t)
	tok, err := k.Forge(WrongRole, 0)
	if err != nil {
		t.Fatalf("Forge(WrongRole): %v", err)
	}
	got := groupsOf(t, k.parseForTest(t, tok))
	if !got["lm_viewer"] || got["lm_integrator"] || got["lm_admin"] || got["lm_user"] {
		t.Fatalf("wrong-role token must carry only lm_viewer, got %v", got)
	}
}
