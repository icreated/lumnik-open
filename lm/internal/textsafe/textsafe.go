// Package textsafe escapes control and bidirectional Unicode runes in lm's
// human-facing output. A raw bidi/control rune reflected into a terminal is a
// Trojan-Source-class hazard (it can reorder or truncate what the operator
// sees). DangerousRune is the single source of truth for "dangerous", shared
// with the OTK oracle so the detector and the sanitizer can never drift.
package textsafe

import (
	"fmt"
	"io"
	"unicode/utf8"
)

// Writer wraps a target io.Writer and escapes every DangerousRune to its
// backslash-u hex form before forwarding. A trailing incomplete UTF-8 rune is
// held in carry and reassembled on the next Write, so producers that split a
// rune across Write calls (json.Encoder, tabwriter) are handled correctly.
type Writer struct {
	w     io.Writer
	carry []byte
}

// NewWriter returns a Writer forwarding to w.
func NewWriter(w io.Writer) *Writer { return &Writer{w: w} }

// Raw returns the wrapped target writer, used to bypass sanitization for
// machine output (json/yaml).
func (sw *Writer) Raw() io.Writer { return sw.w }

// Unwrap returns the underlying target writer when w is a *Writer, else w
// unchanged. Deliberate machine output (json/yaml artifacts, raw passthrough
// bodies) writes through Unwrap so it stays byte-exact, bypassing the sanitizer
// that protects human-facing output.
func Unwrap(w io.Writer) io.Writer {
	if sw, ok := w.(*Writer); ok {
		return sw.w
	}
	return w
}

// Write sanitizes p and forwards it. All of p is consumed before forwarding
// (some bytes may be held in carry as an incomplete trailing rune), so Write
// reports n == len(p) even on a forwarding failure — returning a smaller n would
// invite the caller to retry p and duplicate already-emitted output. A short
// underlying write is surfaced as io.ErrShortWrite.
func (sw *Writer) Write(p []byte) (int, error) {
	data := append(sw.carry, p...)
	sw.carry = nil
	var out []byte
	for len(data) > 0 {
		if !utf8.FullRune(data) {
			// incomplete trailing rune: hold it for the next Write
			sw.carry = append(sw.carry, data...)
			break
		}
		r, size := utf8.DecodeRune(data)
		if DangerousRune(r) {
			out = append(out, fmt.Sprintf(`\u%04X`, r)...)
		} else {
			out = append(out, data[:size]...)
		}
		data = data[size:]
	}
	if len(out) > 0 {
		n, err := sw.w.Write(out)
		if err == nil && n < len(out) {
			err = io.ErrShortWrite
		}
		if err != nil {
			return len(p), err
		}
	}
	return len(p), nil
}

// DangerousRune reports whether r must be escaped before reaching a terminal.
// Ordinary whitespace (tab, newline, carriage return) is intentionally safe.
func DangerousRune(r rune) bool {
	switch {
	case r == '\t' || r == '\n' || r == '\r':
		return false
	case r >= 0x202A && r <= 0x202E:
		return true // bidi embeddings / overrides
	case r >= 0x2066 && r <= 0x2069:
		return true // bidi isolates
	case r < 0x20:
		return true // C0 controls (incl. NUL)
	case r >= 0x7F && r <= 0x9F:
		return true // DEL + C1 controls
	}
	return false
}
