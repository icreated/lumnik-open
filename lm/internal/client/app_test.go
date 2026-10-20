package client

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestAppList_getsTheRegistryAndKeepsTheBodyVerbatim(t *testing.T) {
	const body = `[{"name":"stock","scope":"stock","futureField":1}]`
	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	got, err := New(config.Context{Server: srv.URL}).AppList()
	if err != nil {
		t.Fatal(err)
	}
	if gotMethod != http.MethodGet || gotPath != "/api/apps" {
		t.Errorf("called %s %s, want GET /api/apps", gotMethod, gotPath)
	}
	if string(got) != body {
		t.Errorf("the hub's JSON must reach the caller untouched (a field this lm does not know included), got %s", got)
	}
}

// The typed list the CLI table and :apps share (#1140): one tagged struct, so a hub field rename
// moves both.
func TestDecodeApps_readsTheRegistryIntoAppInfo(t *testing.T) {
	got, err := DecodeApps([]byte(`[{"name":"desk","title":"Desk","scope":"clients","icon":"box","accentColor":"#fff",` +
		`"backgroundColor":"#000","default":true,"tabs":["chat"],"futureField":1}]`))
	if err != nil {
		t.Fatal(err)
	}
	want := []AppInfo{{Name: "desk", Title: "Desk", Scope: "clients", Icon: "box", AccentColor: "#fff",
		BackgroundColor: "#000", Default: true, Tabs: []string{"chat"}}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("DecodeApps = %+v, want %+v", got, want)
	}
}

func TestDecodeApps_aBodyThatIsNotAListIsAnError(t *testing.T) {
	if _, err := DecodeApps([]byte(`{"name":"desk"}`)); err == nil {
		t.Error("an object is not a list of apps")
	}
}
