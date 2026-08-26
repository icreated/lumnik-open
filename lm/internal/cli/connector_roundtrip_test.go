package cli

import (
	"reflect"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/types"
	"gopkg.in/yaml.v3"
)

// `lm connector get NAME` marshals a types.Connector to YAML; `lm connector apply -f` unmarshals
// that YAML into map[string]any and posts it as JSON — and it reads payload["id"] to choose
// update-over-create, which only makes sense when the input came from `get`. So the round trip is
// the designed flow, and the keys the two ends speak have to be the same ones.
//
// The hub binds ConnectorCreateRequest / ConnectorAdminDto, whose fields are `typeId`, `tenantId`.
func TestConnectorGetApply_roundTripsTheKeysTheApiExpects(t *testing.T) {
	got := marshalAsGetDoes(t, types.Connector{
		ID: "11111111-1111-1111-1111-111111111111", TenantID: 1,
		Name: "stripe", TypeID: "rest-generic", Enabled: true,
		Config: map[string]any{"kind": "rest-generic"}, Tags: []string{"billing"},
	})

	for _, key := range []string{"typeId", "tenantId"} {
		if _, ok := got[key]; !ok {
			t.Errorf("apply would post no %q — the hub binds that field and would set it null.\nkeys: %v",
				key, keysOf(got))
		}
	}
}

// exactly what `connector get` writes and `connector apply` reads back.
func marshalAsGetDoes(t *testing.T, c types.Connector) map[string]any {
	t.Helper()
	data, err := yaml.Marshal(c)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	t.Logf("what `lm connector get` writes:\n%s", string(data))
	var payload map[string]any
	if err := yaml.Unmarshal(data, &payload); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	return payload
}

func keysOf(m map[string]any) string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	return strings.Join(ks, ", ")
}

// The rule behind the bug, not just the instance. Every type that `lm <thing> get` writes as YAML
// and `lm <thing> apply` posts back as JSON must speak ONE set of keys: yaml.v3 ignores `json:`
// tags and lowercases the field name instead, so `TypeID` went out as `typeid` while the hub bound
// `typeId` and set it null.
//
// types.Hook passes today by accident — every field is one word, so lowercasing matches. Add a
// multi-word field to it and this fails, which is the point.
func TestYamlKeysMatchJsonKeys_forEveryRoundTrippedType(t *testing.T) {
	for _, v := range []any{types.Connector{}, types.Hook{}} {
		rt := reflect.TypeOf(v)
		for i := 0; i < rt.NumField(); i++ {
			f := rt.Field(i)
			jsonKey := tagName(f.Tag.Get("json"))
			if jsonKey == "" || jsonKey == "-" {
				continue
			}
			yamlKey := tagName(f.Tag.Get("yaml"))
			if yamlKey == "" {
				yamlKey = strings.ToLower(f.Name) // yaml.v3's default
			}
			if yamlKey != jsonKey {
				t.Errorf("%s.%s: yaml writes %q, the API binds %q — a get→apply round trip drops it",
					rt.Name(), f.Name, yamlKey, jsonKey)
			}
		}
	}
}

func tagName(tag string) string {
	return strings.Split(tag, ",")[0]
}
