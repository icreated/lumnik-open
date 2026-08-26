package generator

// storeVariant is a fixed fixture for the saved-view store round-trip and
// apply-refusal acceptance scenarios (#140). Unlike viewVariants (picked
// randomly per seed for the adversarial render battery), this set is small
// and hand-written: the store lane's corpus is one fixed smoke feature
// (view_store_smoke.feature), the same house pattern as smoke_validate.feature
// for the validate lane — a sanity anchor, not a per-seed adversarial sweep.
type storeVariant struct {
	name         string
	manifestYAML string
}

// validStoreViewManifest resolves against the seeded otk scope card (same
// entity/table as validViewManifest in catalog_view.go), under a distinct
// metadata.name so the store round-trip never collides with the render lane's
// own "otk-view" manifest.
const validStoreViewManifest = `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-store-view
spec:
  scope: otk
  table: connector.v_otk_customer
  title: "OTK Store"
  columns: [id, name]
`

func storeVariants() []storeVariant {
	return []storeVariant{
		{
			name:         "store-roundtrip",
			manifestYAML: validStoreViewManifest,
		},
		{
			// "apply" is a RESERVED_NAMES literal (ViewApplyResource): a view so
			// named would list but never be fetchable at GET /api/views/{name}.
			name: "store-reserved-name",
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: apply
spec:
  scope: otk
  table: connector.v_otk_customer
  title: "Reserved"
  columns: [id]
`,
		},
		{
			// A scope with no SchemaCard row at all (never seeded, unlike "otk"):
			// the apply must 404 before any row is read, run-discovery-first.
			name: "store-unknown-scope",
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-ghost-apply
spec:
  scope: otk_ghost_scope
  table: connector.v_otk_customer
  title: "Ghost"
  columns: [id]
`,
		},
	}
}

// StoreVariantByName returns the manifest YAML for a store-lane fixture.
func StoreVariantByName(name string) (manifestYAML string, found bool) {
	for _, v := range storeVariants() {
		if v.name == name {
			return v.manifestYAML, true
		}
	}
	return "", false
}
