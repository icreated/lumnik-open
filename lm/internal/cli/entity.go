package cli

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/google/uuid"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newEntityCmd() *cobra.Command {
	e := &cobra.Command{Use: "entity", Short: "Manage entity manifests"}
	e.AddCommand(newEntityListCmd())
	e.AddCommand(newEntityApplyCmd())
	e.AddCommand(newEntityDeleteCmd())
	e.AddCommand(newEntityResolveCmd())
	e.AddCommand(newEntityAmbiguousCmd())
	e.AddCommand(newEntityHistoryCmd())
	e.AddCommand(newEntityAssertCmd(true))
	e.AddCommand(newEntityAssertCmd(false))
	e.AddCommand(newEntityExportCmd())
	e.AddCommand(newEntityGetCmd())
	return e
}

// newEntityListCmd lists the entities the caller may read — the names entity get/export take (#1114).
func newEntityListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List the entities you may read (a scope-bound user sees only its scopes)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			list, err := c.EntityList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, []string{"NAME", "SOURCES", "SCOPES", "MODE"}, list,
				func(e client.EntitySummary) []string {
					return []string{e.Name, strings.Join(e.Sources, ","), strings.Join(e.Scopes, ","), e.Mode()}
				})
		},
	}
}

// newEntityResolveCmd replays identity resolution over one entity's whole extent (#620) — for
// after fixing a human assertion, editing the cascade, or wanting a fresh answer without waiting
// for the next connector run.
func newEntityResolveCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "resolve NAME",
		Short: "Replay identity resolution over one entity's whole extent",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			res, err := c.EntityResolve(args[0])
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			if format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, res)
			}
			out := cmd.OutOrStdout()
			fmt.Fprintf(out, "rows seen: %d\n", res.RowsSeen)
			fmt.Fprintf(out, "resolved: %d\n", res.Resolved)
			fmt.Fprintf(out, "ambiguous: %d\n", res.Ambiguous)
			fmt.Fprintf(out, "unresolved (no rule): %d\n", res.UnresolvedNoRule)
			fmt.Fprintf(out, "unresolved (provider): %d\n", res.UnresolvedProvider)
			fmt.Fprintf(out, "newly minted: %d\n", res.NewlyMinted)
			fmt.Fprintf(out, "provider calls: %d\n", res.ProviderCalls)
			fmt.Fprintf(out, "provider failures: %d\n", res.ProviderFailures)
			if len(res.RuleHits) > 0 {
				fmt.Fprintln(out, "rule hits:")
				keys := make([]string, 0, len(res.RuleHits))
				for k := range res.RuleHits {
					keys = append(keys, k)
				}
				sort.Strings(keys)
				for _, k := range keys {
					fmt.Fprintf(out, "  %s: %d\n", k, res.RuleHits[k])
				}
			}
			return nil
		},
	}
}

var ambiguousHeaders = []string{"IDENTITY_REF", "SOURCE", "SOURCE_ID", "CANDIDATES", "DECIDED_BY", "OCCURRED_AT"}

func ambiguousRow(a client.AmbiguousIdentity) []string {
	return []string{a.IdentityRef, a.Source, a.SourceID, strings.Join(a.Candidates, ","),
		a.DecidedBy, output.HumanTime(&a.OccurredAt)}
}

// newEntityAmbiguousCmd lists the identities a resolution pass could not settle for one
// entity — the terminal-only view of #622's ambiguity API, so an integrator can see what did
// not resolve without leaving the terminal.
func newEntityAmbiguousCmd() *cobra.Command {
	var offset int
	cmd := &cobra.Command{
		Use:   "ambiguous NAME",
		Short: "List identities this entity's resolution pass could not settle",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.EntityAmbiguous(args[0], pageLimit(cmd), offset)
			if err != nil {
				return err
			}
			return printPage(cmd, ambiguousHeaders, items, ambiguousRow)
		},
	}
	addPageLimitFlag(cmd, 100, client.AmbiguousPageCap, "max ambiguous identities to list")
	cmd.Flags().IntVar(&offset, "offset", 0, "page offset")
	return cmd
}

var historyHeaders = []string{"OCCURRED_AT", "OUTCOME", "RULE", "PROVIDER", "CANDIDATES", "SET_ASIDE", "DECIDED_BY", "RUN_ID"}

func historyRow(e client.EvidenceEvent) []string {
	return []string{output.HumanTimeSeconds(&e.OccurredAt), e.Outcome, e.RuleDeclaredID, e.Provider,
		strings.Join(e.Candidates, ","), setAsideIDs(e.SetAside), e.DecidedBy, e.RunID}
}

// setAsideIDs lists the entities an event set aside (#920); the reason and rule are in `-o json`.
func setAsideIDs(set []client.SetAside) string {
	ids := make([]string, len(set))
	for i, s := range set {
		ids[i] = s.EntityID
	}
	return strings.Join(ids, ",")
}

// newEntityHistoryCmd reads one source identity's full resolution decision history from the
// append-only ledger, chronological.
func newEntityHistoryCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "history NAME SOURCE SOURCE_ID",
		Short: "Show one source identity's full resolution decision history",
		Args:  cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			events, err := c.EntityHistory(args[0], args[1], args[2])
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, historyHeaders, events, historyRow)
		},
	}
	return cmd
}

// newEntityAssertCmd builds `assert-same`/`assert-distinct` — the CLI door into the human
// correction path (#619) that outranks every cascade rule. Shared between both verbs: the
// request shape, validation and confirmation message are identical but for the verb itself.
func newEntityAssertCmd(same bool) *cobra.Command {
	verb, subject := "same", "the same real-world entity"
	if !same {
		verb, subject = "distinct", "different real-world entities"
	}
	var left, right, reason string
	cmd := &cobra.Command{
		Use:   "assert-" + verb + " NAME",
		Short: "Assert that two rows belong to " + subject,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			assertion := client.EntityAssertion{Reason: reason}
			assertion.LeftRef, assertion.Left = refOrHandle(left)
			assertion.RightRef, assertion.Right = refOrHandle(right)
			if same {
				err = c.EntityAssertSame(args[0], assertion)
			} else {
				err = c.EntityAssertDistinct(args[0], assertion)
			}
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "recorded: %s and %s are %s\n", left, right, verb)
			return nil
		},
	}
	cmd.Flags().StringVar(&left, "left", "", "first row: SOURCE:ID as in lm entity history (t_crm:9003), or an identity_ref (required)")
	cmd.Flags().StringVar(&right, "right", "", "second row: SOURCE:ID, or an identity_ref (required)")
	cmd.Flags().StringVar(&reason, "reason", "", "why (optional, recorded in the decision ledger)")
	_ = cmd.MarkFlagRequired("left")
	_ = cmd.MarkFlagRequired("right")
	return cmd
}

// refOrHandle sends a UUID as an identity ref and anything else as a source handle; the hub
// says what is wrong with a malformed one.
func refOrHandle(v string) (ref, handle string) {
	if _, err := uuid.Parse(v); err == nil {
		return v, ""
	}
	return "", v
}

func newEntityDeleteCmd() *cobra.Command {
	var force bool
	cmd := &cobra.Command{
		Use:   "delete NAME",
		Short: "Remove an entity (drops its view, frees the connectors that fed it)",
		Long: "Remove an entity: its view is dropped for every tenant, every search index over it goes\n" +
			"with it, and the connectors that fed it are freed.\n\n" +
			"lm names the search index you can see over it and asks you to type 'delete' first;\n" +
			"--force skips the question in a script.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if !force {
				indexes, err := c.SearchIndexesDeclared()
				if err != nil {
					return err
				}
				what := "delete entity " + args[0] + " — its view, for every tenant"
				for _, ix := range indexes { // a tenant holds at most one index per entity
					if strings.EqualFold(ix.Entity, args[0]) {
						what += ", and search index " + ix.Name
						break
					}
				}
				if err := confirmTyped(cmd, "delete", what); err != nil {
					return err
				}
			}
			if err := c.EntityDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "deleted:", args[0])
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Skip confirmation")
	return cmd
}

func newEntityApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply an entity manifest (YAML)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.EntityApply(body)
			if err != nil {
				return err
			}
			var result struct {
				Name string `json:"name"`
				View string `json:"view"`
			}
			if json.Unmarshal(resp, &result) == nil && result.Name != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "Applied entity %q → %s\n", result.Name, result.View)
				return nil
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML entity manifest file, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
