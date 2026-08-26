// Package auth provides token sources for the OTK auth-adversarial lane. A
// TokenSource yields a valid integrator token; ControlledKey additionally forges
// adversarial tokens for the deterministic battery.
package auth

// TokenSource yields a valid integrator access token for a tenant/user.
type TokenSource interface {
	Integrator(tenant, user string) (string, error)
}

// ControlledKey satisfies TokenSource.
var _ TokenSource = (*ControlledKey)(nil)
