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
