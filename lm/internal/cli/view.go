package cli

import (
	"fmt"
	"io"
	"os"
	"regexp"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newViewCmd() *cobra.Command {
	var file string
	var filters []string
	cmd := &cobra.Command{
		Use:   "view [KEY]",
		Short: "Render a kind:View as a list, or one record's detail when a KEY is given",
		Long: `Render a kind:View as a list, or one record's detail when a KEY is given.

Subcommands manage the saved-view store: list, get, apply, generate.`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) == 1 && len(filters) > 0 {
				return fmt.Errorf("--filter applies to the list; it is not used with a detail KEY — pass one or the other")
			}
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if len(args) == 1 {
				return renderDetail(cmd, c, body, args[0])
			}
			return renderList(cmd, c, body, filters)
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "kind:View YAML manifest, or - for stdin (required)")
	cmd.Flags().StringArrayVar(&filters, "filter", nil,
		"filter rows: col=value (exact), col~text (contains), col>v col<v col>=v col<=v (number/ISO date); repeatable (AND)")
	_ = cmd.MarkFlagRequired("file")
	// Accepted collision: a detail KEY literally named "generate", "list", "get", "apply", or
	// "rm" now routes to the subcommand (cobra resolves subcommands before positionals); no
	// real key is named that.
	cmd.AddCommand(newViewGenerateCmd())
	cmd.AddCommand(newViewListCmd())
	cmd.AddCommand(newViewGetCmd())
	cmd.AddCommand(newViewApplyCmd())
	cmd.AddCommand(newViewRmCmd())
	return cmd
}

// newViewRmCmd deletes one saved view for good — the delete gesture #487 gave kind:Process,
// missing here (#507).
func newViewRmCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rm <name>",
		Short: "Delete a saved view for good",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.ViewDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "view %s deleted\n", args[0])
			return nil
		},
	}
}

// newViewListCmd lists the saved views visible to the caller (optionally narrowed to one
// scope) — table NAME | SCOPE | TITLE, or -o json|yaml for the structured payload.
func newViewListCmd() *cobra.Command {
	var scope string
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List the saved views visible to the caller",
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			views, err := c.ViewsList(scope)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "SCOPE", "TITLE"}, views,
				func(v client.ViewSummary) []string {
					return []string{v.Name, v.Scope, v.Title}
				})
		},
	}
	cmd.Flags().StringVar(&scope, "scope", "", "narrow the listing to one scope")
	return cmd
}

// newViewGetCmd fetches one saved view's manifest YAML, verbatim: to stdout, or to a file
// with -o (the same shadow-the-root--o-as-a-file-path trick as `view generate`, since here
// -o is not the table/json/yaml format).
func newViewGetCmd() *cobra.Command {
	var outFile string
	cmd := &cobra.Command{
		Use:   "get NAME",
		Short: "Fetch a saved view's manifest YAML",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			yaml, err := c.ViewGet(args[0])
			if err != nil {
				return err
			}
			if outFile != "" {
				if err := os.WriteFile(outFile, yaml, 0o644); err != nil {
					return err
				}
				fmt.Fprint(cmd.OutOrStdout(), confirmLine(outFile))
				return nil
			}
			fmt.Fprint(cmd.OutOrStdout(), string(yaml))
			return nil
		},
	}
	cmd.Flags().StringVarP(&outFile, "output", "o", "", "write the manifest to a file instead of stdout")
	return cmd
}

// newViewApplyCmd validates and upserts a kind:View manifest into the saved-view store.
func newViewApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Validate and upsert a kind:View manifest into the saved-view store",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			res, err := c.ViewApply(body)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "applied: %s (scope %s)\n", res.Name, res.Scope)
			if len(res.Warnings) > 0 {
				// Applied, and still worth saying something: stderr, exit 0 — as `lm app apply` does.
				return output.FormatValidation(cmd.ErrOrStderr(), manifestLabel(file), "", &client.ValidationResult{Warnings: res.Warnings})
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "kind:View YAML manifest, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func newViewGenerateCmd() *cobra.Command {
	var outFile string
	cmd := &cobra.Command{
		Use:   "generate <scope> \"<description>\"",
		Short: "Ask the AI to write a kind:View manifest over a scope (review it, then `lm view -f`)",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			// Threshold guard: a filename with a line break would split the rewritten
			// comment into raw YAML above the manifest (quotes don't stop a YAML comment).
			// YAML 1.1 also breaks on NEL/LS/PS, so those count too.
			if strings.ContainsAny(outFile, "\r\n\u0085\u2028\u2029") {
				return fmt.Errorf("the -o filename must not contain line breaks")
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			yaml, err := c.ViewGenerate(args[0], args[1])
			if err != nil {
				return err
			}
			if outFile != "" {
				yaml = rewriteSuggestedFile(yaml, outFile)
				if err := os.WriteFile(outFile, []byte(yaml), 0o644); err != nil {
					return err
				}
				fmt.Fprint(cmd.OutOrStdout(), confirmLine(outFile))
				return nil
			}
			fmt.Fprint(cmd.OutOrStdout(), yaml)
			return nil
		},
	}
	// Same-name local flag SHADOWS the root's persistent -o/--output (format) — the
	// source_rest probe precedent. A different-name flag sharing the shorthand panics
	// at cobra's flag merge; generate emits raw YAML, so no format flag is lost.
	cmd.Flags().StringVarP(&outFile, "output", "o", "", "write the manifest to a file instead of stdout")
	return cmd
}

// rewriteSuggestedFile points the suggested render command at the file the user actually
// chose with -o; the server could only suggest its own slug-derived name. The filename is
// POSIX-quoted when needed so the command stays copy-pasteable.
func rewriteSuggestedFile(yaml, outFile string) string {
	const prefix = "# lm view -f "
	if !strings.HasPrefix(yaml, prefix) {
		return yaml
	}
	rest := yaml[len(prefix):]
	end := strings.IndexAny(rest, " \n")
	if end == -1 {
		return yaml
	}
	return prefix + posixQuote(outFile) + rest[end:]
}

// confirmLine is the post-write message: prose keeps the raw name, the command part is
// POSIX-quoted so it stays copy-pasteable.
func confirmLine(outFile string) string {
	return fmt.Sprintf("manifest written to %s — review it, then: lm view -f %s\n",
		outFile, posixQuote(outFile))
}

var shellPlain = regexp.MustCompile(`^[A-Za-z0-9._/-]+$`)

// posixQuote returns s unchanged when it is shell-plain, else single-quoted POSIX-style
// (embedded single quotes become '\'').
func posixQuote(s string) string {
	if shellPlain.MatchString(s) {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func renderList(cmd *cobra.Command, c *client.Client, body []byte, filters []string) error {
	res, err := c.ViewRender(body, filters)
	if err != nil {
		return err
	}
	format, _ := cmd.Flags().GetString("output")
	return emitList(cmd.OutOrStdout(), format, res, filters)
}

// emitList renders a list: the human table (default / -o table) or a structured payload for -o json|yaml.
func emitList(out io.Writer, format string, res *client.ViewResult, filters []string) error {
	if format != "" && format != "table" {
		return output.Print(out, format, nil, []*client.ViewResult{res}, nil)
	}
	title := res.Title
	if len(filters) > 0 {
		title += " · " + strings.Join(filters, " · ")
	}
	if title != "" {
		fmt.Fprintf(out, "%s\n\n", title)
	}
	headers := make([]string, len(res.Columns))
	for i, col := range res.Columns {
		headers[i] = col.Label
	}
	return output.Print(out, "table", headers, res.Rows,
		func(row []string) []string { return row })
}

func renderDetail(cmd *cobra.Command, c *client.Client, body []byte, key string) error {
	res, err := c.ViewDetail(body, key)
	if err != nil {
		return err
	}
	format, _ := cmd.Flags().GetString("output")
	return emitDetail(cmd.OutOrStdout(), format, res)
}

// emitDetail renders one record: the human layout (default / -o table) or a structured payload for -o json|yaml.
func emitDetail(out io.Writer, format string, res *client.ViewDetailResult) error {
	if format != "" && format != "table" {
		return output.Print(out, format, nil, []*client.ViewDetailResult{res}, nil)
	}
	formatDetail(out, res)
	return nil
}

// formatDetail renders one record: "Title / Key", the aligned fields, then — only when the
// backing table carries a _conflicts ledger — either the agreement line or the per-column
// divergence lines (label   src «value» · src «value»).
func formatDetail(out io.Writer, res *client.ViewDetailResult) {
	fmt.Fprintf(out, "%s / %s\n\n", res.Title, res.Key)
	tw := newPadWriter()
	for _, f := range res.Fields {
		tw.line("  "+f.Label, f.Value)
	}
	tw.flush(out)
	if res.ConflictLedger {
		fmt.Fprintln(out)
		if len(res.Conflicts) == 0 {
			fmt.Fprintln(out, "  ✓ aucune ambiguïté ni divergence")
		} else {
			fmt.Fprintln(out, "  (!) Ambiguïtés et divergences")
			ctw := newPadWriter()
			for _, cf := range res.Conflicts {
				parts := make([]string, len(cf.Sources))
				for i, s := range cf.Sources {
					parts[i] = fmt.Sprintf("%s «%s»", s.Src, s.Display())
				}
				ctw.line("  "+cf.Label, strings.Join(parts, " · "))
			}
			ctw.flush(out)
		}
	}
}
