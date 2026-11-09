package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNotifyAddAndList(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.Method {
		case "POST":
			_ = json.NewDecoder(r.Body).Decode(&body)
			w.WriteHeader(201)
			_, _ = w.Write([]byte(`{"id":3,"eventPattern":"decision.*","recipient":"adv@client.example","template":"event-notification","enabled":true}`))
		case "GET":
			_, _ = w.Write([]byte(`[{"id":3,"eventPattern":"decision.*","recipient":"adv@client.example","template":"event-notification","enabled":true}]`))
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"notify", "add", "--events", "decision.*", "--to", "adv@client.example"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["eventPattern"] != "decision.*" || body["recipient"] != "adv@client.example" {
		t.Errorf("unexpected body: %v", body)
	}
	if !strings.Contains(out.String(), "rule 3: decision.* → adv@client.example") {
		t.Errorf("output missing confirmation:\n%s", out.String())
	}

	cmd = NewRootCmd()
	out = new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"notify", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	for _, want := range []string{"RECIPIENT", "adv@client.example", "decision.*", "event-notification"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("list missing %q:\n%s", want, out.String())
		}
	}
}

func TestNotifyDeliveries_listsWhatTheHubDidWithTheMails(t *testing.T) {
	var path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.RequestURI()
		_, _ = w.Write([]byte(`[
			{"id":12,"ruleId":3,"recipient":"adv@example.com","eventType":"decision.litige-ouvert",
			 "status":"given up: send","attempts":3,"error":"MailSendException","at":"2026-11-08T09:12:04Z"},
			{"id":13,"ruleId":3,"recipient":"adv@example.com","eventType":"decision.litige-ouvert",
			 "status":"sent","attempts":1,"error":null,"at":"2026-11-08T09:13:00Z"}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"notify", "deliveries"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if path != "/api/platform/notification-deliveries?limit=200" {
		t.Errorf("GET went to %q", path)
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(lines) != 3 {
		t.Fatalf("want a header and two rows:\n%s", out.String())
	}
	for _, want := range []string{"ID", "RECIPIENT", "EVENT", "STATUS", "ATTEMPTS", "WHEN", "ERROR"} {
		if !strings.Contains(lines[0], want) {
			t.Errorf("header lacks %s: %q", want, lines[0])
		}
	}
	for _, want := range []string{"12", "adv@example.com", "decision.litige-ouvert", "given up: send", "MailSendException"} {
		if !strings.Contains(lines[1], want) {
			t.Errorf("the first row lacks %q: %q", want, lines[1])
		}
	}
}

func TestNotifyReplay_saysWhereTheDeliveryStands(t *testing.T) {
	var path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		_, _ = w.Write([]byte(`{"id":12,"status":"given up: send"}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"notify", "replay", "12"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if path != "/api/platform/notification-deliveries/12/replay" {
		t.Errorf("POST went to %q", path)
	}
	if got := strings.TrimSpace(out.String()); got != "delivery 12: given up: send" {
		t.Errorf("replay printed %q", got)
	}
}

func TestNotifyReplay_refusesAnIdThatIsNotANumber(t *testing.T) {
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"notify", "replay", "abc"})
	err := cmd.Execute()
	if err == nil || !strings.Contains(err.Error(), "lm notify deliveries") {
		t.Errorf("want a refusal pointing at lm notify deliveries, got %v", err)
	}
}
