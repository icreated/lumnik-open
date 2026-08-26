package client

import (
	"fmt"
	"net/http"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/oidc"
)

// refreshed carries new token material the caller must persist.
type refreshed struct {
	Access    string
	Refresh   string
	ExpiresAt int64
}

// bearerToken returns the access token to send. If the OIDC access token is
// expired (or within 30s of expiry) it refreshes and returns the new material to
// persist. Contexts without OIDC (Access == "") return the static Token unchanged.
func bearerToken(h *http.Client, tokenEndpoint string, ctx config.Context, now time.Time) (string, *refreshed, error) {
	if ctx.Access == "" {
		return ctx.Token, nil, nil // dev/static token path
	}
	if now.Unix() < ctx.ExpiresAt-30 { // 30s skew
		return ctx.Access, nil, nil
	}
	// Expired: refresh — but only if we actually have what a refresh needs.
	// Otherwise we'd POST an empty refresh token and surface a confusing error.
	if ctx.Refresh == "" || tokenEndpoint == "" || ctx.OidcClientID == "" {
		return "", nil, fmt.Errorf("access token expired and cannot refresh (missing refresh token or OIDC config)")
	}
	tok, err := oidc.Refresh(h, tokenEndpoint, ctx.OidcClientID, ctx.Refresh)
	if err != nil {
		return "", nil, err
	}
	r := &refreshed{Access: tok.AccessToken, Refresh: tok.RefreshToken,
		ExpiresAt: now.Add(time.Duration(tok.ExpiresIn) * time.Second).Unix()}
	return tok.AccessToken, r, nil
}

// reloadAndRetry is the rotation-resilience step: after a refresh failure, if a
// neighbor session already rotated the token (the on-disk refresh token differs
// from ours), retry the refresh ONCE with the fresh one. It returns only the
// token material to persist — the caller applies it onto its OWN context so
// runtime overrides (--server/--token, env) are preserved. ok=false means the
// session is genuinely dead and the caller should signal auth-lost.
func reloadAndRetry(h *http.Client, load func() (config.Context, bool),
	ctx config.Context, now time.Time) (string, *refreshed, bool) {
	disk, ok := load()
	if !ok || disk.Refresh == "" || disk.Refresh == ctx.Refresh {
		return "", nil, false // nobody rotated a fresher token
	}
	// Prefer the disk's OIDC settings for the retry (fresher truth), falling back to ours.
	endpoint := disk.OidcTokenEndpoint
	if endpoint == "" {
		endpoint = ctx.OidcTokenEndpoint
	}
	clientID := disk.OidcClientID
	if clientID == "" {
		clientID = ctx.OidcClientID
	}
	tok, err := oidc.Refresh(h, endpoint, clientID, disk.Refresh)
	if err != nil {
		return "", nil, false
	}
	r := &refreshed{Access: tok.AccessToken, Refresh: tok.RefreshToken,
		ExpiresAt: now.Add(time.Duration(tok.ExpiresIn) * time.Second).Unix()}
	return tok.AccessToken, r, true
}
