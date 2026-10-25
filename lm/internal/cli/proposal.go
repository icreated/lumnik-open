package cli

import (
	"errors"
	"fmt"
	"io/fs"

	"github.com/icreated/lumnik-open/lm/internal/newfile"
	"github.com/icreated/lumnik-open/lm/internal/proposal"
	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
)

// newProposalCmd is the integrator's side of a proposal dossier. It only ever writes a draft: it
// never contacts the hub (the checked dossier comes from POST /api/proposal/check, by curl) and
// never applies — `lm workflow apply` stays her own, separate gesture.
func newProposalCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "proposal",
		Short: "Work from a proposal dossier — drafts only, nothing is contacted or applied",
	}

	var t proposal.Target
	var outFile string
	draft := &cobra.Command{
		Use:   "draft DOSSIER",
		Short: "Write a draft kind:Workflow manifest from a proposal dossier (never applies)",
		Long: `Write the start of a kind:Workflow manifest from a proposal dossier file (or - for stdin).

lm does not contact the hub and does not apply anything. What the dossier cannot know — the
workflow's name, its scope, the table, the status column, the key, the threshold — comes from the
flags; a value you do not give is left out, with a comment saying where it goes. The states graph is
never generated: edit it in. The result is not an applicable manifest until you have.

Every condition the dossier carries is listed with what it says, in the order that needs you
first. A hub confirmation or refutation is reported as carried by the dossier, not as observed now.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			// Visit walks only the flags that were set: an explicitly empty one would silently read
			// as "left out" (or, for -o, as stdout), so it is refused instead.
			var empty error
			cmd.Flags().Visit(func(f *pflag.Flag) {
				if f.Value.String() == "" && empty == nil {
					empty = fmt.Errorf("--%s is empty: leave the flag out instead", f.Name)
				}
			})
			if empty != nil {
				return empty
			}
			if err := t.Validate(); err != nil {
				return err
			}
			raw, err := readManifest(cmd, args[0])
			if err != nil {
				return err
			}
			text, err := proposal.Decode(raw)
			if err != nil {
				return err
			}
			d, err := proposal.Read(text)
			if err != nil {
				return err
			}
			yaml, skipped, err := proposal.Draft(d, t)
			if err != nil {
				return err
			}
			for _, rule := range skipped {
				fmt.Fprintf(cmd.ErrOrStderr(), "not drafted: the lead for rule %s\n", rule)
			}
			if outFile == "" {
				_, err = fmt.Fprint(cmd.OutOrStdout(), yaml)
				return err
			}
			// An edited draft is never replaced: the message says so, and says it is that, not a failed write.
			err = newfile.Write(outFile, []byte(yaml))
			if errors.Is(err, fs.ErrExist) {
				return fmt.Errorf("%s already exists — not overwritten (an edited draft is never replaced)", outFile)
			}
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "draft written to %s — complete it, then `lm workflow apply -f %s`\n", outFile, shellArg(outFile))
			return nil
		},
	}
	draft.Flags().StringVar(&t.Name, "name", "", "metadata.name — yours to choose (a-z, 0-9, -); apply updates the workflow of this name")
	draft.Flags().StringVar(&t.Scope, "scope", "", "spec.scope — the scope that may read the lifecycle")
	draft.Flags().StringVar(&t.Table, "table", "", "spec.on.table, as in connector.t_orders")
	draft.Flags().StringVar(&t.Column, "column", "", "spec.on.column — the status column")
	draft.Flags().StringVar(&t.Key, "key", "", "spec.on.key — the column that identifies one document (needed for a dormancy)")
	draft.Flags().StringVar(&t.After, "after", "", "spec.dormancy.after, as 14d, 36h or 90m")
	draft.Flags().StringVarP(&outFile, "output", "o", "", "write the draft to this new file instead of stdout (never overwrites)")
	cmd.AddCommand(draft)
	return cmd
}
