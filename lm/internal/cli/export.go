package cli

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

// lm export — the integrator's surface over declared, managed kind:Export manifests: apply a
// definition, read it back, start a run and follow it through the ledger. Every door is
// lm_integrator-only on the hub; the CLI adds no rule of its own and passes refusals through
// (the scope refusal included) as the hub worded them.
//
// The one thing the CLI must never do is claim more than the hub said. A 202 from `run` means a
// row was ACCEPTED, not that a file was delivered — only a Completed ledger row says that,
// which is what --wait waits for.

func newExportCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "export",
		Short: "Manage kind:Export manifests and their managed runs",
	}
	cmd.AddCommand(newExportApplyCmd(), newExportListCmd(), newExportGetCmd(),
		newExportRunCmd(), newExportRetryCmd(), newExportRunsCmd(), newExportStatusCmd())
	return cmd
}

func newExportApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply a kind:Export manifest (YAML) — validate, then create or update",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			applied, err := c.ExportApply(body)
			if err != nil {
				return err
			}
			if format := outputFormat(cmd); format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, applied)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Applied export %q → entity %s, scope %s\n",
				applied.Name, applied.Entity, applied.Scope)
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "kind:Export YAML manifest, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func newExportListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List the declared exports your scopes can see",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			exports, err := c.ExportList()
			if err != nil {
				return err
			}
			return output.Print(cmd.OutOrStdout(), outputFormat(cmd),
				[]string{"NAME", "ENTITY", "SCOPE", "DESTINATION", "SCHEDULE"}, exports,
				func(e client.ExportSummary) []string {
					return []string{e.Name, e.Entity, e.Scope, e.DestinationKind, e.Schedule}
				})
		},
	}
}

func newExportGetCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "get NAME",
		Short: "Print a declared export's stored manifest (YAML)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			yaml, err := c.ExportManifest(args[0])
			if err != nil {
				return err
			}
			fmt.Fprint(cmd.OutOrStdout(), redactURLCredentials(string(yaml)))
			return nil
		},
	}
}

// urlCredentials matches the userinfo of a URL (`https://user:secret@host`). The hub refuses
// inline credential FIELDS, but a destination endpoint is free text and can still carry them.
var urlCredentials = regexp.MustCompile(`([A-Za-z][A-Za-z0-9+.\-]*://)[^/\s@'"]+@`)

// redactURLCredentials masks URL userinfo before a stored manifest reaches the terminal. It
// changes the printed text on purpose: a manifest piped back into `apply` with `***` in it fails
// loudly at the destination, which beats a secret sitting in a scrollback or a CI log.
func redactURLCredentials(s string) string {
	return urlCredentials.ReplaceAllString(s, "${1}***@")
}

// waitFlags are the options shared by the two commands that start a run.
type waitFlags struct {
	wait     bool
	timeout  time.Duration
	interval time.Duration
}

func (w *waitFlags) register(cmd *cobra.Command) {
	cmd.Flags().BoolVar(&w.wait, "wait", false,
		"wait until the hub records the run Completed or Failed (exit 1 if it failed)")
	cmd.Flags().DurationVar(&w.timeout, "timeout", 30*time.Minute,
		"with --wait: stop waiting after this long, 0 = no limit (the run itself continues on the hub)")
	cmd.Flags().DurationVar(&w.interval, "interval", 2*time.Second, "with --wait: how often to poll the ledger")
}

// deadline builds the poll interval (defaulted to 2s) and the --timeout-bounded context both
// waitForExportRun and waitForSearchRebuild derive from a caller's ctx (code review finding: this
// ceremony was duplicated verbatim in each). The domain-specific polling loop and its "why did the
// wait end" wording stay with each caller — only this construction is identical between them.
func (w *waitFlags) deadline(ctx context.Context) (waitCtx context.Context, cancel context.CancelFunc, interval time.Duration) {
	interval = w.interval
	if interval <= 0 {
		interval = 2 * time.Second
	}
	waitCtx, cancel = ctx, func() {}
	if w.timeout > 0 {
		waitCtx, cancel = context.WithTimeout(ctx, w.timeout)
	}
	return waitCtx, cancel, interval
}

func newExportRunCmd() *cobra.Command {
	var w waitFlags
	cmd := &cobra.Command{
		Use:   "run NAME",
		Short: "Start a managed run of an export (answers once the hub has claimed it)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			return startExportRun(cmd, name, w, func(ctx context.Context, c *client.Client) (string, error) {
				return c.ExportRunStart(ctx, name)
			})
		},
	}
	w.register(cmd)
	return cmd
}

func newExportRetryCmd() *cobra.Command {
	var w waitFlags
	cmd := &cobra.Command{
		Use:   "retry NAME RUN_ID",
		Short: "Start a new attempt of a Failed run (same artifact name, regenerated from scratch)",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			return startExportRun(cmd, name, w, func(ctx context.Context, c *client.Client) (string, error) {
				id, err := resolveExportRunID(ctx, c, name, args[1])
				if err != nil {
					return "", err
				}
				return c.ExportRunRetry(ctx, name, id)
			})
		},
	}
	w.register(cmd)
	return cmd
}

// startExportRun is the body `run` and `retry` share: start, report what the hub said, and with
// --wait follow the ledger to a terminal status.
func startExportRun(cmd *cobra.Command, name string, w waitFlags,
	start func(context.Context, *client.Client) (string, error)) error {
	c, err := ClientFromCmd(cmd)
	if err != nil {
		return err
	}
	ctx, stop := interruptible(cmd.Context())
	defer stop()

	runID, err := start(ctx, c)
	if err != nil {
		if ctx.Err() != nil {
			// The request may have reached the hub before the interrupt: say neither yes nor no.
			return fmt.Errorf("interrupted before the hub answered — the run may or may not have started; check: lm export runs %s", name)
		}
		if inFlight, ok := client.InFlightExportRun(err); ok {
			if inFlight != "" {
				return fmt.Errorf("%w — follow it with: lm export status %s %s", err, name, inFlight)
			}
			return fmt.Errorf("%w — see: lm export runs %s", err, name)
		}
		return err
	}

	if !w.wait {
		if format := outputFormat(cmd); format != "table" {
			return output.PrintObject(cmd.OutOrStdout(), format,
				map[string]string{"runId": runID, "status": "accepted"})
		}
		fmt.Fprintf(cmd.OutOrStdout(), "export run %s accepted\n", runID)
		fmt.Fprintf(cmd.ErrOrStderr(), "• the hub accepted the run; delivery is not confirmed yet — follow it with: lm export status %s %s\n",
			name, runID)
		return nil
	}

	fmt.Fprintf(cmd.ErrOrStderr(), "• export run %s accepted — waiting for the hub to record its outcome\n", runID)
	run, err := waitForExportRun(ctx, c, name, runID, w)
	if err != nil {
		return err
	}
	if err := printExportRun(cmd, run); err != nil {
		return err
	}
	if run.Status == client.ExportRunFailed {
		return exportRunFailure(run)
	}
	return nil
}

// waitForExportRun polls the ledger until the run leaves Queued and Running. It never reports an outcome the
// hub has not recorded: a timeout, an interrupt or a lost poll all come back as errors that say
// the run continues on the hub.
func waitForExportRun(ctx context.Context, c *client.Client, name, runID string, w waitFlags) (*client.ExportRun, error) {
	follow := fmt.Sprintf("follow it with: lm export status %s %s", name, runID)
	// --timeout is a real deadline on the whole wait, polls included: a blocked read cannot
	// outlive it, and a timeout shorter than the interval still waits out the timeout.
	waitCtx, cancel, interval := w.deadline(ctx)
	defer cancel()
	// Which of the two ended the wait: an interrupt (the caller's ctx) or the --timeout.
	ended := func() error {
		if ctx.Err() != nil {
			return fmt.Errorf("stopped waiting — run %s continues on the hub (interrupting lm does not cancel it); %s",
				runID, follow)
		}
		return fmt.Errorf("gave up waiting after %s — run %s is still in flight on the hub; %s",
			w.timeout, runID, follow)
	}
	timer := time.NewTimer(interval)
	defer timer.Stop()
	for {
		run, err := c.ExportRunGet(waitCtx, name, runID)
		if waitCtx.Err() != nil {
			return nil, ended()
		}
		if err != nil {
			return nil, fmt.Errorf("lost track of run %s: %w — it may still be running; %s", runID, err, follow)
		}
		if run.Status != client.ExportRunRunning && run.Status != client.ExportRunQueued {
			return run, nil
		}
		timer.Reset(interval)
		select {
		case <-waitCtx.Done():
			return nil, ended()
		case <-timer.C:
		}
	}
}

func exportRunFailure(run *client.ExportRun) error {
	if run.FailureCode == "" {
		return fmt.Errorf("export run %s failed", run.ID)
	}
	return fmt.Errorf("export run %s failed (%s): %s", run.ID, run.FailureCode, run.FailureReason)
}

func newExportRunsCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "runs NAME",
		Short: "List an export's runs, newest first",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ctx, stop := interruptible(cmd.Context())
			defer stop()
			runs, err := c.ExportRuns(ctx, args[0], pageLimit(cmd))
			if err != nil {
				return err
			}
			return printPage(cmd, exportRunHeaders, runs, exportRunRow)
		},
	}
	addPageLimitFlag(cmd, 20, client.ExportRunsPageCap, "max runs to list")
	return cmd
}

var exportRunHeaders = []string{"ID", "STATUS", "TRIGGER", "ATTEMPT", "ROWS", "BYTES", "STARTED", "ENDED", "FAILURE"}

func exportRunRow(r client.ExportRun) []string {
	return []string{
		shortID(r.ID), r.Status, r.Trigger, strconv.Itoa(r.Attempt),
		optInt(r.RowCount), optInt(r.ByteCount),
		output.HumanTime(r.StartedAt), output.HumanTime(r.EndedAt), r.FailureCode,
	}
}

func newExportStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status NAME [RUN_ID]",
		Short: "Show one run of an export — its latest when no id is given",
		Args:  cobra.RangeArgs(1, 2),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ctx, stop := interruptible(cmd.Context())
			defer stop()
			name := args[0]
			var run *client.ExportRun
			if len(args) == 1 {
				runs, err := c.ExportRuns(ctx, name, 1)
				if err != nil {
					return err
				}
				if len(runs) == 0 {
					return fmt.Errorf("export %q has no runs yet — start one with: lm export run %s", name, name)
				}
				run = &runs[0]
			} else {
				var err error
				if run, err = findExportRun(ctx, c, name, args[1]); err != nil {
					return err
				}
			}
			if err := printExportRun(cmd, run); err != nil {
				return err
			}
			// Inspecting a failed run succeeded, so the exit stays 0; the reason goes to stderr,
			// the same contract as `lm run get`.
			if run.Status == client.ExportRunFailed && outputFormat(cmd) == "table" {
				fmt.Fprintf(cmd.ErrOrStderr(), "✖ %v — retry with: lm export retry %s %s\n",
					exportRunFailure(run), name, shortID(run.ID))
			}
			return nil
		},
	}
}

// printExportRun renders one run: the whole object for -o json|yaml, a key/value block for a
// human — a receipt (sha256, location) does not fit a table row.
func printExportRun(cmd *cobra.Command, run *client.ExportRun) error {
	if format := outputFormat(cmd); format != "table" {
		return output.PrintObject(cmd.OutOrStdout(), format, run)
	}
	p := newPadWriter()
	line := func(k, v string) {
		if v != "" {
			p.line(k, v)
		}
	}
	line("Run", run.ID)
	line("Export", run.Export)
	line("Entity", run.Entity)
	line("Scope", run.Scope)
	line("Destination", run.DestinationKind)
	line("Status", run.Status)
	line("Trigger", run.Trigger)
	line("Attempt", strconv.Itoa(run.Attempt))
	line("Retry of", run.RetryOf)
	line("Occurrence", output.HumanTimeSeconds(run.Occurrence))
	line("Started", output.HumanTimeSeconds(run.StartedAt))
	line("Ended", output.HumanTimeSeconds(run.EndedAt))
	line("Rows", optInt(run.RowCount))
	line("Bytes", optInt(run.ByteCount))
	line("SHA-256", run.Sha256)
	line("Location", run.Location)
	line("Version", run.VersionID)
	line("ETag", run.ETag)
	line("Failure", run.FailureCode)
	line("Reason", run.FailureReason)
	p.flush(cmd.OutOrStdout())
	return nil
}

// resolveExportRunID turns an abbreviated run id (what `lm export runs` shows) back into the full
// one, against that export's own runs.
func resolveExportRunID(ctx context.Context, c *client.Client, name, arg string) (string, error) {
	if len(arg) == uuidLen {
		return arg, nil
	}
	run, err := findExportRun(ctx, c, name, arg)
	if err != nil {
		return "", err
	}
	return run.ID, nil
}

// findExportRun reads one run by full or abbreviated id. A prefix resolves against the listed
// page, which already holds the whole row — no second read for it.
func findExportRun(ctx context.Context, c *client.Client, name, arg string) (*client.ExportRun, error) {
	if len(arg) == uuidLen {
		return c.ExportRunGet(ctx, name, arg)
	}
	runs, err := c.ExportRuns(ctx, name, client.ExportRunsPageCap)
	if err != nil {
		return nil, err
	}
	ids := make([]string, 0, len(runs))
	for _, r := range runs {
		ids = append(ids, r.ID)
	}
	id, err := resolveIDPrefix("export run", "lm export runs "+name, ids, arg)
	if err != nil {
		return nil, err
	}
	for i := range runs {
		if runs[i].ID == id {
			return &runs[i], nil
		}
	}
	return nil, fmt.Errorf("no export run matches %q", arg)
}
