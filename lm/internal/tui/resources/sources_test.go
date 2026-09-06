package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

// The describe panel's "at:" line (last run) renders local, second precision — not the raw
// RFC3339 the hub sends. Expected value computed, not hardcoded, so this does not depend on
// the machine running it being on any particular timezone.
func TestSourcesDescribe_lastRunAtIsLocal(t *testing.T) {
	lastRun, err := time.Parse(time.RFC3339, "2026-08-15T09:12:04Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"id":"c1","tenantId":1,"name":"ad_user","typeId":"csv-file",
			"enabled":true,"status":"Completed","lastRunAt":"2026-08-15T09:12:04Z"}`))
	}))
	defer srv.Close()

	s := NewSources(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
	s.items = []types.Connector{{ID: "c1", Name: "ad_user"}}
	s.table.Select(1, 0)
	_, body, ok := s.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	want := "at:        " + lastRun.In(time.Local).Format("2006-01-02 15:04:05")
	if !strings.Contains(body, want) {
		t.Errorf("describe body missing %q:\n%s", want, body)
	}
	if strings.Contains(body, "2026-08-15T09:12:04Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}
