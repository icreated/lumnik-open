package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The shapes a -f flag actually receives, as a table.
//
// The rule under test is who DECIDES: the YAML parser, never the file name and never a peek at
// the first line. A pre-check on line 1 would be a second judge able to disagree with the one
// that matters — it would reject a manifest opening on a comment, and would wave through a file
// whose first line is fine and whose body is broken, handing back the raw library error anyway.
// So: the parse decides, and the first line is only ever consulted to GUESS what the file is, so
// the message can name the command that would have worked.
func TestDecodeManifest(t *testing.T) {
	cases := []struct {
		name     string
		file     string
		body     string
		wantErr  bool
		contains []string // fragments the error must carry, in the reader's own terms
		absent   []string // …and fragments it must not
	}{
		{
			// The flat payload this command actually takes — no envelope, and it must keep working.
			name: "the flat admin payload decodes",
			file: "c.yaml",
			body: "tenantId: 1\nname: ad_user\ntypeId: csv-file\nenabled: true\n",
		},
		{
			// A real kind: Connector manifest, posted to the endpoint that wants the flat fields:
			// every one lands null — tenantId is a nullable Long — so the insert carries a NULL
			// tenant_id that RLS never matches, and the refusal surfaces as an anonymous 500.
			name:     "a kind: Connector manifest is refused here and sent to the door that opens",
			file:     "acme-ad-user.yml",
			body:     "apiVersion: connectors.lumnik.io/v1\nkind: Connector\nmetadata:\n  name: x\nspec:\n  parser:\n    has_header: true\n",
			wantErr:  true,
			contains: []string{"manifest", "lm apply -f"},
		},
		{
			name: "a flat payload opening on a comment decodes — the pre-check I did not write would have refused it",
			file: "c.yaml",
			body: "# the nightly export\nname: ad_user\ntypeId: csv-file\n",
		},
		{
			name: "a flat payload opening on blank lines decodes too",
			file: "c.yaml",
			body: "\n\nname: ad_user\ntypeId: csv-file\n",
		},
		{
			name:     "the CSV that prompted this says so, and names the command that ingests it",
			file:     "ad_user.csv",
			body:     "ad_user_id,name,email\n1,alpha,a@x\n",
			wantErr:  true,
			contains: []string{"ad_user.csv", "not a connector manifest", "lm csv ingest"},
		},
		{
			name:     "a CSV without the extension is caught all the same — the parse decides, not the name",
			file:     "export.txt",
			body:     "ad_user_id,name,email\n1,alpha,a@x\n",
			wantErr:  true,
			contains: []string{"not a connector manifest", "lm csv ingest"},
		},
		{
			name:    "valid YAML under a .csv name decodes — the name does NOT decide",
			file:    "misnamed.csv",
			body:    "name: ad_user\ntypeId: csv-file\n",
			wantErr: false,
		},
		{
			// A one-column CSV carries no comma at all: here the extension is the ONLY signal,
			// which is why it must be allowed to say yes.
			name:     "a single-column CSV is still recognised — the extension alone may say yes",
			file:     "ids.csv",
			body:     "ad_user_id\n1\n2\n",
			wantErr:  true,
			contains: []string{"lm csv ingest"},
		},
		{
			// The hint exists to be pasted; a path with a space must survive the trip.
			name:     "a path with a space comes back quoted, so the hint stays pasteable",
			file:     "my exports.csv",
			body:     "ad_user_id,name\n1,a\n",
			wantErr:  true,
			contains: []string{"'", "my exports.csv'"},
		},
		{
			name:     "a YAML list is not a manifest either, and gets no CSV advice it cannot use",
			file:     "list.yaml",
			body:     "- one\n- two\n",
			wantErr:  true,
			contains: []string{"not a connector manifest"},
		},
		{
			// A malformed MANIFEST is not a wrong file: the reader wants the line, not advice.
			name:     "a syntax error keeps the library's line number and gets no not-a-manifest verdict",
			file:     "broken.yaml",
			body:     "name: ad_user\ntypeId csv-file\n",
			wantErr:  true,
			contains: []string{"line 2"},
			// A typo in a real manifest must not be answered with advice about CSVs.
			absent: []string{"lm csv ingest", "is not a"},
		},
		{
			name:     "an empty file is not a manifest",
			file:     "empty.yaml",
			body:     "",
			wantErr:  true,
			contains: []string{"not a connector manifest", "empty"},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// No file is written: the decoder takes the bytes, and the path is now only a NAME
			// — what the refusal prints, and what looksLikeCsv reads an extension from. Seeding
			// a real file here would suggest a filesystem dependency the decoder no longer has.
			path := filepath.Join("/tmp/tenant", tc.file)

			payload, err := decodeManifest([]byte(tc.body), path, "connector")

			if !tc.wantErr {
				if err != nil {
					t.Fatalf("expected a decode, got: %v", err)
				}
				if payload["name"] != "ad_user" {
					t.Fatalf("expected the file's own fields, got %v", payload)
				}
				return
			}
			if err == nil {
				t.Fatalf("expected a refusal, got payload %v", payload)
			}
			for _, want := range tc.contains {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("error must carry %q, got: %v", want, err)
				}
			}
			for _, unwanted := range tc.absent {
				if strings.Contains(err.Error(), unwanted) {
					t.Errorf("error must not carry %q, got: %v", unwanted, err)
				}
			}
			if strings.Contains(err.Error(), "map[string]interface") {
				t.Errorf("the library's vocabulary must not reach the reader: %v", err)
			}
		})
	}
}

// A missing file keeps its own plain error — the reader is not told about manifests when the
// problem is that nothing was read. Asserted through the command now that reading and decoding
// are separate functions: what matters is that the reader, not the decoder, answers first.
func TestDecodeManifestMissingFile(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "nope.yaml")
	cmd := NewRootCmd()
	cmd.SetOut(new(strings.Builder))
	cmd.SetErr(new(strings.Builder))
	cmd.SetArgs([]string{"connector", "apply", "-f", missing})

	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected an error for a missing file")
	}
	if strings.Contains(err.Error(), "not a connector manifest") {
		t.Errorf("a missing file is not a malformed manifest: %v", err)
	}
	if !strings.Contains(err.Error(), "no such file") {
		t.Errorf("the reader's own error must survive: %v", err)
	}
}

// The wiring, not the decoder: both commands that parse a manifest locally must route through
// decodeManifest, so the honest refusal actually reaches the reader. Neither reaches the network
// — the refusal happens before a client is ever built, which is also why no server is stubbed.
//
// What is asserted here is only what the two sites SHARE. Which manifest each one names is the
// business of TestRefusalNamesTheKindTheCommandWanted — this test asserted "connector" for both
// until the review caught it, which is how a test comes to encode the bug it was meant to guard.
func TestApplyCommandsRefuseANonManifestInTheReadersTerms(t *testing.T) {
	for _, cmdPath := range [][]string{{"connector", "apply"}, {"hook", "apply"}} {
		t.Run(strings.Join(cmdPath, " "), func(t *testing.T) {
			csv := filepath.Join(t.TempDir(), "ad_user.csv")
			if err := os.WriteFile(csv, []byte("ad_user_id,name,email\n1,alpha,a@x\n"), 0o600); err != nil {
				t.Fatalf("seeding: %v", err)
			}

			cmd := NewRootCmd()
			cmd.SetOut(new(strings.Builder))
			cmd.SetErr(new(strings.Builder))
			cmd.SetArgs(append(append([]string{}, cmdPath...), "-f", csv))

			err := cmd.Execute()
			if err == nil {
				t.Fatal("expected a refusal")
			}
			for _, want := range []string{"is not a", "manifest", "lm csv ingest"} {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("error must carry %q, got: %v", want, err)
				}
			}
			if strings.Contains(err.Error(), "map[string]interface") {
				t.Errorf("the library's vocabulary must not reach the reader: %v", err)
			}
		})
	}
}

// The refusal must name the manifest the command actually wanted. Sharing one decoder between
// `connector apply` and `hook apply` made it say "connector" at both — a message that lies at one
// site is worse than two messages that were honest.
func TestRefusalNamesTheKindTheCommandWanted(t *testing.T) {
	for _, tc := range []struct{ cmd, want string }{
		{"connector", "not a connector manifest"},
		{"hook", "not a hook manifest"},
	} {
		t.Run(tc.cmd, func(t *testing.T) {
			csv := filepath.Join(t.TempDir(), "ad_user.csv")
			if err := os.WriteFile(csv, []byte("ad_user_id,name\n1,a\n"), 0o600); err != nil {
				t.Fatalf("seeding: %v", err)
			}
			cmd := NewRootCmd()
			cmd.SetOut(new(strings.Builder))
			cmd.SetErr(new(strings.Builder))
			cmd.SetArgs([]string{tc.cmd, "apply", "-f", csv})

			err := cmd.Execute()
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Errorf("expected %q, got: %v", tc.want, err)
			}
		})
	}
}

// The pasteability contract, for BOTH shells the project documents — asserted on the pure
// function with the platform as a parameter, so the table states each style outright instead of
// changing shape with the host that runs it.
//
// Windows is not a footnote here: docs/deploy-windows.md runs `.\lm.exe` in PowerShell, not in
// WSL — WSL is where the stack lives, PowerShell is where the CLI is typed. Single quotes are
// literal in both shells; only an embedded quote diverges (POSIX `\'`, PowerShell doubles it).
//
// Asserted on the function rather than through decodeManifest, because t.TempDir() builds its
// directory from the SUBTEST NAME: a comma in a name quoted the hint for reasons having nothing
// to do with the file under test. That is how an assertion ends up measuring its own harness.
func TestQuoteFor(t *testing.T) {
	for _, tc := range []struct{ name, goos, in, want string }{
		{"posix leaves an ordinary relative path bare", "linux", "../doc/ad_user.csv", "../doc/ad_user.csv"},
		{"posix leaves an absolute path bare", "linux", "/tmp/ad_user.csv", "/tmp/ad_user.csv"},
		{"posix quotes a space", "linux", "/tmp/my exports.csv", "'/tmp/my exports.csv'"},
		{"posix quotes a metacharacter", "linux", "/tmp/a;rm -rf.csv", "'/tmp/a;rm -rf.csv'"},
		{"posix escapes an embedded quote with a backslash", "linux", "/tmp/it's.csv", `'/tmp/it'\''s.csv'`},

		{"windows leaves a drive path bare — backslash and colon are ordinary there", "windows",
			`C:\Users\me\ad_user.csv`, `C:\Users\me\ad_user.csv`},
		{"windows quotes a space", "windows", `C:\My Files\ad_user.csv`, `'C:\My Files\ad_user.csv'`},
		{"windows doubles an embedded quote, as PowerShell wants", "windows",
			`C:\it's.csv`, `'C:\it''s.csv'`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := quoteFor(tc.goos, tc.in); got != tc.want {
				t.Errorf("quoteFor(%q, %q) = %q, want %q", tc.goos, tc.in, got, tc.want)
			}
		})
	}
}

// The EOF gesture is not the same key on both shells the project documents, and the wrong one
// reads as a hang: a PowerShell reader pressing Ctrl+D sees nothing happen. Stated as a table
// with the platform as a parameter, like quoteFor above, rather than inferred from whatever host
// runs the test.
func TestEofGestureFor(t *testing.T) {
	for _, tc := range []struct{ goos, want string }{
		{"linux", "Ctrl+D"},
		{"darwin", "Ctrl+D"},
		{"windows", "Ctrl+Z then Enter"},
	} {
		t.Run(tc.goos, func(t *testing.T) {
			if got := eofGestureFor(tc.goos); got != tc.want {
				t.Errorf("eofGestureFor(%q) = %q, want %q", tc.goos, got, tc.want)
			}
		})
	}
}

// Every message that names the EOF key must get it from eofGesture(), so a Windows reader is
// never told to press a key that does nothing there. Anchored on the sources rather than on a
// list of call sites: the next notice someone writes is covered without being enrolled.
//
// terminal.go is excluded because it DEFINES both gestures — a guard that cannot tell a
// definition from a hard-coded use fails for a reason that is not a defect. (It did, first try.)
func TestOnlyTerminalGoNamesTheEofKey(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if f == "terminal.go" || strings.HasSuffix(f, "_test.go") {
			continue
		}
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(b), "Ctrl+") {
			t.Errorf("%s hard-codes an EOF gesture — call eofGesture() so Windows reads correctly", f)
		}
	}
}

// The line six docs pages transcribe verbatim must keep its exact shape for an ordinary path —
// and start surviving the paths those pages never show. Quoting it was the point of routing the
// hint through shellArg: an unquoted path with a space breaks the moment the line is pasted.
func TestRerunCommand(t *testing.T) {
	for _, tc := range []struct{ name, in, want string }{
		// The four paths that actually appear in docs/connectors/*.md — these must not move.
		{"a bare file name is left alone", "bad.yaml", "lm apply -f bad.yaml"},
		{"a relative path is left alone", "docs/connectors/kafka/demo/90-refused.yaml",
			"lm apply -f docs/connectors/kafka/demo/90-refused.yaml"},
		{"a space is quoted, so the line survives being pasted", "my exports.yaml",
			"lm apply -f 'my exports.yaml'"},
		{"a piped manifest gets no command at all", "-", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := rerunCommand(tc.in); got != tc.want {
				t.Errorf("rerunCommand(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}
