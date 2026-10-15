package cli

import (
	"context"
	"fmt"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

// lm search — the integrator's surface over #671/#703's projection pipeline: declare or delete a
// kind:SearchIndex (#828), query it (#836), read status/lag/document count, trigger an on-demand
// rebuild, request its cooperative cancellation. Apart from `query` (lm_user), every door is
// lm_integrator-only on the hub; the CLI adds no rule of its own.

func newSearchCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "search",
		Short: "Declare search indexes, inspect and drive the projection pipeline (#703)",
	}
	cmd.AddCommand(newSearchApplyCmd(), newSearchRmCmd(), newSearchQueryCmd(), newSearchStatusCmd(), newSearchRebuildCmd(), newSearchCancelCmd())
	return cmd
}

func newSearchApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply a kind:SearchIndex manifest (YAML) — validate, then create or update",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			applied, err := c.SearchIndexApply(body)
			if err != nil {
				return err
			}
			if format := outputFormat(cmd); format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, applied)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Applied search index %q → entity %s (%s)\n",
				applied.Name, applied.Entity, applied.Status)
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "kind:SearchIndex YAML manifest, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func newSearchRmCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rm NAME",
		Short: "Delete a declared search index (the hub queues the purge of its projected documents)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.SearchIndexDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "search index %s deleted\n", args[0])
			return nil
		},
	}
}

func newSearchStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status [INDEX]",
		Short: "Show every scope-visible index, or one index's full detail",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if len(args) == 1 {
				s, err := c.SearchStatus(args[0])
				if err != nil {
					return err
				}
				return printSearchStatus(cmd, s)
			}
			list, err := c.SearchList()
			if err != nil {
				return err
			}
			return output.Print(cmd.OutOrStdout(), outputFormat(cmd),
				[]string{"NAME", "STATUS", "LAG(s)", "DOCUMENTS", "LAST REBUILD"}, list,
				func(s client.SearchIndexStatus) []string {
					return []string{s.Name, s.Status, fmt.Sprintf("%d", s.LagSeconds),
						optInt(s.DocumentCount), lastRebuildOutcome(s.LastRebuild)}
				})
		},
	}
}

func lastRebuildOutcome(r *client.SearchRunDone) string {
	if r == nil {
		return ""
	}
	return r.Outcome
}

func printSearchStatus(cmd *cobra.Command, s *client.SearchIndexStatus) error {
	if format := outputFormat(cmd); format != "table" {
		return output.PrintObject(cmd.OutOrStdout(), format, s)
	}
	p := newPadWriter()
	p.line("Name", s.Name)
	p.line("Status", s.Status)
	p.line("Live generation", optStr(s.LiveGeneration))
	p.line("Documents", optInt(s.DocumentCount))
	if s.DocumentCountStale {
		p.line("Documents stale", "yes")
	}
	p.line("Lag", fmt.Sprintf("%ds", s.LagSeconds))
	if s.ActiveRun != nil {
		p.line("Active run", fmt.Sprintf("%s (%s)", s.ActiveRun.RunID, s.ActiveRun.Phase))
	}
	if s.LastRebuild != nil {
		p.line("Last rebuild", fmt.Sprintf("%s (%s)", s.LastRebuild.RunID, s.LastRebuild.Outcome))
	}
	if s.LastError != "" {
		p.line("Last error", s.LastError)
	}
	if s.Invalid {
		p.line("Invalid", s.InvalidReason)
	}
	p.flush(cmd.OutOrStdout())
	return nil
}

func newSearchRebuildCmd() *cobra.Command {
	var w waitFlags
	cmd := &cobra.Command{
		Use:   "rebuild INDEX",
		Short: "Trigger a full rebuild of an index",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ctx, stop := interruptible(cmd.Context())
			defer stop()

			runID, err := c.SearchRebuildStart(ctx, name)
			if err != nil {
				return err
			}
			if !w.wait {
				if format := outputFormat(cmd); format != "table" {
					return output.PrintObject(cmd.OutOrStdout(), format, map[string]string{"runId": runID})
				}
				fmt.Fprintf(cmd.OutOrStdout(), "rebuild %s started\n", runID)
				return nil
			}
			return waitForSearchRebuild(ctx, cmd, c, name, runID, w)
		},
	}
	w.register(cmd)
	return cmd
}

// waitForSearchRebuild polls status and decides purely from where runID appears in activeRun/
// lastRebuild — never from the index's own status (design doc §7's four-case rule). It takes ctx
// from the caller (a single interruptible() derivation shared with the start request, exactly
// like startExportRun threading its own ctx into waitForExportRun) rather than deriving a second,
// independent one — that second derivation is what would make an interrupt and a --timeout
// indistinguishable at the end of the wait.
func waitForSearchRebuild(ctx context.Context, cmd *cobra.Command, c *client.Client, name, runID string, w waitFlags) error {
	follow := fmt.Sprintf("check: lm search status %s", name)
	// --timeout is a real deadline on the whole wait, polls included — mirrors waitForExportRun.
	waitCtx, cancel, interval := w.deadline(ctx)
	defer cancel()
	// Which of the two ended the wait: an interrupt (the caller's ctx) or the --timeout.
	ended := func() error {
		if ctx.Err() != nil {
			return fmt.Errorf("stopped waiting — rebuild %s continues on the hub (interrupting lm does not cancel it); %s",
				runID, follow)
		}
		return fmt.Errorf("gave up waiting after %s — rebuild %s continues on the hub; %s",
			w.timeout, runID, follow)
	}

	timer := time.NewTimer(0) // poll immediately once
	defer timer.Stop()
	for {
		select {
		case <-waitCtx.Done():
			return ended()
		case <-timer.C:
		}
		s, err := c.SearchStatus(name)
		if waitCtx.Err() != nil {
			return ended()
		}
		if err != nil {
			return fmt.Errorf("lost track of rebuild %s: %w — it may still be running; %s", runID, err, follow)
		}
		if s.ActiveRun != nil && s.ActiveRun.RunID == runID {
			timer.Reset(interval)
			continue // still running; ignore whatever lastRebuild currently shows
		}
		if s.LastRebuild != nil && s.LastRebuild.RunID == runID {
			if err := printSearchStatus(cmd, s); err != nil {
				return err
			}
			if s.LastRebuild.Outcome != "ready" {
				return fmt.Errorf("rebuild %s ended %s", runID, s.LastRebuild.Outcome)
			}
			return nil
		}
		// Neither field carries runID: a DIFFERENT run is now active or already terminal ->
		// superseded before this CLI ever observed it in either field.
		if s.ActiveRun != nil || s.LastRebuild != nil {
			return fmt.Errorf("lost track of run %s — a different run is now visible; %s", runID, follow)
		}
		// Neither field shows ANY run yet — the narrow window right after 202; keep waiting.
		timer.Reset(interval)
	}
}

func newSearchCancelCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "cancel INDEX",
		Short: "Request cancellation of an index's active rebuild",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ctx, stop := interruptible(cmd.Context())
			defer stop()
			if err := c.SearchCancel(ctx, args[0]); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "cancellation requested")
			return nil
		},
	}
}
