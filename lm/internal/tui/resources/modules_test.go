package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

func TestModulesPage_ConstructsAndFetches(t *testing.T) {
	c := client.New(config.Context{Server: "http://127.0.0.1:65500"})
	p := NewModules(nil, c)
	if p == nil {
		t.Fatal("nil page")
	}
	p.Refresh()
}

func TestModulesPage_showsWhyAModuleIsInactive(t *testing.T) {
	c := client.New(config.Context{Server: "http://127.0.0.1:65500"})
	p := NewModules(nil, c)
	p.renderList([]types.Module{
		{Name: "core", Version: "0.1.0", Schema: "Platform", Status: "ACTIVE"},
		{Name: "rag", Version: "0.1.0", Schema: "Platform RAG", Status: "INACTIVE", Reason: "missing_dependency:llm"},
	}, nil)
	if got := p.table.GetCell(0, 4).Text; got != "REASON" {
		t.Errorf("header[4] = %q, want REASON", got)
	}
	if got := p.table.GetCell(2, 4).Text; got != "missing_dependency:llm" {
		t.Errorf("rag reason = %q", got)
	}
	if got := p.table.GetCell(1, 4).Text; got != "" {
		t.Errorf("an active module carries no reason, got %q", got)
	}
}
