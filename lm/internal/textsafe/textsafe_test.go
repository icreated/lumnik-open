package textsafe

import (
	"bytes"
	"fmt"
	"io"
	"strings"
	"testing"
)

func TestDangerousRune(t *testing.T) {
	safe := []rune{'a', 'Z', '0', ' ', '\t', '\n', '\r', 'é', '⁰', '🔴'}
	for _, r := range safe {
		if DangerousRune(r) {
			t.Errorf("U+%04X should be safe", r)
		}
	}
	dangerous := []rune{
		0x00,   // NUL
		0x1F,   // last C0
		0x7F,   // DEL
		0x80,   // first C1
		0x9F,   // last C1
		0x202A, // first bidi embedding
		0x202E, // RTL override
		0x2066, // first bidi isolate
		0x2069, // last bidi isolate
	}
	for _, r := range dangerous {
		if !DangerousRune(r) {
			t.Errorf("U+%04X should be dangerous", r)
		}
	}
}

func TestWriter_passesSafeUnchanged(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)
	in := "hello\tworld\n café 한글"
	n, err := w.Write([]byte(in))
	if err != nil || n != len(in) {
		t.Fatalf("Write = (%d,%v), want (%d,nil)", n, err, len(in))
	}
	if buf.String() != in {
		t.Fatalf("safe text changed: %q", buf.String())
	}
}

func TestWriter_escapesDangerous(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)
	// input: 'a', RTL override, 'b', NUL, 'c', bidi isolate, 'd'
	in := "a" + string(rune(0x202E)) + "b" + string(rune(0x00)) + "c" + string(rune(0x2066)) + "d"
	_, _ = w.Write([]byte(in))
	got := buf.String()
	for _, r := range got {
		if DangerousRune(r) {
			t.Fatalf("dangerous rune U+%04X survived: %q", r, got)
		}
	}
	for _, hex := range []string{"202E", "0000", "2066"} {
		if !strings.Contains(got, hex) {
			t.Fatalf("missing escaped form %s in %q", hex, got)
		}
	}
	if !strings.Contains(got, "a") || !strings.Contains(got, "d") {
		t.Fatalf("safe letters lost: %q", got)
	}
}

func TestWriter_splitDangerousRune(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)
	full := []byte("x" + string(rune(0x202E)) + "y")
	cut := 2 // after 'x' and the first byte (0xE2) of the 3-byte rune
	_, _ = w.Write(full[:cut])
	_, _ = w.Write(full[cut:])
	got := buf.String()
	if strings.ContainsRune(got, rune(0x202E)) {
		t.Fatalf("raw rune survived split: %q", got)
	}
	if strings.Count(got, "202E") != 1 {
		t.Fatalf("split rune not escaped exactly once: %q", got)
	}
	if got[0] != 'x' || got[len(got)-1] != 'y' {
		t.Fatalf("surrounding bytes lost: %q", got)
	}
}

func TestWriter_splitSafeMultibyteRune(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)
	full := []byte("aéb")
	_, _ = w.Write(full[:2]) // 'a' + 0xC3
	_, _ = w.Write(full[2:]) // 0xA9 + 'b'
	if got := buf.String(); got != "aéb" {
		t.Fatalf("split safe rune corrupted: %q", got)
	}
}

func TestWriter_raw(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)
	if w.Raw() != &buf {
		t.Fatal("Raw() must return the target writer")
	}
}

func TestUnwrap(t *testing.T) {
	var buf bytes.Buffer
	if Unwrap(NewWriter(&buf)) != &buf {
		t.Fatal("Unwrap(*Writer) must return the wrapped target")
	}
	if Unwrap(&buf) != &buf {
		t.Fatal("Unwrap(non-Writer) must return the same writer")
	}
}

type failWriter struct{}

func (failWriter) Write(p []byte) (int, error) { return 0, fmt.Errorf("boom") }

type shortWriter struct{}

func (shortWriter) Write(p []byte) (int, error) { return len(p) - 1, nil }

// On an underlying failure the input is already consumed/transformed, so we must
// report n == len(p) (not 0) so a caller does not retry p and duplicate output.
func TestWriter_propagatesWriteErrorWithFullN(t *testing.T) {
	w := NewWriter(failWriter{})
	in := []byte("hello")
	n, err := w.Write(in)
	if err == nil {
		t.Fatal("expected the underlying write error to propagate")
	}
	if n != len(in) {
		t.Fatalf("n=%d, want %d (avoid retry-duplication)", n, len(in))
	}
}

// A short write with a nil error must surface as io.ErrShortWrite.
func TestWriter_shortWriteIsErrShortWrite(t *testing.T) {
	w := NewWriter(shortWriter{})
	if _, err := w.Write([]byte("hello")); err != io.ErrShortWrite {
		t.Fatalf("err=%v, want io.ErrShortWrite", err)
	}
}
