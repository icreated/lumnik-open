package client

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

const snapshotCSV = "id,name\n1,Acme\n2,\"Bé, Inc\"\n"

func snapshotClient(srv *httptest.Server) *Client {
	return New(config.Context{Server: srv.URL, Token: "x"})
}

func TestEntitySnapshotDownload_streamsTheExactBytes(t *testing.T) {
	var gotURI, gotAccept string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI, gotAccept = r.URL.EscapedPath(), r.Header.Get("Accept")
		w.Header().Set("Content-Type", "text/csv;charset=UTF-8")
		_, _ = io.WriteString(w, snapshotCSV)
	}))
	t.Cleanup(srv.Close)

	var buf bytes.Buffer
	n, err := snapshotClient(srv).EntitySnapshotDownload(context.Background(), "a/b",
		func() (io.Writer, error) { return &buf, nil })
	if err != nil {
		t.Fatalf("download: %v", err)
	}
	if gotURI != "/api/exports/entities/a%2Fb/download" {
		t.Errorf("name not escaped into one segment: %q", gotURI)
	}
	if !strings.HasPrefix(gotAccept, "text/csv") || !strings.Contains(gotAccept, "application/json") {
		t.Errorf("Accept = %q", gotAccept)
	}
	if buf.String() != snapshotCSV || n != int64(len(snapshotCSV)) {
		t.Errorf("got %d bytes %q", n, buf.String())
	}
}

func TestEntitySnapshotDownload_refusalOpensNothing(t *testing.T) {
	for _, tc := range []struct {
		name, ctype, body string
		status            int
		want              string
	}{
		{"scope", "application/json", `{"error":"scope not granted"}`, 403, "scope not granted"},
		{"missing", "application/json", `{"error":"entity Nope not found"}`, 404, "entity Nope not found"},
		{"not csv", "text/html", "<html>sign in</html>", 200, "not a CSV snapshot"},
		{"partial content", "text/csv", "id,name\n", 206, "not 200"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", tc.ctype)
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}))
			t.Cleanup(srv.Close)
			opened := false
			_, err := snapshotClient(srv).EntitySnapshotDownload(context.Background(), "e",
				func() (io.Writer, error) { opened = true; return io.Discard, nil })
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Errorf("err = %v, want it to carry %q", err, tc.want)
			}
			if opened {
				t.Error("a refused download must not open an output")
			}
		})
	}
}

// truncatingHub answers 200 text/csv and cuts the connection mid-body, the way a hub that fails
// after the headers are committed looks from the client: framing is the only signal left.
func truncatingHub(t *testing.T, chunked bool) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		conn, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		if chunked {
			_, _ = rw.WriteString("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nTransfer-Encoding: chunked\r\n\r\n" +
				"8\r\nid,name\n\r\n") // no terminating 0-chunk
		} else {
			_, _ = rw.WriteString("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nContent-Length: 100\r\n\r\nid,name\n")
		}
		_ = rw.Flush()
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestEntitySnapshotDownload_truncatedBodyIsAnError(t *testing.T) {
	for name, chunked := range map[string]bool{"chunked": true, "content-length": false} {
		t.Run(name, func(t *testing.T) {
			_, err := snapshotClient(truncatingHub(t, chunked)).EntitySnapshotDownload(context.Background(), "e",
				func() (io.Writer, error) { return io.Discard, nil })
			if err == nil {
				t.Fatal("a body cut short must not pass for a complete snapshot")
			}
		})
	}
}

// signalWriter reports its first write, so the test knows the body is flowing to the output
// before the hub has finished sending it — i.e. streamed, not buffered whole in memory.
type signalWriter struct {
	first chan struct{}
	n     int
}

func (s *signalWriter) Write(p []byte) (int, error) {
	if s.n == 0 {
		close(s.first)
	}
	s.n += len(p)
	return len(p), nil
}

func TestEntitySnapshotDownload_streamsBeforeTheBodyEnds(t *testing.T) {
	out := &signalWriter{first: make(chan struct{})}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/csv")
		_, _ = io.WriteString(w, "id,name\n")
		w.(http.Flusher).Flush()
		select { // the rest is sent only once the first bytes reached the output
		case <-out.first:
		case <-time.After(5 * time.Second):
			t.Error("the first bytes never reached the output while the body was still open")
			return
		}
		_, _ = io.WriteString(w, "1,Acme\n")
	}))
	t.Cleanup(srv.Close)
	n, err := snapshotClient(srv).EntitySnapshotDownload(context.Background(), "e",
		func() (io.Writer, error) { return out, nil })
	if err != nil || n != int64(len("id,name\n1,Acme\n")) {
		t.Errorf("n=%d err=%v", n, err)
	}
}

func TestEntitySnapshotDownload_cancelStopsTheCopy(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/csv")
		_, _ = io.WriteString(w, "id,name\n")
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(func() { close(release); srv.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	out := &signalWriter{first: make(chan struct{})}
	go func() { <-out.first; cancel() }()
	start := time.Now()
	_, err := snapshotClient(srv).EntitySnapshotDownload(ctx, "e", func() (io.Writer, error) { return out, nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if time.Since(start) > 5*time.Second {
		t.Error("the copy ran on past its cancellation")
	}
}
