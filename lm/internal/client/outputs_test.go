package client

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestOutputList_decodesRowsAndNullStatus(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		_, _ = w.Write([]byte(`[
			{"id":91,"integrationId":3,"integration":"n8n-prod","eventType":"decision.dormant.commande",
			 "httpStatus":502,"error":"connect timed out","durationMs":3001,
			 "attemptedAt":"2026-08-15T09:12:04Z","succeeded":false},
			{"id":92,"integrationId":3,"integration":"n8n-prod","eventType":"data.row.changed",
			 "httpStatus":null,"error":"no route to host","durationMs":50,
			 "attemptedAt":"2026-08-15T09:13:00Z","succeeded":false}]`))
	}))
	t.Cleanup(srv.Close)

	c := New(config.Context{Server: srv.URL, Token: "static"})
	got, err := c.OutputList(200)
	if err != nil {
		t.Fatalf("OutputList: %v", err)
	}
	if gotPath != "/api/platform/deliveries?limit=200" {
		t.Errorf("path = %q", gotPath)
	}
	if len(got) != 2 {
		t.Fatalf("got %d rows", len(got))
	}
	if got[0].Integration != "n8n-prod" || got[0].HTTPStatus == nil || *got[0].HTTPStatus != 502 {
		t.Errorf("row not decoded: %+v", got[0])
	}
	if got[1].HTTPStatus != nil {
		t.Error("a delivery that never got a response must decode httpStatus as nil, not 0")
	}
}

func TestOutputReplay_postsToTheDeliveryPath(t *testing.T) {
	var gotPath, gotMethod string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.RequestURI(), r.Method
		_, _ = w.Write([]byte(`{"ok":true,"httpStatus":200}`))
	}))
	t.Cleanup(srv.Close)

	c := New(config.Context{Server: srv.URL, Token: "static"})
	if err := c.OutputReplay(91); err != nil {
		t.Fatalf("OutputReplay: %v", err)
	}
	if gotMethod != "POST" || gotPath != "/api/platform/deliveries/91/replay" {
		t.Errorf("%s %s", gotMethod, gotPath)
	}
}
