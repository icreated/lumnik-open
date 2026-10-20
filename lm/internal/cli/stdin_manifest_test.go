package cli

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

// A manifest that never touched a disk is the point of the "-" sentinel: an integrator facing an
// SI with two hundred tables writes the generator once and pipes its output straight in.
//
//	./generate-entities.sh acme | lm entity apply -f -
//
// The doors are enumerated from the command tree rather than listed by hand, so the eleventh one
// someone adds cannot quietly go back to reading files only.
type manifestDoor struct {
	path []string // the sub-command path, e.g. {"entity", "apply"}
	yaml string   // a payload THIS door accepts — the doors do not all take the same shape
}

// marker is what must reach the wire: finding it in the request body proves the bytes came from
// stdin and not from anywhere else.
const marker = "stdin-sentinel-proof"

var manifestDoors = []manifestDoor{
	{[]string{"apply"}, "apiVersion: connectors.lumnik.io/v1\nkind: Connector\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"validate"}, "apiVersion: connectors.lumnik.io/v1\nkind: Connector\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"entity", "apply"}, "kind: Entity\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"app", "apply"}, "kind: App\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"process", "apply"}, "apiVersion: apps.lumnik.io/v1\nkind: Process\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"export", "apply"}, "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"workflow", "apply"}, "apiVersion: apps.lumnik.io/v1\nkind: Workflow\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"search", "apply"}, "apiVersion: search.lumnik.io/v1\nkind: SearchIndex\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"view"}, "apiVersion: apps.lumnik.io/v1\nkind: View\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	{[]string{"view", "apply"}, "apiVersion: apps.lumnik.io/v1\nkind: View\nmetadata:\n  name: " + marker + "\nspec: {}\n"},
	// The flat-payload doors: an envelope (kind/apiVersion) is refused here by design.
	// `hook apply` used to be a third one here — #610 removed it entirely (not implemented:
	// hooks come from deployment manifests, no `-f` flag left to read from stdin at all).
	{[]string{"connector", "apply"}, "tenantId: 1\nname: " + marker + "\ntypeId: csv-file\nenabled: true\n"},
}

// The inventory: the table above must name every -f door and no others. Anchored on the command
// tree itself, it fails in both directions — a new door left uncovered, or a stale entry for a
// door that no longer exists.
func TestEveryFileFlagDoorIsCovered(t *testing.T) {
	found := fileFlagDoors(NewRootCmd())
	declared := make([]string, 0, len(manifestDoors))
	for _, d := range manifestDoors {
		declared = append(declared, strings.Join(d.path, " "))
	}
	sort.Strings(found)
	sort.Strings(declared)
	if strings.Join(found, "|") != strings.Join(declared, "|") {
		t.Errorf("the -f doors and the stdin table disagree.\n  command tree: %v\n  table:        %v",
			found, declared)
	}
}

// The contract itself, one door at a time: `-f -` must read the manifest from stdin and put those
// bytes on the wire. Before the sentinel existed every one of these failed trying to open a file
// literally named "-".
func TestEveryFileDoorReadsTheManifestFromStdin(t *testing.T) {
	for _, door := range manifestDoors {
		t.Run(strings.Join(door.path, " "), func(t *testing.T) {
			bodies := make(chan string, 4)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				b, _ := io.ReadAll(r.Body)
				select {
				case bodies <- r.URL.Path + " " + string(b):
				default:
				}
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{}`))
			}))
			defer srv.Close()
			setupCfg(t, srv.URL)

			root := NewRootCmd()
			root.SetIn(strings.NewReader(door.yaml))
			root.SetOut(new(strings.Builder))
			root.SetErr(new(strings.Builder))
			root.SetArgs(append(append([]string{}, door.path...), "-f", "-"))
			err := root.Execute()

			if err != nil && strings.Contains(err.Error(), "no such file") {
				t.Fatalf("`-f -` was taken as a file name: %v", err)
			}
			select {
			case got := <-bodies:
				if !strings.Contains(got, marker) {
					t.Errorf("the piped manifest did not reach the wire, got: %s", got)
				}
			default:
				t.Fatalf("nothing reached the server; command error was: %v", err)
			}
		})
	}
}

// The file path must keep working exactly as before — the sentinel is an addition, not a switch.
func TestFileDoorStillReadsAFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "c.yaml")
	if err := os.WriteFile(path, []byte("hello: world\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := &cobra.Command{Use: "x"}
	got, err := readManifest(cmd, path)
	if err != nil {
		t.Fatalf("reading a file: %v", err)
	}
	if string(got) != "hello: world\n" {
		t.Errorf("got %q", got)
	}
}

// A manifest piped in is not typed at a keyboard, so nothing may be written to stderr: a note
// printed on every piped apply would be noise in every CI log that runs one.
func TestPipedManifestSaysNothingOnStderr(t *testing.T) {
	cmd := &cobra.Command{Use: "x"}
	cmd.SetIn(strings.NewReader("hello: world\n"))
	var errb strings.Builder
	cmd.SetErr(&errb)
	if _, err := readManifest(cmd, "-"); err != nil {
		t.Fatalf("reading stdin: %v", err)
	}
	if errb.Len() != 0 {
		t.Errorf("stderr must stay silent for a pipe, got %q", errb.String())
	}
}

// fileFlagDoors walks the command tree and returns the path of every runnable command carrying a
// -f/--file flag.
func fileFlagDoors(root *cobra.Command) []string {
	var out []string
	var walk func(c *cobra.Command, path []string)
	walk = func(c *cobra.Command, path []string) {
		if c.Runnable() && c.Flags().Lookup("file") != nil {
			out = append(out, strings.Join(path, " "))
		}
		for _, sub := range c.Commands() {
			walk(sub, append(append([]string{}, path...), sub.Name()))
		}
	}
	walk(root, nil)
	return out
}
