package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestModulesPage_ConstructsAndFetches(t *testing.T) {
	c := client.New(config.Context{Server: "http://127.0.0.1:65500"})
	p := NewModules(c)
	if p == nil {
		t.Fatal("nil page")
	}
	p.Refresh()
}
