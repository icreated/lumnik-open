// Package brand holds lm's name and wordmark — the one place the product says who it is, shared by
// the CLI login screen, the TUI's session-expired modal and the TUI header, so no surface can drift
// away from the others.
package brand

import (
	"os"
	"strings"
)

// Version and GitCommit are the binary's build stamp, set at release time by goreleaser's -X
// ldflags (see .goreleaser.yaml; a test in this package pins that path to these symbols, because a
// stale path is dropped by the linker in silence and would ship every release as "dev"). They live
// here rather than in the CLI because this package is where the product says who it is, and the
// version is part of that: `lm version` and the login banner read the same two vars, so they cannot
// drift. A build from source keeps "dev" — that is the truth, and a useful signal of its own.
var (
	Version   = "dev"
	GitCommit = "unknown"
)

// GoldHex is lumière, the accent lm wears everywhere. tui.ChromePrimary is the same colour spelt as
// a tcell value for the TUI borders; a test in package tui pins the two spellings together.
const GoldHex = "#ffb000"

// goldANSI is lumière for a terminal with no tcell to negotiate colour depth on its behalf. xterm
// index 214 is #ffaf00 — one step of green from lumière, and rendered where 24-bit colour is not.
const goldANSI = "\033[38;5;214m"
const resetANSI = "\033[0m"

// ColorsEnabled honors the NO_COLOR convention (https://no-color.org) and, on Windows, only says
// yes once the console actually interprets ANSI (vtEnabled switches it into VT mode; a console
// that refuses gets the wordmark plain rather than as raw escapes). It lives here rather than in
// the TUI because the CLI login page needs it too, and one answer beats three.
func ColorsEnabled() bool { return os.Getenv("NO_COLOR") == "" && vtEnabled() }

// Wordmark is LUMNIK in filled blocks with a drop shadow (the "ANSI Shadow" shape). Every row is
// exactly Width runes, so the block stays a rectangle; a test pins that.
//
// The runes are block elements and box drawing (U+2500…U+2588) — all far above the C1 range and
// not bidi, so lm's SafeWriter passes them through untouched. A test pins that too, because a
// logo escaped to \uXXXX would be worse than no logo.
// Right-padded at init rather than in the literal: the two rows whose K column ends in a space
// lose it to any tool that trims trailing whitespace, and a ragged logo reads as a rendering bug.
// Guaranteed by construction beats guarded by vigilance.
var Wordmark = rectangle(`██╗     ██╗   ██╗███╗   ███╗███╗   ██╗██╗██╗  ██╗
██║     ██║   ██║████╗ ████║████╗  ██║██║██║ ██╔╝
██║     ██║   ██║██╔████╔██║██╔██╗ ██║██║█████╔╝
██║     ██║   ██║██║╚██╔╝██║██║╚██╗██║██║██╔═██╗
███████╗╚██████╔╝██║ ╚═╝ ██║██║ ╚████║██║██║  ██╗
╚══════╝ ╚═════╝ ╚═╝     ╚═╝╚═╝  ╚═══╝╚═╝╚═╝  ╚═╝`)

// rectangle right-pads every row to Width so the block cannot go ragged.
func rectangle(art string) string {
	rows := strings.Split(art, "\n")
	for i, r := range rows {
		if n := Width - len([]rune(r)); n > 0 {
			rows[i] = r + strings.Repeat(" ", n)
		}
	}
	return strings.Join(rows, "\n")
}

// Width is the wordmark's column count. Below this a terminal would fold every row and the shape
// would be lost, so Banner prints the name plainly instead.
const Width = 49

// Name is what the header and the fallback say when there is no room to draw.
const Name = "lumnik 2026"

// Tagline is the meaning of the name: lum (light) + the Slavic agent suffix -nik.
const Tagline = "the light-bringer · " + Name

// Banner renders the wordmark in lumière for a terminal of the given width, centred, with the
// tagline plain beneath it. It opens with the logo and closes with a newline — a caller wanting air
// around it adds its own. A width of 0 means "not a terminal" (a pipe or a redirect) and is treated
// as roomy: that output is the most likely to be read by a human, not the least.
//
// Below Width the block is dropped rather than wrapped — a folded logo reads as a rendering fault.
//
// The colour opens and closes on every row rather than once around the block, so a pager or a
// truncating terminal that cuts mid-banner cannot bleed gold over everything printed after it.
func Banner(width int) string {
	gold, reset := goldANSI, resetANSI
	if !ColorsEnabled() {
		gold, reset = "", ""
	}
	if width > 0 && width < Width {
		return gold + Name + " · " + Version + reset + "\n"
	}
	// The signature carries the build stamp: whoever reads this page may not have built the
	// binary, and a guess about which version is running is how two machines end up debugging
	// different products. Centring measures THIS string, not Tagline, or the line sits off-axis.
	sig := Tagline + " · " + Version
	pad := strings.Repeat(" ", indent(width))
	var b strings.Builder
	for _, line := range strings.Split(Wordmark, "\n") {
		b.WriteString(pad)
		b.WriteString(gold)
		b.WriteString(line)
		b.WriteString(reset)
		b.WriteByte('\n')
	}
	b.WriteString(pad)
	b.WriteString(strings.Repeat(" ", max((Width-len([]rune(sig)))/2, 0)))
	b.WriteString(sig)
	b.WriteByte('\n')
	return b.String()
}

// indent centres the block, but never so far right that a slightly-too-narrow terminal folds it.
func indent(width int) int {
	if width <= Width {
		return 0
	}
	return (width - Width) / 2
}
