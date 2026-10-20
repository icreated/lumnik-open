package client

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestSSE_StreamsEvents(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		flusher, _ := w.(http.Flusher)
		fmt.Fprintf(w, "data: {\"topic\":\"connector.run.started\",\"runId\":\"r1\"}\n\n")
		flusher.Flush()
		fmt.Fprintf(w, "data: {\"topic\":\"connector.run.completed\",\"runId\":\"r1\"}\n\n")
		flusher.Flush()
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "x"})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	events := make(chan SSEEvent, 10)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = c.SSEStream(ctx, "/api/platform/events/stream", []string{"connector.run.*"}, events)
	}()

	count := 0
loop:
	for count < 2 {
		select {
		case ev := <-events:
			if len(ev.Data) == 0 {
				continue
			}
			count++
		case <-ctx.Done():
			break loop
		}
	}
	cancel()
	wg.Wait()
	if count < 2 {
		t.Errorf("expected at least 2 events, got %d", count)
	}
}

func TestSSE_SurvivesJSONClientTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		flusher, _ := w.(http.Flusher)
		fmt.Fprintf(w, "data: {\"seq\":1}\n\n")
		flusher.Flush()
		time.Sleep(1 * time.Second) // longer than the json client's shortened timeout below
		fmt.Fprintf(w, "data: {\"seq\":2}\n\n")
		flusher.Flush()
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "x"})
	// Shorten the JSON client's Timeout well below the server's pause. SSEStream
	// must NOT die at this mark — a long-lived stream is bounded by the caller's
	// ctx, not by the request/response timeout meant for regular JSON calls.
	c.http.Timeout = 500 * time.Millisecond

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	events := make(chan SSEEvent, 10)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = c.SSEStream(ctx, "/api/platform/events/stream", nil, events)
	}()

	count := 0
loop:
	for count < 2 {
		select {
		case ev := <-events:
			if len(ev.Data) == 0 {
				continue
			}
			count++
		case <-ctx.Done():
			break loop
		}
	}
	cancel()
	wg.Wait()
	if count < 2 {
		t.Errorf("expected 2 events (stream must survive past the json client's 500ms timeout), got %d", count)
	}
}

func TestSSE_StreamWithReconnect_RetriesOnEOF(t *testing.T) {
	attempts := 0
	var mu sync.Mutex
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		attempts++
		current := attempts
		mu.Unlock()
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, _ := w.(http.Flusher)
		fmt.Fprintf(w, "data: {\"attempt\":%d}\n\n", current)
		flusher.Flush()
		// close immediately to force reconnect
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	events := make(chan SSEEvent, 10)

	go c.SSEStreamWithReconnect(ctx, "/api/platform/events/stream", []string{"x"}, events, 50*time.Millisecond, nil)

	seen := 0
loop:
	for {
		select {
		case <-events:
			seen++
			if seen >= 2 {
				break loop
			}
		case <-ctx.Done():
			break loop
		}
	}
	cancel()
	if seen < 2 {
		t.Errorf("expected at least 2 events from reconnect attempts, got %d", seen)
	}
}

// The reconnect loop reports what the CONNECTION does — open on the 200, the error when it ends —
// so the TUI badge stops inferring liveness from traffic (#1108).
func TestSSEReconnect_reportsOpenAndRefusal(t *testing.T) {
	var refuse atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if refuse.Load() {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.(http.Flusher).Flush()
		refuse.Store(true) // the next attempt is refused
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	statuses := make(chan error, 16)
	go c.SSEStreamWithReconnect(ctx, "/s", nil, make(chan SSEEvent, 1), 10*time.Millisecond,
		func(err error) { statuses <- err })

	if err := <-statuses; err != nil {
		t.Fatalf("first status = %v, want nil (open)", err)
	}
	for {
		select {
		case err := <-statuses:
			if err != nil && strings.Contains(err.Error(), "403") {
				return
			}
		case <-ctx.Done():
			t.Fatal("the 403 was never reported")
		}
	}
}
