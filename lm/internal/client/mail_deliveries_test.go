package client

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestMailDeliveryList_decodesTheRowsAndAsksForTheLimit(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		_, _ = w.Write([]byte(`[
			{"id":12,"ruleId":3,"recipient":"adv@example.com","eventType":"decision.litige-ouvert",
			 "status":"given up: send","attempts":3,"error":"MailSendException","at":"2026-11-08T09:12:04Z"}]`))
	}))
	t.Cleanup(srv.Close)

	c := New(config.Context{Server: srv.URL, Token: "static"})
	got, err := c.MailDeliveryList(200)
	if err != nil {
		t.Fatalf("MailDeliveryList: %v", err)
	}
	if gotPath != "/api/platform/notification-deliveries?limit=200" {
		t.Errorf("path = %q", gotPath)
	}
	if len(got) != 1 || got[0].Recipient != "adv@example.com" || got[0].Status != "given up: send" || got[0].Attempts != 3 {
		t.Errorf("row not decoded: %+v", got)
	}
}

func TestMailDeliveryReplay_postsToTheDeliveryAndReturnsItsStatus(t *testing.T) {
	var gotPath, gotMethod string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotMethod = r.URL.RequestURI(), r.Method
		_, _ = w.Write([]byte(`{"id":12,"status":"sent"}`))
	}))
	t.Cleanup(srv.Close)

	c := New(config.Context{Server: srv.URL, Token: "static"})
	status, err := c.MailDeliveryReplay(12)
	if err != nil {
		t.Fatalf("MailDeliveryReplay: %v", err)
	}
	if gotMethod != "POST" || gotPath != "/api/platform/notification-deliveries/12/replay" {
		t.Errorf("%s %s", gotMethod, gotPath)
	}
	if status != "sent" {
		t.Errorf("status = %q", status)
	}
}
