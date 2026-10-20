package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestIntegrationAdd_readsSecretFromStdinAndStatesTheContract(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(201)
		_, _ = w.Write([]byte(`{"id":7,"name":"n8n-prod","targetUrl":"https://n8n.local/wh","eventTypes":["decision.*"],"enabled":true}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetIn(strings.NewReader("s3cret-from-stdin\n"))
	cmd.SetArgs([]string{"integration", "add", "n8n-prod",
		"--url", "https://n8n.local/wh", "--events", "decision.*", "--secret-stdin"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["secret"] != "s3cret-from-stdin" {
		t.Errorf("secret must travel in the body, read from stdin; got %v", body["secret"])
	}
	got := out.String()
	for _, want := range []string{"registered: n8n-prod (id 7)", "from now on"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}

func TestIntegrationList_rendersTheRegistrations(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":7,"name":"n8n-prod","targetUrl":"https://n8n.local/wh",
			"eventTypes":["decision.*","annotation.decision.acked"],"enabled":true}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"integration", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	got := out.String()
	for _, want := range []string{"NAME", "n8n-prod", "decision.*,annotation.decision.acked", "true"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}

func TestIntegrationTest_failedDeliveryIsAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":false,"httpStatus":null,"error":"connection refused","durationMs":50}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	err := runRootErr(t, "integration", "test", "7")
	if err == nil || !strings.Contains(err.Error(), "connection refused") {
		t.Errorf("a failed delivery must surface as an error naming the cause; got %v", err)
	}
}

// runRootErr runs lm with args and returns its error, where runRoot fails the test on one.
func runRootErr(t *testing.T, args ...string) error {
	t.Helper()
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs(args)
	return cmd.Execute()
}

// integrationTestHub answers the test door with result and counts the get calls, the one that
// reads the target; getStatus is that call's status.
func integrationTestHub(t *testing.T, result string, getStatus int) *atomic.Int32 {
	t.Helper()
	var gets atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodGet && r.URL.Path == "/api/platform/integrations/7" {
			gets.Add(1)
			w.WriteHeader(getStatus)
			_, _ = w.Write([]byte(`{"id":7,"name":"chat-ops","targetUrl":"https://user:pw@hooks.example.com/path?k=v","eventTypes":["decision.*"],"enabled":true}`))
			return
		}
		_, _ = w.Write([]byte(result))
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
	return &gets
}

const failedDelivery = `{"ok":false,"httpStatus":502,"error":"HTTP 502 from https://user:pw@hooks.example.com/path?k=v: bad gateway","durationMs":50}`

// The hub's error text can quote the target URL, its credential included. A failed test prints it
// with the host only, as list and describe do (#1160); the target is read only on failure.
func TestIntegrationTest_aFailedDeliveryShowsTheTargetsHostOnly(t *testing.T) {
	gets := integrationTestHub(t, failedDelivery, http.StatusOK)

	err := runRootErr(t, "integration", "test", "7")
	if err == nil || !strings.Contains(err.Error(), "https://hooks.example.com") || !strings.Contains(err.Error(), "bad gateway") {
		t.Fatalf("a failed delivery must name the cause and the target's host; got %v", err)
	}
	for _, leak := range []string{"user", "pw@", "/path", "k=v"} {
		if strings.Contains(err.Error(), leak) {
			t.Errorf("the error shows %q of the target URL: %v", leak, err)
		}
	}
	if n := gets.Load(); n != 1 {
		t.Errorf("a failed test reads the target once; got %d reads", n)
	}
}

// Success costs nothing extra: the target is not read.
func TestIntegrationTest_aDeliveredTestDoesNotReadTheTarget(t *testing.T) {
	gets := integrationTestHub(t, `{"ok":true,"httpStatus":200,"durationMs":12}`, http.StatusOK)
	if got := runRoot(t, "integration", "test", "7"); !strings.Contains(got, "delivered in 12ms (HTTP 200)") {
		t.Fatalf("output: %s", got)
	}
	if n := gets.Load(); n != 0 {
		t.Errorf("a delivered test must not read the target; got %d reads", n)
	}
}

// Without the target URL the text cannot be scrubbed: it is withheld rather than printed raw.
func TestIntegrationTest_withoutTheTargetTheErrorTextIsWithheld(t *testing.T) {
	integrationTestHub(t, failedDelivery, http.StatusInternalServerError)

	err := runRootErr(t, "integration", "test", "7")
	if err == nil || !strings.Contains(err.Error(), "delivery failed") || !strings.Contains(err.Error(), "502") {
		t.Fatalf("a failed delivery is still an error naming its status; got %v", err)
	}
	for _, leak := range []string{"hooks.example.com", "user", "pw@", "/path", "k=v"} {
		if strings.Contains(err.Error(), leak) {
			t.Errorf("the error shows %q of the target URL: %v", leak, err)
		}
	}
}

// A webhook URL is often the credential itself: its user, path and query (#1158). The table — list
// and describe — shows the scheme and host only, as :integrations does (#1144).
func TestIntegrationListAndDescribe_tableShowsTheHostOnly(t *testing.T) {
	const item = `{"id":7,"name":"chat-ops","targetUrl":"https://bot:pa55@hooks.example.com/services/T0K3N/S3CR3T?key=k3y","eventTypes":["decision.*"],"enabled":true}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/7") {
			_, _ = w.Write([]byte(item))
			return
		}
		_, _ = w.Write([]byte(`[` + item + `]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	for _, args := range [][]string{{"integration", "list"}, {"integration", "describe", "7"}} {
		got := runRoot(t, args...)
		if !strings.Contains(got, "https://hooks.example.com") {
			t.Errorf("%v: the target's host is missing:\n%s", args, got)
		}
		for _, leak := range []string{"bot", "pa55", "T0K3N", "S3CR3T", "k3y", "/services"} {
			if strings.Contains(got, leak) {
				t.Errorf("%v: the table shows %q of the target URL:\n%s", args, leak, got)
			}
		}
	}
}

// -o json|yaml is the machine contract the integrator asked for explicitly: the hub's body, the
// full target URL included, unchanged (#1158).
func TestIntegrationList_jsonAndYamlKeepTheHubsTargetURL(t *testing.T) {
	const target = "https://hooks.example.com/services/T0K3N?key=k3y"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":7,"name":"chat-ops","targetUrl":"` + target + `","eventTypes":["decision.*"],"enabled":true}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	for _, format := range []string{"json", "yaml"} {
		if got := runRoot(t, "integration", "list", "-o", format); !strings.Contains(got, target) {
			t.Errorf("-o %s must keep the hub's target URL whole:\n%s", format, got)
		}
	}
}
