package config

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// Context is one entry of ~/.lm/config.yaml. Its credentials (Token, Access, Refresh) are
// redacted by `lm config view` (cli.redacted): a new secret field must be added there too.
type Context struct {
	Server             string `yaml:"server"`
	Token              string `yaml:"token"`
	InsecureSkipVerify bool   `yaml:"insecure-skip-verify,omitempty"`
	CAFile             string `yaml:"ca-file,omitempty"` // trust an internal CA; takes precedence over insecure-skip-verify
	Tenant             string `yaml:"tenant,omitempty"`  // dev-bypass: sent as X-Tenant-ID header
	User               string `yaml:"user,omitempty"`    // dev-bypass: sent as X-User-ID header

	// OIDC (lm login). When Access is set it is sent as the Bearer token,
	// auto-refreshed via Refresh against OidcTokenEndpoint.
	OidcIssuer         string `yaml:"oidc-issuer,omitempty"`
	OidcClientID       string `yaml:"oidc-client-id,omitempty"`
	OidcDeviceEndpoint string `yaml:"oidc-device-endpoint,omitempty"`
	OidcTokenEndpoint  string `yaml:"oidc-token-endpoint,omitempty"`
	Access             string `yaml:"access,omitempty"`
	Refresh            string `yaml:"refresh,omitempty"`
	ExpiresAt          int64  `yaml:"expires-at,omitempty"`

	// AllowInsecureHTTP lets this context send its credentials over plain http to a host that is not this
	// machine (#2004): lm config set-context NAME --allow-insecure-http.
	AllowInsecureHTTP bool `yaml:"allow-insecure-http,omitempty"`

	// stored is the context's own server, kept by Resolve when LUMNIK_URL or --server overrides Server: the
	// stored session belongs to it, and is not sent elsewhere (#2004).
	stored string
}

type Config struct {
	CurrentContext string             `yaml:"current-context"`
	Contexts       map[string]Context `yaml:"contexts"`
}

// DefaultPath returns ~/.lm/config.yaml
func DefaultPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".lm", "config.yaml"), nil
}

// LoadFromFile reads and parses a config file. Returns an empty Config if the
// file does not exist (so first-run is graceful).
func LoadFromFile(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return &Config{Contexts: map[string]Context{}}, nil
		}
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	ownerOnly(path)
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	if cfg.Contexts == nil {
		cfg.Contexts = map[string]Context{}
	}
	return &cfg, nil
}

// Save writes the config to path, creating parent directories with mode 0700.
func (c *Config) Save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return err
	}
	return os.Chmod(path, 0o600) // WriteFile sets the mode only on a file it creates
}

// ownerOnly tightens the config to 0600: it holds tokens, and a file created by hand or by an older lm may be
// readable by others (#2004). Best-effort: a file lm cannot chmod is one it does not own.
func ownerOnly(path string) {
	fi, err := os.Stat(path)
	if err != nil || fi.Mode().Perm()&0o077 == 0 {
		return
	}
	if os.Chmod(path, 0o600) == nil {
		fmt.Fprintf(os.Stderr, "lm: %s was readable by others (%o); now owner-only (600)\n", path, fi.Mode().Perm())
	}
}

// Resolve returns the Context matching the given name, or the current-context
// if name is empty. Env vars LUMNIK_URL / LUMNIK_TOKEN override fields.
func (c *Config) Resolve(name string) (Context, error) {
	if name == "" {
		name = c.CurrentContext
	}
	if name == "" {
		return Context{}, fmt.Errorf("no context specified and no current-context set")
	}
	ctx, ok := c.Contexts[name]
	if !ok {
		return Context{}, fmt.Errorf("context %q not found", name)
	}
	ctx.stored = ctx.Server
	if v := os.Getenv("LUMNIK_URL"); v != "" {
		ctx.Server = v
	}
	if v := os.Getenv("LUMNIK_TOKEN"); v != "" {
		ctx.Token = v
	}
	return ctx, nil
}

// ValidID reports whether v can be sent as X-Tenant-ID / X-User-ID: empty (not sent), or a positive integer.
func ValidID(v string) bool {
	if v == "" {
		return true
	}
	n, err := strconv.ParseInt(v, 10, 64)
	return err == nil && n > 0
}

// CheckIdentity refuses a dev-bypass identity (Tenant / User, sent as X-Tenant-ID / X-User-ID) on a context whose
// server is a remote https hub: no dev-bypass hub runs there, and a production context must not carry one (#1610).
// Checked on the effective server — after --server and LUMNIK_URL — wherever a client is built.
func CheckIdentity(ctx Context) error {
	if ctx.Tenant == "" && ctx.User == "" {
		return nil
	}
	if host, remote := RemoteHTTPS(ctx.Server); remote {
		return fmt.Errorf("a dev-bypass identity (--tenant/--user) is for a local hub; %s is a remote https server", host)
	}
	return nil
}

// RemoteHTTPS reports whether server is an https URL whose host is not local, and returns the host alone — the URL
// may carry credentials. Plain http and local names (localhost, a loopback address, *.local, *.localhost, *.test,
// with or without a fully-qualified name's trailing dot) are not remote: make k8s-up and its gateway variant run there.
func RemoteHTTPS(server string) (string, bool) {
	u, err := url.Parse(server)
	if err != nil || u.Scheme != "https" {
		return "", false
	}
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	// *.local counts as local here (a dev-bypass hub on the LAN), unlike onThisMachine's clear-text rule.
	return host, !onThisMachine(host) && !strings.HasSuffix(host, ".local")
}

// StoredServer is the server written in the context, before LUMNIK_URL or --server (#2004).
func (c Context) StoredServer() string { return c.stored }

// Foreign reports whether the effective server is another origin than the context's own: its stored session
// (token, access, refresh) is then not sent there (#2004). A context not built by Resolve is never foreign.
func (c Context) Foreign() bool { return c.stored != "" && !SameOrigin(c.stored, c.Server) }

// SameOrigin compares two URLs by scheme, host (case-insensitive) and effective port; path and trailing slash
// are ignored (#2004).
func SameOrigin(a, b string) bool {
	oa, okA := origin(a)
	ob, okB := origin(b)
	return okA && okB && oa == ob
}

func origin(raw string) (string, bool) {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "", false
	}
	scheme := strings.ToLower(u.Scheme)
	port := u.Port()
	if port == "" {
		switch scheme {
		case "https":
			port = "443"
		case "http":
			port = "80"
		}
	}
	return scheme + "://" + net.JoinHostPort(strings.TrimSuffix(strings.ToLower(u.Hostname()), "."), port), true
}

// onThisMachine reports whether host never leaves this machine: localhost, a loopback address, *.localhost,
// *.test. Not *.local: mDNS resolves it on the LAN (#2004).
func onThisMachine(host string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".test") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ClearText reports whether a credential sent to raw would cross the network unencrypted: plain http to a host
// that is not this machine (#2004).
func ClearText(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && strings.EqualFold(u.Scheme, "http") && !onThisMachine(u.Hostname())
}

// CheckClearText refuses to send what (a token, a refresh token) to target in clear text, unless the context
// allows it (allow-insecure-http) or LUMNIK_ALLOW_HTTP=1 is set for this run (#2004).
func CheckClearText(ctx Context, name, what, target string) error {
	if !ClearText(target) || ctx.AllowInsecureHTTP || os.Getenv("LUMNIK_ALLOW_HTTP") == "1" {
		return nil
	}
	if name == "" {
		name = "NAME"
	}
	target = Origin(target)
	return &ClearTextError{fmt.Sprintf("refusing to send %s to %s in clear text: use https (the TLS proxy), "+
		"or allow this context: lm config set-context %s --allow-insecure-http", what, target, name)}
}

// Origin is raw's scheme://host[:port], for a message: no userinfo, path or query, which may carry credentials (#2004).
func Origin(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "(an unreadable URL)"
	}
	return u.Scheme + "://" + u.Host
}

// ClearTextError is CheckClearText's refusal: a caller tells it from a network failure with errors.As.
type ClearTextError struct{ msg string }

func (e *ClearTextError) Error() string { return e.msg }
