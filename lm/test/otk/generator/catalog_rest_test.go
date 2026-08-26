package generator

import (
	"strings"
	"testing"
)

func findRestVariant(t *testing.T, cat, name string) restVariant {
	t.Helper()
	for _, v := range restVariantsByCategory[cat] {
		if v.name == name {
			return v
		}
	}
	t.Fatalf("REST variant %s/%s not found", cat, name)
	return restVariant{}
}

func TestRestVariantsByCategory_coversAllFour(t *testing.T) {
	for _, cat := range []string{"logical", "weird", "textual", "hack"} {
		vs, ok := restVariantsByCategory[cat]
		if !ok || len(vs) == 0 {
			t.Errorf("restVariantsByCategory missing or empty for category %q", cat)
		}
	}
}

func TestRenderRest_baseline_hasRestEnvelope(t *testing.T) {
	m := restBaseline(1)
	yaml := renderRest(m)
	for _, want := range []string{
		"apiVersion: connectors.lumnik.io/v1",
		"kind: Connector",
		"connector_type: rest-generic",
		"spec:",
		"base_url:",
		"auth:",
		"endpoints:",
	} {
		if !strings.Contains(yaml, want) {
			t.Errorf("renderRest baseline missing %q:\n%s", want, yaml)
		}
	}
}

func TestRenderRest_noEndpoints_rendersEmptyList(t *testing.T) {
	m := restBaseline(1)
	m.endpoints = nil
	yaml := renderRest(m)
	if !strings.Contains(yaml, "endpoints: []") {
		t.Errorf("empty endpoints must render as 'endpoints: []':\n%s", yaml)
	}
}

func TestRestVariant_noEndpoints_setsEmptySlice(t *testing.T) {
	v := findRestVariant(t, "logical", "no-endpoints")
	m := restBaseline(1)
	v.mutate(&m, "")
	if len(m.endpoints) != 0 {
		t.Fatalf("no-endpoints: want empty slice, got %d endpoints", len(m.endpoints))
	}
}

func TestRestVariant_bearerNoTokenEnv_clearsTokenEnv(t *testing.T) {
	v := findRestVariant(t, "logical", "bearer-no-token-env")
	m := restBaseline(1)
	v.mutate(&m, "")
	if m.tokenEnv != "" {
		t.Fatalf("bearer-no-token-env: tokenEnv = %q, want empty", m.tokenEnv)
	}
	yaml := renderRest(m)
	if strings.Contains(yaml, "token_env") {
		t.Fatalf("bearer-no-token-env: manifest must not contain token_env:\n%s", yaml)
	}
}

func TestRestVariant_duplicateEndpointIds_producesTwoEndpoints(t *testing.T) {
	v := findRestVariant(t, "weird", "duplicate-endpoint-ids")
	m := restBaseline(1)
	v.mutate(&m, "")
	if len(m.endpoints) != 2 {
		t.Fatalf("duplicate-endpoint-ids: want 2 endpoints, got %d", len(m.endpoints))
	}
	if m.endpoints[0].id != m.endpoints[1].id {
		t.Fatal("duplicate-endpoint-ids: both endpoints must have the same id")
	}
}

func TestRestVariant_ssrfInternalIp_setsMetadataUrl(t *testing.T) {
	v := findRestVariant(t, "hack", "ssrf-internal-ip")
	m := restBaseline(1)
	v.mutate(&m, "http://169.254.169.254/latest/meta-data/")
	if !strings.Contains(m.baseURL, "169.254.169.254") {
		t.Fatalf("ssrf-internal-ip: baseURL = %q, want AWS metadata address", m.baseURL)
	}
}

func TestRestVariant_fileScheme_setsFileUrl(t *testing.T) {
	v := findRestVariant(t, "hack", "file-scheme")
	m := restBaseline(1)
	v.mutate(&m, "file:///etc/passwd")
	if !strings.HasPrefix(m.baseURL, "file://") {
		t.Fatalf("file-scheme: baseURL = %q, want file:// scheme", m.baseURL)
	}
}

func TestRestVariant_tokenEnvSentinel_setsOTKSecret(t *testing.T) {
	v := findRestVariant(t, "hack", "token-env-sentinel")
	m := restBaseline(1)
	v.mutate(&m, "OTK_SENTINEL_SECRET")
	if m.tokenEnv != "OTK_SENTINEL_SECRET" {
		t.Fatalf("token-env-sentinel: tokenEnv = %q, want OTK_SENTINEL_SECRET", m.tokenEnv)
	}
}
