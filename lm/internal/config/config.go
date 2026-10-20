package config

import (
	"fmt"
	"os"
	"path/filepath"

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
	return os.WriteFile(path, data, 0o600)
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
	if v := os.Getenv("LUMNIK_URL"); v != "" {
		ctx.Server = v
	}
	if v := os.Getenv("LUMNIK_TOKEN"); v != "" {
		ctx.Token = v
	}
	return ctx, nil
}
