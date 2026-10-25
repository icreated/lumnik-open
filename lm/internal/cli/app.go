package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newAppCmd() *cobra.Command {
	a := &cobra.Command{Use: "app", Short: "Manage app manifests"}
	a.AddCommand(newAppApplyCmd(), newAppGetCmd(), newAppListCmd(), newAppRmCmd())
	return a
}

// newAppGetCmd prints one declared app as an applyable kind:App manifest (#1176).
func newAppGetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "get NAME",
		Short: "Print a declared app as an applyable kind:App manifest",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			manifest, err := c.AppManifest(args[0])
			if err != nil {
				return err
			}
			_, err = cmd.OutOrStdout().Write(manifest)
			return err
		},
	}
}

var errNotAListOfApps = errors.New("the hub's answer is not a list of apps")

// decodeKeepingNumbers reads one JSON object for YAML without the float64 rounding of a plain decode: an integer
// that fits stays an integer, a decimal stays a float, and anything a YAML number could not hold exactly (a 20-digit
// integer, 1e400) is kept as the text the hub sent.
func decodeKeepingNumbers(item json.RawMessage) (map[string]any, error) {
	dec := json.NewDecoder(bytes.NewReader(item))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return nil, err
	}
	return exactNumbers(m).(map[string]any), nil
}

func exactNumbers(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, e := range t {
			t[k] = exactNumbers(e)
		}
		return t
	case []any:
		for i, e := range t {
			t[i] = exactNumbers(e)
		}
		return t
	case json.Number:
		if i, err := t.Int64(); err == nil {
			return i
		}
		if strings.ContainsAny(string(t), ".eE") {
			if f, err := t.Float64(); err == nil && !math.IsInf(f, 0) {
				return f
			}
		}
		return string(t)
	}
	return v
}

func newAppListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List the declared apps (the registry the business-app PWA reads)",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			raw, err := c.AppList()
			if err != nil {
				return err
			}
			format := outputFormat(cmd)
			// the one check that the answer is a list (an object or null is not one), made without reading a
			// single number: 1e400 is valid JSON, and a float64 would refuse it
			var items []json.RawMessage
			if json.Unmarshal(raw, &items) != nil || items == nil {
				return errNotAListOfApps
			}
			switch format {
			case "json":
				// the hub's own body, re-indented: its values, its key order, nothing rebuilt; byte-exact like
				// every explicitly requested machine format
				var out bytes.Buffer
				_ = json.Indent(&out, raw, "", "  ") // cannot fail: the body parsed above
				out.WriteByte('\n')
				_, err := machineOut(cmd).Write(out.Bytes())
				return err
			case "yaml":
				apps := make([]map[string]any, 0, len(items))
				for _, item := range items {
					app, err := decodeKeepingNumbers(item)
					if err != nil {
						return errNotAListOfApps
					}
					apps = append(apps, app)
				}
				return output.Print(cmd.OutOrStdout(), format, nil, apps, nil)
			}
			rows, err := client.DecodeApps(raw)
			if err != nil {
				return errNotAListOfApps
			}
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TITLE", "SCOPE", "ICON", "ACCENT", "BACKGROUND", "DEFAULT", "TABS"}, rows,
				func(a client.AppInfo) []string {
					def, tabs := "", "(all)" // no tabs declared = the app inherits every tab the hub offers
					if a.Default {
						def = "yes"
					}
					if len(a.Tabs) > 0 {
						tabs = strings.Join(a.Tabs, ",")
					}
					return []string{a.Name, a.Title, a.Scope, a.Icon, a.AccentColor, a.BackgroundColor, def, tabs}
				})
		},
	}
}

func newAppRmCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rm <name>",
		Short: "Delete a declared app for good",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.AppDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "app %s deleted\n", args[0])
			return nil
		},
	}
}

func newAppApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply an app manifest (YAML) — the business-app PWA's title, scope, icon and theme",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.AppApply(body)
			if err != nil {
				return err
			}
			var result struct {
				Name           string   `json:"name"`
				Scope          string   `json:"scope"`
				Default        bool     `json:"default"`
				DemotedDefault string   `json:"demotedDefault"`
				Tabs           []string `json:"tabs"`
			}
			if json.Unmarshal(resp, &result) == nil && result.Name != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "Applied app %q → scope %s\n", result.Name, result.Scope)
				if len(result.Tabs) > 0 {
					// Absent from the answer = the app inherits every tab the hub offers.
					fmt.Fprintf(cmd.OutOrStdout(), "  tabs: %s\n", strings.Join(result.Tabs, ", "))
				}
				if result.Default {
					// An apply that promotes a default silently demotes another app. Saying so is
					// the difference between a registry change and a surprise.
					if result.DemotedDefault != "" {
						fmt.Fprintf(cmd.OutOrStdout(),
							"  default app — %q is no longer the default\n", result.DemotedDefault)
					} else {
						fmt.Fprintln(cmd.OutOrStdout(), "  default app")
					}
				}
				// Applied, and worth saying something (a tab this hub does not offer): stderr, exit 0.
				return renderApplyWarnings(cmd, manifestLabel(file), resp)
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML app manifest file, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
