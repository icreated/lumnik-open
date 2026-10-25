// Package newkind is the :new picker's registry: each kind ships the REAL doc
// example as its template — the transclusion philosophy compiled into the binary.
// The sync test guards the copies against drift from their canonical files.
package newkind

import (
	_ "embed"
	"sync"

	"gopkg.in/yaml.v3"
)

//go:embed templates/csv-file.yaml
var csvFile string

//go:embed templates/jdbc-generic.yaml
var jdbcGeneric string

//go:embed templates/rest-generic.yaml
var restGeneric string

//go:embed templates/graphql.yaml
var graphql string

//go:embed templates/soap.yaml
var soap string

//go:embed templates/kafka.yaml
var kafka string

//go:embed templates/entity.yaml
var entity string

//go:embed templates/process.yaml
var process string

//go:embed templates/workflow.yaml
var workflow string

//go:embed templates/app.yaml
var app string

//go:embed templates/view.yaml
var view string

//go:embed templates/export.yaml
var export string

//go:embed templates/searchindex.yaml
var searchIndex string

// KindTemplate is one picker row: a kind, its living example, and where to
// observe the result after apply.
type KindTemplate struct {
	Kind     string // display name in the picker
	Doc      string // one-line description shown in the describe panel
	Template string // the living example (embedded copy of the canonical file)
	Source   string // repo-relative path of the canonical file (sync test + describe)
	Observe  string // page to switch to after a successful apply
}

// connectorTypes caches ConnectorType per kind: a template is a compiled-in constant, and the :new
// describe panel asks on every arrow key.
var connectorTypes sync.Map

// ConnectorType is a connector template's own metadata.connector_type — graphql and soap are request
// shapes of rest-generic, not types of their own. "" for any other kind.
func (k KindTemplate) ConnectorType() string {
	if t, ok := connectorTypes.Load(k.Kind); ok {
		return t.(string)
	}
	var m struct {
		Kind     string `yaml:"kind"`
		Metadata struct {
			ConnectorType string `yaml:"connector_type"`
		} `yaml:"metadata"`
	}
	t := ""
	if yaml.Unmarshal([]byte(k.Template), &m) == nil && m.Kind == "Connector" {
		t = m.Metadata.ConnectorType
	}
	connectorTypes.Store(k.Kind, t)
	return t
}

func All() []KindTemplate {
	return []KindTemplate{
		{"csv-file", "CSV files: local, SFTP, S3", csvFile, "docs/connectors/csv/10-local-simple.yaml", "sources"},
		{"jdbc-generic", "SQL databases (watermark/full)", jdbcGeneric, "lumnik-hub/src/test/resources/validator/jdbc-multi-table-valid.yaml", "sources"},
		{"rest-generic", "HTTP/JSON APIs (11 pagination presets)", restGeneric, "docs/connectors/rest/20-page-basic.yaml", "sources"},
		{"graphql", "GraphQL APIs (relay pagination)", graphql, "docs/connectors/graphql/10-relay-basic.yaml", "sources"},
		{"soap", "SOAP/legacy middlewares (Siebel QueryPage)", soap, "docs/connectors/soap/10-siebel-querypage.yaml", "sources"},
		{"kafka", "Inbound events (Kafka/Redpanda/Event Hubs)", kafka, "docs/connectors/kafka/10-kafka-json.yaml", "sources"},
		{"Entity", "Fuse sources into one canonical entity", entity, "docs/demo/wow-customer/customer-entity.yaml", "sources"},
		{"Process", "React to rows: emit decision.* events", process, "docs/demo/wow-customer/lyon-process.yaml", "inbox"},
		{"Workflow", "Declare a document lifecycle: states, aliases, transitions", workflow, "docs/demo/wow-customer/commande-workflow.yaml", "observations"},
		{"App", "A business app's identity: title, scope, icon, theme, tabs", app, "docs/apps/demo/apps/10-lumilait.yaml", "apps"},
		{"View", "A saved list over an entity, served to the PWA", view, "docs/demo/wow-customer/clients-view.yaml", "views"},
		{"Export", "A scheduled file of an entity, to SFTP or S3", export, "docs/apps/exports/10-sftp-daily.yaml", "entities"},
		{"SearchIndex", "Which columns of an entity a Search provider indexes", searchIndex, "docs/search/search-indexes/10-customers.yaml", "entities"},
	}
}
