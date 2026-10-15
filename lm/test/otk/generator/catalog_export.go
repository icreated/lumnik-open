package generator

import (
	"fmt"
	"strings"
)

// The export lane (#684) holds the two export surfaces to their public contracts through the
// lm binary: the managed kind:Export doors (`lm export apply|list|get|run|status`) and the
// one-shot canonical CSV download (`lm entity export`). Sub-typed like entity and process —
// managed vs oneshot — and scripted like entityres: each case is a short chain of steps over
// one fixture, not one request and one status.
//
// Every case targets the harness-seeded otk_customer entity (seed_entity.go, scope otk). The
// destination of the managed fixtures is an SFTP port nothing listens on (127.0.0.1:1), so a
// run fails at publication deterministically on any hub, with no server to provision; only
// managed/delivers needs a real destination, and it is dormant unless one is declared.

// ExportSentinel is the credential every export case plants — as a stored secret the
// destination reads, inline in a refused manifest, in an endpoint's userinfo. High-entropy, so
// its appearance in any lm output is a leak and never a coincidence.
const ExportSentinel = "OTK-EXPORT-SENTINEL-q8Vd2LxR4w"

// ExportPasswordSecret names the lm secret the unreachable destination authenticates with; the
// harness stores ExportSentinel under it before a run.
const ExportPasswordSecret = "OTK_EXPORT_PASSWORD"

// ExportEntity is the canonical entity every export case reads.
const ExportEntity = "otk_customer"

// exportVariant is one export case. manifest is empty where the case builds none (oneshot) or
// builds it at run time from its declared environment (managed/delivers).
type exportVariant struct {
	sub        string // "managed" | "oneshot"
	name       string
	exportName string // metadata.name the manifest declares, lower-case as the hub stores it
	manifest   string
	needsSeed  bool // resolves otk_customer: dormant without OTK_DB_DSN
	needsKey   bool // forges a scope-bound identity: dormant without real-auth
	needsSFTP  bool // delivers to a real server: dormant without OTK_EXPORT_SFTP_*
	steps      []string
	feature    string
}

const exportSalt = uint64(0xE2907)

// exportManifest renders a kind:Export manifest from its spec and destination lines. Each
// refusal case passes the valid fixture's lines with exactly one field changed.
func exportManifest(name string, spec, destination []string) string {
	var b strings.Builder
	b.WriteString("apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: " + name + "\nspec:\n")
	for _, l := range spec {
		b.WriteString("  " + l + "\n")
	}
	b.WriteString("  destination:\n")
	for _, l := range destination {
		b.WriteString("    " + l + "\n")
	}
	return b.String()
}

// validExportSpec is otk_customer's canonical columns, in the shape the apply door accepts.
func validExportSpec() []string {
	return []string{
		"scope: otk",
		"source:",
		"  entity: " + ExportEntity,
		"columns: [id, name]",
		"order: [id]",
		"format:",
		"  kind: csv",
		"mode: snapshot",
	}
}

// unreachableSftp is a destination nothing answers: port 1 on the hub's own loopback refuses the
// connection, so a run gets as far as publication and no further.
func unreachableSftp() []string {
	return []string{
		"kind: sftp",
		"host: 127.0.0.1",
		"port: 1",
		"user: otk",
		"path: /otk/customers-${timestamp}.csv",
		"password_env: " + ExportPasswordSecret,
		`host_key_fingerprint: "SHA256:otk-never-reached"`,
	}
}

func replaceLine(lines []string, prefix, with string) []string {
	out := make([]string, 0, len(lines))
	for _, l := range lines {
		if strings.HasPrefix(l, prefix) {
			l = with
		}
		out = append(out, l)
	}
	return out
}

// refusedApply is the step chain every invalid-manifest case shares.
var refusedApply = []string{
	`    When I apply its export manifest with lm`,
	`    Then the export apply is refused and nothing is stored`,
	`    And no lm output echoed the export sentinel`,
}

func refusal(name, feature string, needsSeed bool, spec, destination []string) exportVariant {
	exportName := "otk-export-bad-" + name
	return exportVariant{
		sub: "managed", name: name + "-refused", exportName: exportName,
		manifest:  exportManifest(exportName, spec, destination),
		needsSeed: needsSeed,
		steps:     refusedApply,
		feature:   feature,
	}
}

// wrongAPIVersion is the one refusal outside spec: the valid fixture under apps.lumnik.io/v2.
func wrongAPIVersion(spec, dest []string) exportVariant {
	v := refusal("wrong-api-version", "kind:Export enforces its apiVersion", false, spec, dest)
	v.manifest = strings.Replace(v.manifest, "apps.lumnik.io/v1", "apps.lumnik.io/v2", 1)
	return v
}

func exportVariants() []exportVariant {
	spec, dest := validExportSpec(), unreachableSftp()
	return []exportVariant{
		{
			sub: "managed", name: "roundtrip", exportName: "otk-export-unreachable",
			manifest:  exportManifest("otk-export-unreachable", spec, dest),
			needsSeed: true,
			steps: []string{
				`    When I apply its export manifest with lm`,
				`    Then the export apply succeeds and lm lists and gets it`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "a valid export applies, is listed, and reads back as its stored manifest",
		},
		{
			sub: "managed", name: "destination-failure", exportName: "otk-export-unreachable",
			manifest:  exportManifest("otk-export-unreachable", spec, dest),
			needsSeed: true,
			steps: []string{
				`    When I apply its export manifest with lm`,
				`    And I run the applied export with lm and wait for its outcome`,
				`    Then the export run ended Failed with failure code "publication"`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "a destination that refuses the upload ends the run Failed with a coded reason, never Completed, never echoing its credential",
		},
		{
			sub: "managed", name: "inline-credential-refused", exportName: "otk-export-bad-inline-credential",
			manifest: exportManifest("otk-export-bad-inline-credential", spec,
				replaceLine(dest, "password_env:", "password: "+ExportSentinel)),
			steps:   refusedApply,
			feature: "an inline credential is refused by field name, and the refusal never echoes its value",
		},
		refusal("unknown-key", "an unknown spec key is refused, not ignored", false,
			append(append([]string{}, spec...), "compression: gzip"), dest),
		wrongAPIVersion(spec, dest),
		refusal("reserved-column", "an internal column cannot be exported", false,
			replaceLine(spec, "columns:", "columns: [id, _entity_id]"), dest),
		refusal("unknown-column", "a column the entity does not declare is refused at apply, not at run", true,
			replaceLine(spec, "columns:", "columns: [id, otk_ghost_column]"), dest),
		refusal("bad-schedule", "an impossible cron schedule is refused at apply", false,
			append(append([]string{}, spec...), `schedule: "61 * * * *"`), dest),
		refusal("foreign-destination-field", "an S3 field on an SFTP destination is refused, not silently dropped", false,
			spec, append(append([]string{}, dest...), "bucket: otk-bucket")),
		{
			sub: "managed", name: "url-credential-redacted", exportName: "otk-export-leaky-endpoint",
			manifest: exportManifest("otk-export-leaky-endpoint", spec, []string{
				"kind: s3",
				"bucket: otk-bucket",
				"filename: customers-${date}.csv",
				"endpoint: https://otk:" + ExportSentinel + "@127.0.0.1:1",
			}),
			needsSeed: true,
			steps: []string{
				`    When I apply its export manifest with lm`,
				`    Then lm export get masks the endpoint credential`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "credentials in a destination URL never reach the terminal when the manifest is read back",
		},
		{
			sub: "managed", name: "scope-refusal", exportName: "otk-export-scoped",
			manifest:  exportManifest("otk-export-scoped", spec, dest),
			needsSeed: true, needsKey: true,
			steps: []string{
				`    When I apply its export manifest with lm`,
				`    And a scope-bound identity lacking scope "otk" reads and runs the applied export with lm`,
				`    Then lm reported the uniform scope refusal`,
				`    And no export run was recorded by the refused identity`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "an identity bound to another scope can neither read nor run the export, and starts nothing",
		},
		{
			sub: "managed", name: "delivers", exportName: "otk-export-delivers",
			needsSeed: true, needsSFTP: true,
			steps: []string{
				`    When I apply its export manifest with lm`,
				`    And I run the applied export with lm and wait for its outcome`,
				`    Then the export run ended Completed with a receipt`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "a run to a reachable destination ends Completed with its receipt",
		},
		{
			sub: "oneshot", name: "exact-bytes-to-file",
			needsSeed: true,
			steps: []string{
				`    When I download entity "otk_customer" with lm to a new file`,
				`    Then the download holds the hub's exact snapshot bytes`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "the one-shot download lands the hub's exact bytes under the final name and nothing else",
		},
		{
			sub: "oneshot", name: "exact-bytes-to-stdout",
			needsSeed: true,
			steps: []string{
				`    When I download entity "otk_customer" with lm to stdout`,
				`    Then the download holds the hub's exact snapshot bytes`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "-o - streams the hub's exact bytes, unfiltered",
		},
		{
			sub: "oneshot", name: "existing-file-refused",
			steps: []string{
				`    When I download entity "otk_customer" with lm to an existing file`,
				`    Then lm refused the download and the existing file is untouched`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "an existing file is never overwritten without --force",
		},
		{
			sub: "oneshot", name: "unknown-entity-no-artifact",
			steps: []string{
				`    When I download entity "otk_ghost_entity" with lm to a new file`,
				`    Then lm refused the download and left no file behind`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "a refused download leaves no file and no partial file",
		},
		{
			sub: "oneshot", name: "scope-refusal-no-artifact",
			needsSeed: true, needsKey: true,
			steps: []string{
				`    When a scope-bound identity lacking scope "otk" downloads entity "otk_customer" with lm to a new file`,
				`    Then lm reported the uniform scope refusal`,
				`    And lm refused the download and left no file behind`,
				`    And no lm output echoed the export sentinel`,
			},
			feature: "a scope refusal passes through lm and produces no artifact",
		},
	}
}

// ExportCase is what the step layer needs of one export case.
type ExportCase struct {
	CaseID     CaseID
	ExportName string
	Manifest   string
	NeedsSeed  bool
	NeedsKey   bool
	NeedsSFTP  bool
}

// ExportCaseByName resolves "managed/roundtrip"-shaped names (the Given line's text).
func ExportCaseByName(name string) (ExportCase, bool) {
	for _, v := range exportVariants() {
		if v.sub+"/"+v.name == name {
			return ExportCase{
				CaseID: exportCaseID(v.sub, v.name), ExportName: v.exportName, Manifest: v.manifest,
				NeedsSeed: v.needsSeed, NeedsKey: v.needsKey, NeedsSFTP: v.needsSFTP,
			}, true
		}
	}
	return ExportCase{}, false
}

// ExportScenario holds the generated scenario for one seed.
type ExportScenario struct {
	Seed    int64
	CaseID  CaseID
	Variant string
	Feature string
}

// GenerateExport deterministically selects one export case from a seed.
func GenerateExport(seed int64) ExportScenario {
	h := splitmix64(uint64(seed) ^ exportSalt)
	variants := exportVariants()
	v := variants[int(splitmix64(h)%uint64(len(variants)))]
	return ExportScenario{
		Seed:    seed,
		CaseID:  exportCaseID(v.sub, v.name),
		Variant: v.sub + "/" + v.name,
		Feature: renderExportFeature(seed, v),
	}
}

func renderExportFeature(seed int64, v exportVariant) string {
	body := fmt.Sprintf("    Given the export case %q is prepared [seed=%d]\n", v.sub+"/"+v.name, seed)
	for _, line := range v.steps {
		body += line + "\n"
	}
	return fmt.Sprintf("Feature: export %s/%s [seed=%d]\n  Scenario: %s\n%s", v.sub, v.name, seed, v.feature, body)
}
