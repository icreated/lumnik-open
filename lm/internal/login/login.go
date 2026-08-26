package login

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/brand"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/oidc"
	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

// Run executes the OIDC device flow for cfg.Contexts[name], prints the URL +
// user code to out, polls for the token, and persists Access/Refresh/ExpiresAt
// back to path. Reused by the `lm login` command and the TUI's re-login modal.
func Run(ctx context.Context, h *http.Client, cfg *config.Config, path, name string, out io.Writer) error {
	c, ok := cfg.Contexts[name]
	if !ok {
		return fmt.Errorf("context %q not found in config", name)
	}
	if c.OidcClientID == "" {
		c.OidcClientID = "lm-cli"
	}
	if c.OidcDeviceEndpoint == "" || c.OidcTokenEndpoint == "" {
		if c.OidcIssuer == "" {
			return fmt.Errorf("context %q has no OIDC config: run 'lm config set-context %s --oidc-issuer <url>', "+
				"or set the oidc-device-endpoint / oidc-token-endpoint keys in ~/.lm/config.yaml directly", name, name)
		}
		disc, err := oidc.Discover(ctx, h, c.OidcIssuer)
		if err != nil {
			return err
		}
		if c.OidcDeviceEndpoint == "" {
			c.OidcDeviceEndpoint = disc.DeviceAuthorizationEndpoint
		}
		if c.OidcTokenEndpoint == "" {
			c.OidcTokenEndpoint = disc.TokenEndpoint
		}
	}
	dc, err := oidc.RequestDeviceCode(h, c.OidcDeviceEndpoint, c.OidcClientID)
	if err != nil {
		return err
	}
	uri := dc.VerificationURIComplete
	if uri == "" {
		uri = dc.VerificationURI
	}
	prompt(out, uri, dc.UserCode)
	expiry := dc.ExpiresIn
	if expiry <= 0 {
		expiry = 600
	}
	pollCtx, cancel := context.WithTimeout(ctx, time.Duration(expiry)*time.Second)
	defer cancel()
	tok, err := oidc.PollToken(pollCtx, h, c.OidcTokenEndpoint, c.OidcClientID, dc.DeviceCode, time.Duration(dc.Interval)*time.Second)
	if err != nil {
		return err
	}
	c.Access = tok.AccessToken
	c.Refresh = tok.RefreshToken
	c.ExpiresAt = time.Now().Add(time.Duration(tok.ExpiresIn) * time.Second).Unix()
	cfg.Contexts[name] = c
	fmt.Fprintln(out, "Logged in.")
	return cfg.Save(path)
}

// HTTPClient builds the login http client, honoring a context's TLS trust settings
// (ca-file beats insecure-skip-verify — see client.TLSConfigFor, the single shared
// helper both this and the main API client build their transport from). It clones
// http.DefaultTransport so proxy/timeout/HTTP-2 defaults survive (device-flow login
// must work behind a corporate proxy); only TLS verification is overridden.
func HTTPClient(ctx config.Context) *http.Client {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	if tlsCfg, err := client.TLSConfigFor(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "lm: %v — falling back to system trust\n", err)
	} else if tlsCfg != nil {
		tr.TLSClientConfig = tlsCfg
	}
	return &http.Client{Timeout: 30 * time.Second, Transport: tr}
}

// prompt is the login page: the wordmark, then the two things a human must actually do. Width 0
// means "assume a normal terminal" — `lm login` is interactive by nature, and a pipe reading this
// output is reading it for a human anyway.
//
// The banner goes to the unwrapped writer because lm's stdout is a textsafe.Writer and ESC is a C0
// control rune: the colour would arrive as \u001B soup. That bypass is safe for exactly one reason
// — the banner is a compile-time constant of ours. The URI and the code come from the device
// endpoint, so they keep the guard and stay on out.
func prompt(out io.Writer, uri, code string) {
	fmt.Fprint(textsafe.Unwrap(out), brand.Banner(0))
	fmt.Fprintf(out, "\nTo sign in, open:\n  %s\nand enter code: %s\n\nWaiting...\n", uri, code)
}
