package client

import (
	"encoding/json"
	"net/url"

	"gopkg.in/yaml.v3"
)

// AppApply sends a raw YAML kind:App manifest to the hub.
// Content-Type: application/yaml. Returns the raw JSON response.
func (c *Client) AppApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/apps/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}

// AppList returns the scope-bound registry (GET /api/apps, lm_user) as the hub sent it: the raw JSON, so
// a field this lm does not know yet still reaches -o json / -o yaml.
func (c *Client) AppList() (json.RawMessage, error) {
	var raw json.RawMessage
	err := c.GET("/api/apps", &raw)
	return raw, err
}

// AppInfo is one declared kind:App, as GET /api/apps lists it — the one decoding of that list the
// CLI table and :apps share (#1140).
type AppInfo struct {
	Name            string   `json:"name"`
	Title           string   `json:"title"`
	Scope           string   `json:"scope"`
	Icon            string   `json:"icon"`
	AccentColor     string   `json:"accentColor"`
	BackgroundColor string   `json:"backgroundColor"`
	Default         bool     `json:"default"`
	Tabs            []string `json:"tabs"` // empty: the app inherits every tab the hub offers
}

// DecodeApps reads AppList's raw body into AppInfo — for a table; -o json / -o yaml print the raw
// body itself.
func DecodeApps(raw json.RawMessage) ([]AppInfo, error) {
	var apps []AppInfo
	if err := json.Unmarshal(raw, &apps); err != nil {
		return nil, err
	}
	return apps, nil
}

// AppGet reads one declared app (GET /api/apps/{name}, lm_user, scope-bound).
func (c *Client) AppGet(name string) (AppInfo, error) {
	var a AppInfo
	err := c.GET("/api/apps/"+url.PathEscape(name), &a)
	return a, err
}

// AppManifest is name's applyable kind:App manifest. The hub keeps no manifest text, but a kind:App
// is exactly the row's fields, so it is rebuilt from them (#1176) — `lm app get` and :apps' e.
func (c *Client) AppManifest(name string) ([]byte, error) {
	a, err := c.AppGet(name)
	if err != nil {
		return nil, err
	}
	return a.Manifest()
}

// Manifest renders the app as AppManifestParser (lumnik-hub) reads a kind:App, in the :new
// template's key order — keep it in step with that parser's SPEC_KEYS / THEME_KEYS: a key the hub
// gains and this drops is erased by a get → apply. No tabs is no key: the app inherits every tab the hub offers, and the hub
// refuses an empty list.
func (a AppInfo) Manifest() ([]byte, error) {
	type theme struct {
		AccentColor     string `yaml:"accentColor,omitempty"`
		BackgroundColor string `yaml:"backgroundColor,omitempty"`
	}
	type spec struct {
		Title   string   `yaml:"title"`
		Scope   string   `yaml:"scope"`
		Icon    string   `yaml:"icon"`
		Theme   theme    `yaml:"theme"`
		Default bool     `yaml:"default"`
		Tabs    []string `yaml:"tabs,omitempty"`
	}
	return yaml.Marshal(struct {
		Kind       string            `yaml:"kind"`
		APIVersion string            `yaml:"apiVersion"`
		Metadata   map[string]string `yaml:"metadata"`
		Spec       spec              `yaml:"spec"`
	}{"App", "apps.lumnik.io/v1", map[string]string{"name": a.Name},
		spec{a.Title, a.Scope, a.Icon, theme{a.AccentColor, a.BackgroundColor}, a.Default, a.Tabs}})
}

// AppDelete removes one declared app for good.
func (c *Client) AppDelete(name string) error {
	return c.DELETE("/api/apps/" + url.PathEscape(name))
}
