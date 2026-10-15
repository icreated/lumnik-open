package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// APIError is a non-2xx response from the hub API, carrying enough of the
// response to render a useful message and to drive rate-limit handling.
type APIError struct {
	Status     int
	Body       string
	RetryAfter string // seconds, from the Retry-After header (rate limiting); "" if absent
}

// Error renders a human-readable message for the API failure: it surfaces the
// hub's uniform {"error": …} body when present, and falls back to guidance for
// the common bodyless 401/403 responses Quarkus returns.
func (e *APIError) Error() string {
	if body := strings.TrimSpace(e.Body); body != "" {
		// The hub's refusals are uniform {"error": …} — surface the message, not raw JSON.
		var m struct {
			Err string `json:"error"`
		}
		if json.Unmarshal([]byte(body), &m) == nil && m.Err != "" {
			switch e.Status {
			case 403:
				return fmt.Sprintf("access refused (403): %s", m.Err)
			case 429:
				if e.RetryAfter != "" {
					return fmt.Sprintf("rate limited (429): %s, retry in %ss", m.Err, e.RetryAfter)
				}
				return fmt.Sprintf("rate limited (429): %s", m.Err)
			default:
				return fmt.Sprintf("API error %d: %s", e.Status, m.Err)
			}
		}
		return fmt.Sprintf("API error %d: %s", e.Status, body)
	}
	// Quarkus often returns 401/403 with no body — don't surface a blank "API error 401: ".
	switch e.Status {
	case 401:
		return "API error 401 (unauthorized): no details from the server — your token may be missing or expired (try 'lm login')"
	case 403:
		return "API error 403 (forbidden): no details from the server — your account may lack the required role"
	default:
		return fmt.Sprintf("API error %d (no details from the server)", e.Status)
	}
}

// Client is an authenticated HTTP client bound to one config context (server +
// credentials); every API-calling method in this package hangs off it.
type Client struct {
	ctx  config.Context
	name string // the config context name this client represents (for token persistence)
	http *http.Client
	// stream is a second client sharing http's Transport but with Timeout 0:
	// http.Client.Timeout bounds the WHOLE round trip including body reads, so
	// it would silently kill a long-lived SSE connection at 30s. Long-lived
	// streams are instead bounded by the caller's context (already threaded
	// via http.NewRequestWithContext) — that context owns the lifetime.
	stream     *http.Client
	mu         sync.Mutex // guards the refresh+persist path in authorize
	onAuthLost func()
	lostFired  atomic.Bool
	// staticToken pins ctx.Token as the bearer and takes the refresh path out of
	// play entirely. Set only by PinStaticToken, i.e. only for an explicit --token.
	staticToken bool
}

// PinStaticToken makes this client send ctx.Token and never refresh — what an
// explicit `--token` on the command line means.
//
// The ordinary policy is the opposite, and stays: a static token read from the
// config FILE loses to a live `lm login` access token, so a stale value someone
// left behind cannot shadow a working session. A token typed on the command line
// is not a leftover. It was still losing, so `lm --token "$TOK" …` against an
// expired context ran the refresh first and died on it before the override was
// ever reached (#491).
//
// Pinned on the client rather than by blanking ctx.Access, because ReloadContext
// re-reads the auth fields from disk after a re-login and would put the stored
// access token straight back in front of the override — the same defect, one
// method further along.
func (c *Client) PinStaticToken() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.staticToken = true
}

// SetAuthLost arms the single callback the client fires when the session is dead
// (refresh failed past the reload-retry, or a 401 on an authenticated request).
func (c *Client) SetAuthLost(cb func()) { c.onAuthLost = cb }

// RearmAuthLost clears the debounce so the next failing request re-fires the
// signal — used when the human dismisses the modal but keeps hitting a dead session.
func (c *Client) RearmAuthLost() { c.lostFired.Store(false) }

// signalAuthLost fires the auth-lost callback at most once (debounced) until a
// later successful auth resets it, so N concurrent failing requests raise ONE modal.
func (c *Client) signalAuthLost() {
	if c.onAuthLost == nil {
		return
	}
	if c.lostFired.CompareAndSwap(false, true) {
		c.onAuthLost()
	}
}

// New builds a Client for ctx, persisting any refreshed OIDC token back to the
// on-disk current-context (see NewForContext for the named-context variant).
func New(ctx config.Context) *Client {
	return NewForContext("", ctx)
}

// NewForContext builds a client bound to a named config context. Refreshed OIDC
// tokens are persisted back to THAT context (not blindly to current-context), so
// a --context override never overwrites another context's tokens. An empty name
// falls back to current-context at persist time.
func NewForContext(name string, ctx config.Context) *Client {
	tr := &http.Transport{}
	if tlsCfg, err := TLSConfigFor(ctx); err != nil {
		// Keep NewForContext's no-error signature: warn once and fall back to
		// system trust rather than failing every call built from this context.
		fmt.Fprintf(os.Stderr, "lm: %v — falling back to system trust\n", err)
	} else if tlsCfg != nil {
		tr.TLSClientConfig = tlsCfg
	}
	return &Client{
		ctx:  ctx,
		name: name,
		http: &http.Client{Timeout: 30 * time.Second, Transport: tr},
		// Same Transport, zero Timeout — see the stream field doc comment.
		stream: &http.Client{Transport: tr},
	}
}

// ReloadContext re-reads this client's context from disk (fresh tokens after a
// re-login) and resets the auth-lost debounce.
func (c *Client) ReloadContext() {
	path, err := config.DefaultPath()
	if err != nil {
		return
	}
	cfg, err := config.LoadFromFile(path)
	if err != nil {
		return
	}
	name := c.name
	if name == "" {
		name = cfg.CurrentContext
	}
	if ctx, ok := cfg.Contexts[name]; ok {
		c.mu.Lock()
		// Only the auth fields — preserve the effective Server/Token/tenant/user
		// this client was built with (--server/--token flags, env overrides).
		c.ctx.Access = ctx.Access
		c.ctx.Refresh = ctx.Refresh
		c.ctx.ExpiresAt = ctx.ExpiresAt
		c.ctx.OidcTokenEndpoint = ctx.OidcTokenEndpoint
		c.ctx.OidcClientID = ctx.OidcClientID
		c.mu.Unlock()
		c.lostFired.Store(false)
	}
}

// ContextName exposes the config context name the re-login modal needs to
// look up the full context (TLS trust settings included) from disk.
func (c *Client) ContextName() string { return c.name }

// GET issues an authenticated GET to path and decodes the JSON response into out.
func (c *Client) GET(path string, out any) error {
	return c.do("GET", path, nil, out)
}

// POST issues an authenticated POST to path, marshalling body as the JSON
// request payload and decoding the JSON response into out.
func (c *Client) POST(path string, body, out any) error {
	return c.do("POST", path, body, out)
}

// modelCallTimeout bounds the calls that can legitimately run past the 30s c.http budget: the
// model-bound ones (ask, chat, view generate — CPU inference takes minutes on a cold model) and
// EntityResolve (a synchronous bulk SQL scan over one entity's whole extent) — while still
// guaranteeing the call cannot hang forever.
const modelCallTimeout = 5 * time.Minute

// POSTSlow is POST for slow endpoints (model-bound, or a synchronous bulk operation): the same
// request pipeline, but on the stream client (Timeout 0) bounded by modelCallTimeout through the
// request context — the same pattern the SSE paths already use.
func (c *Client) POSTSlow(path string, body, out any) error {
	return c.doOn(c.stream, modelCallTimeout, "POST", path, body, out)
}

// PUT issues an authenticated PUT to path, marshalling body as the JSON request
// payload and decoding the JSON response into out.
func (c *Client) PUT(path string, body, out any) error {
	return c.do("PUT", path, body, out)
}

// DELETE issues an authenticated DELETE to path and discards any response body.
func (c *Client) DELETE(path string) error {
	return c.do("DELETE", path, nil, nil)
}

func (c *Client) do(method, path string, body, out any) error {
	return c.doOn(c.http, 0, method, path, body, out)
}

// doOn is do() with an explicit http client and an optional per-request deadline
// (0 = rely on hc's own Timeout). The deadline rides the request context, so it
// bounds the whole round trip even on the Timeout-0 stream client.
func (c *Client) doOn(hc *http.Client, timeout time.Duration, method, path string, body, out any) error {
	return c.doOnCtx(context.Background(), hc, timeout, method, path, body, out)
}

// doOnCtx is doOn under a caller's context: cancelling it (an interrupted command that waits)
// aborts the round trip instead of leaving it to run out its own timeout.
func (c *Client) doOnCtx(ctx context.Context, hc *http.Client, timeout time.Duration, method, path string, body, out any) error {
	var reader io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(buf)
	}
	url := strings.TrimRight(c.ctx.Server, "/") + path
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if err := c.authorize(req); err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	// Snapshot auth state under the mutex: authorize()/ReloadContext() write ctx.Access
	// under c.mu on other goroutines — reading it here unguarded is a data race.
	//
	// `Access != ""` alone used to be an accurate stand-in for "this request carried an OIDC
	// session", because a context holding a stored access token ALWAYS took the OIDC path.
	// That was the accidental confinement #491 removed: with an explicit --token the credential
	// on the wire is static while ctx.Access is still set, so the flag and the request disagreed
	// and a 401 raised a modal offering to re-login a session that was never in play. The state
	// is not what is wrong — clearing ctx.Access here would be undone by the next
	// ReloadContext — the PREDICATE was, so it now says what it means.
	c.mu.Lock()
	oidcSession := !c.staticToken && c.ctx.Access != ""
	c.mu.Unlock()
	if resp.StatusCode == 401 && oidcSession {
		// OIDC session rejected by the server — re-login can fix it. A static-token
		// 401 is NOT signalled (device-flow login can't help it).
		c.signalAuthLost()
	}
	if resp.StatusCode < 400 && oidcSession {
		c.lostFired.Store(false) // a confirmed-good authenticated request resets the debounce
	}
	if resp.StatusCode >= 400 {
		return &APIError{Status: resp.StatusCode, Body: string(data), RetryAfter: resp.Header.Get("Retry-After")}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

// authorize attaches the Authorization + tenant/user headers to a request. It is used by
// do() and by every hand-built request method (apply, entity, validate, describe, csv, …),
// so they all resolve the token the same way: the fresh `lm login` Access token (refreshed
// if needed) when present, else the static ctx.Token — a stale/expired static token can
// never shadow a valid login token.
func (c *Client) authorize(req *http.Request) error {
	// Serialize refresh+persist so two TUI goroutines never both POST a refresh.
	c.mu.Lock()
	defer c.mu.Unlock()

	// An explicit --token pins the static path: no refresh, so nothing to persist and
	// no auth-lost signal to raise — there is no session to have lost. Decided HERE,
	// in the one function that resolves a credential, and then falling through to the
	// shared tail: a second path would have to re-write the Authorization header and
	// the tenant/user headers, and would silently miss whatever bearerToken grows next.
	tok, r, err := c.ctx.Token, (*refreshed)(nil), error(nil)
	if !c.staticToken {
		tok, r, err = bearerToken(c.http, c.ctx.OidcTokenEndpoint, c.ctx, time.Now())
	}
	if err != nil {
		// Refresh failed. A neighbor may have rotated a fresh token to disk — try it once.
		load := func() (config.Context, bool) {
			path, e := config.DefaultPath()
			if e != nil {
				return config.Context{}, false
			}
			cfg, e := config.LoadFromFile(path)
			if e != nil {
				return config.Context{}, false
			}
			name := c.name
			if name == "" {
				name = cfg.CurrentContext
			}
			ctx, ok := cfg.Contexts[name]
			return ctx, ok
		}
		var ok bool
		tok, r, ok = reloadAndRetry(c.http, load, c.ctx, time.Now())
		if !ok {
			c.signalAuthLost()
			return fmt.Errorf("auth: %w (run 'lm login')", err)
		}
		// r is applied onto our OWN context below — runtime overrides preserved.
	}
	if tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	if r != nil {
		// Reuse the fresh token on subsequent requests instead of refreshing every call.
		c.ctx.Access, c.ctx.Refresh, c.ctx.ExpiresAt = r.Access, r.Refresh, r.ExpiresAt
		c.persistRefresh(r)
	}
	if c.ctx.Tenant != "" {
		req.Header.Set("X-Tenant-ID", c.ctx.Tenant)
	}
	if c.ctx.User != "" {
		req.Header.Set("X-User-ID", c.ctx.User)
	}
	return nil
}

func (c *Client) persistRefresh(r *refreshed) {
	path, err := config.DefaultPath()
	if err != nil {
		return
	}
	cfg, err := config.LoadFromFile(path)
	if err != nil {
		return
	}
	name := c.name
	if name == "" {
		name = cfg.CurrentContext
	}
	if applyRefresh(cfg, name, r) {
		_ = cfg.Save(path)
	}
}

// applyRefresh writes the refreshed token fields onto the named context, leaving
// its other fields untouched. It returns false (and writes nothing) when the
// context does not exist, so a corrupt config / stale current-context never
// creates a ghost entry holding only tokens.
func applyRefresh(cfg *config.Config, name string, r *refreshed) bool {
	ctx, ok := cfg.Contexts[name]
	if !ok {
		return false
	}
	ctx.Access, ctx.Refresh, ctx.ExpiresAt = r.Access, r.Refresh, r.ExpiresAt
	cfg.Contexts[name] = ctx
	return true
}
