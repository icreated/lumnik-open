package auth

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Defect names an adversarial token mutation. The expectation each defect implies
// (401 vs 403 vs empty) is decided by the lane, not here.
type Defect int

const (
	WrongRole   Defect = iota // valid sig, no lm_integrator group → expect 403
	Expired                   // valid sig, exp in the past         → expect 401
	BadAudience               // valid sig, wrong aud               → expect 401
	BadIssuer                 // valid sig, wrong iss               → expect 401
	TamperedSig               // good claims, flipped signature     → expect 401
	CrossTenant               // valid integrator, tenant_id=ForeignTenantID → expect empty
	Garbage                   // not a JWT at all                   → expect 401
)

// ForeignTenantID is the tenant a CrossTenant token is forged for — a real,
// otherwise-valid tenant distinct from the harness's own (see otkTenant in
// hubsnap.go). Named rather than inlined so a DB-truth cross-tenant probe
// (checks.RLSIsolation, wired in steps_auth.go) can pin the exact same tenant the
// forged token carries instead of re-hardcoding "2" a second place.
const ForeignTenantID = "2"

// ControlledKey is a self-minting TokenSource backed by an in-process RSA keypair.
// The hub is configured to trust PublicKeyPEM(), so any token ControlledKey signs
// is accepted unless the defect breaks signature/issuer/audience/expiry on purpose.
type ControlledKey struct {
	priv     *rsa.PrivateKey
	issuer   string
	audience string
}

// NewControlledKey generates a fresh 2048-bit RSA keypair bound to an issuer and
// audience that the hub's %otkauth profile must mirror.
func NewControlledKey(issuer, audience string) (*ControlledKey, error) {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	return &ControlledKey{priv: priv, issuer: issuer, audience: audience}, nil
}

// NewControlledKeyFromPEM builds a ControlledKey from a PKCS1 private-key PEM
// (the shared key emitted by cmd/otkkey), so the harness signs with the exact key
// the hub trusts.
func NewControlledKeyFromPEM(privPEM []byte, issuer, audience string) (*ControlledKey, error) {
	blk, _ := pem.Decode(privPEM)
	if blk == nil {
		return nil, errors.New("no PEM block in private key")
	}
	priv, err := x509.ParsePKCS1PrivateKey(blk.Bytes)
	if err != nil {
		return nil, err
	}
	return &ControlledKey{priv: priv, issuer: issuer, audience: audience}, nil
}

func (k *ControlledKey) Issuer() string   { return k.issuer }
func (k *ControlledKey) Audience() string { return k.audience }

// PublicKeyPEM returns the SubjectPublicKeyInfo PEM the hub trusts.
func (k *ControlledKey) PublicKeyPEM() string {
	der, _ := x509.MarshalPKIXPublicKey(&k.priv.PublicKey)
	return string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der}))
}

// PublicKeyBase64DER returns the base64 DER body (no PEM header), the form some
// Quarkus versions expect for quarkus.oidc.public-key. The %otkauth wiring picks
// whichever form the live boot accepts (see the de-risk task).
func (k *ControlledKey) PublicKeyBase64DER() string {
	der, _ := x509.MarshalPKIXPublicKey(&k.priv.PublicKey)
	return base64.StdEncoding.EncodeToString(der)
}

// realmRoles mirrors the bundled realm's demo integrator ([lm_integrator, lm_admin,
// lm_user]): entity apply needs lm_admin and reads need lm_user with no role
// hierarchy, so an lm_integrator-only token under-represents the persona and 403s.
func realmRoles() []string { return []string{"lm_integrator", "lm_admin", "lm_user"} }

// Integrator mints a valid token: correct iss/aud, future exp, groups=realmRoles().
func (k *ControlledKey) Integrator(tenant, user string) (string, error) {
	return k.sign(jwt.MapClaims{
		"iss":       k.issuer,
		"aud":       k.audience,
		"sub":       user,
		"tenant_id": tenant,
		"user_id":   user,
		"groups":    realmRoles(),
		"iat":       time.Now().Unix(),
		"exp":       time.Now().Add(10 * time.Minute).Unix(),
	})
}

// Forge mints the adversarial variant for a defect. seed is carried for traceability
// only; the mutation itself is fixed per defect (determinism comes from the seed
// driving which defect the generator selects).
func (k *ControlledKey) Forge(d Defect, seed int64) (string, error) {
	base := func() jwt.MapClaims {
		return jwt.MapClaims{
			"iss": k.issuer, "aud": k.audience, "sub": "1",
			"tenant_id": "1", "user_id": "1", "groups": realmRoles(),
			"iat": time.Now().Unix(), "exp": time.Now().Add(10 * time.Minute).Unix(),
		}
	}
	switch d {
	case WrongRole:
		c := base()
		c["groups"] = []string{"lm_viewer"}
		return k.sign(c)
	case Expired:
		c := base()
		c["iat"] = time.Now().Add(-1 * time.Hour).Unix()
		c["exp"] = time.Now().Add(-10 * time.Minute).Unix()
		return k.sign(c)
	case BadAudience:
		c := base()
		c["aud"] = "not-lumnik"
		return k.sign(c)
	case BadIssuer:
		c := base()
		c["iss"] = "https://evil.example/issuer"
		return k.sign(c)
	case CrossTenant:
		c := base()
		c["tenant_id"] = ForeignTenantID
		c["user_id"] = ForeignTenantID
		return k.sign(c)
	case TamperedSig:
		tok, err := k.sign(base())
		if err != nil {
			return "", err
		}
		bad := flipSigChar(tok)
		if bad == tok {
			// A defect generator that silently emits a still-valid token would erode
			// the harness's trust; fail loudly instead.
			return "", errors.New("tamper produced an unchanged token")
		}
		return bad, nil
	case Garbage:
		return "not-a-jwt-" + strconv.FormatInt(seed, 10), nil
	default:
		return "", errors.New("unknown defect")
	}
}

// ScopeBound mints a valid token carrying full realm roles PLUS one opt-in
// scope:<tag> grant (ScopeGrants, #131/#138) — a real identity bound to a
// scope OTHER than the one under test. Used to pin the uniform scope-403
// refusal without granting the probed scope: unlike WrongRole/CrossTenant this
// is not itself a defect — the hub must accept the token and refuse only the
// resource whose scope was never granted.
func (k *ControlledKey) ScopeBound(tenant, user, scope string) (string, error) {
	return k.sign(jwt.MapClaims{
		"iss": k.issuer, "aud": k.audience, "sub": user,
		"tenant_id": tenant, "user_id": user,
		"groups": append(realmRoles(), "scope:"+scope),
		"iat":    time.Now().Unix(),
		"exp":    time.Now().Add(10 * time.Minute).Unix(),
	})
}

func (k *ControlledKey) sign(c jwt.MapClaims) (string, error) {
	return jwt.NewWithClaims(jwt.SigningMethodRS256, c).SignedString(k.priv)
}

// flipSigChar mutates a character inside the signature segment so verification
// fails while the token still parses structurally (three dot-separated segments).
// It flips a character near the middle of the signature rather than the last one:
// the trailing base64 char can carry only padding bits that round-trip to the same
// decoded signature, leaving the token valid.
func flipSigChar(tok string) string {
	dot := strings.LastIndexByte(tok, '.')
	if dot < 0 || dot == len(tok)-1 {
		return tok
	}
	b := []byte(tok)
	i := dot + 1 + (len(b)-dot-1)/2 // middle of the signature segment
	if b[i] == 'A' {
		b[i] = 'B'
	} else {
		b[i] = 'A'
	}
	return string(b)
}
