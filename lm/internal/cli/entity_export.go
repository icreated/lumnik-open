package cli

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
)

// newEntityExportCmd downloads one entity's canonical CSV snapshot through the hub's one-shot
// door (#682): no kind:Export contract, no destination, no run ledger — the hub audits it as
// data egress and the file lands locally.
//
// A file target is written to a temporary file next to it and published only after the hub's
// body has arrived whole, so an interrupted or failed download never leaves a file that looks
// complete. An existing file is refused unless --force says to replace it. `-o -` streams to
// stdout instead: nothing there can be withheld, so a failure is reported by the exit status
// and the error alone.
func newEntityExportCmd() *cobra.Command {
	var outFile string
	var force bool
	cmd := &cobra.Command{
		Use:   "export NAME -o FILE",
		Short: "Download an entity's canonical CSV snapshot to a local file (or - for stdout)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if outFile == "" {
				return errors.New("-o FILE is required (use -o - to stream to stdout)")
			}
			if outFile != "-" {
				if err := checkDownloadTarget(outFile, force); err != nil {
					return err
				}
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ctx, stop := interruptible(cmd.Context())
			defer stop()
			name := args[0]

			if outFile == "-" {
				n, err := c.EntitySnapshotDownload(ctx, name, func() (io.Writer, error) {
					return machineOut(cmd), nil // the CSV bytes, never the textsafe rewrite of them
				})
				if err != nil {
					if n > 0 {
						return fmt.Errorf("%w — the %d bytes already written to stdout are incomplete", err, n)
					}
					return err
				}
				return nil
			}

			sum := sha256.New()
			var tmp *os.File
			published := false
			defer func() {
				if tmp != nil && !published {
					_ = tmp.Close()
					if err := removeTemp(tmp.Name()); err != nil {
						fmt.Fprintf(cmd.ErrOrStderr(), "lm: could not remove the partial download %s (%v) — delete it by hand\n",
							tmp.Name(), err)
					}
				}
			}()
			n, err := c.EntitySnapshotDownload(ctx, name, func() (io.Writer, error) {
				// Same directory as the target, so publishing is a rename, not a copy.
				f, err := os.CreateTemp(filepath.Dir(outFile), "."+filepath.Base(outFile)+".*.part")
				if err != nil {
					return nil, err
				}
				tmp = f
				return io.MultiWriter(f, sum), nil
			})
			if err != nil {
				if ctx.Err() != nil {
					return fmt.Errorf("download interrupted — %s was not written", outFile)
				}
				return err
			}
			if err := tmp.Sync(); err != nil {
				return err
			}
			if err := tmp.Close(); err != nil {
				return err
			}
			if err := publishDownload(tmp.Name(), outFile, force); err != nil {
				return err
			}
			published = true
			// The link left the temporary name pointing at the same complete snapshot: success
			// must not be claimed while a second copy of the data sits under a hidden name.
			if err := removeTemp(tmp.Name()); err != nil {
				return fmt.Errorf("%s was written in full, but its temporary copy %s could not be removed (%v) — delete it by hand",
					outFile, tmp.Name(), err)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "%s: %d bytes written to %s (sha256 %s)\n",
				name, n, outFile, hex.EncodeToString(sum.Sum(nil)))
			return nil
		},
	}
	// Same-name local flag SHADOWS the root's -o/--output format, as `view get` does: the
	// snapshot is CSV, so there is no table/json/yaml rendering to lose.
	cmd.Flags().StringVarP(&outFile, "output", "o", "", "file to write the CSV to, or - for stdout (required)")
	cmd.Flags().BoolVar(&force, "force", false, "replace FILE if it already exists")
	return cmd
}

// checkDownloadTarget refuses, before the hub is asked for anything, a target that could never
// be published: an existing file (without --force), a parent that is not a usable directory, or
// a path the stat itself cannot answer for (permission denied, a file used as a directory).
// The snapshot is real egress on the hub; a failure knowable up front must not cost one.
func checkDownloadTarget(outFile string, force bool) error {
	_, err := os.Lstat(outFile)
	switch {
	case err == nil && !force:
		return fmt.Errorf("%s already exists — refusing to overwrite it (use --force to replace it)", outFile)
	case err != nil && !errors.Is(err, fs.ErrNotExist):
		return fmt.Errorf("cannot write %s: %w", outFile, err)
	}
	dir := filepath.Dir(outFile)
	fi, err := os.Stat(dir)
	if err != nil {
		return fmt.Errorf("cannot write %s: %w", outFile, err)
	}
	if !fi.IsDir() {
		return fmt.Errorf("cannot write %s: %s is not a directory", outFile, dir)
	}
	return nil
}

// linkFile and removeFile are os.Link and os.Remove, swappable so a test can play a filesystem
// without hard links, or one that refuses an unlink.
var (
	linkFile   = os.Link
	removeFile = os.Remove
)

// removeTemp deletes a temporary download, retrying once (a transient network-filesystem error
// deserves a second try). A name already gone — renamed into place by --force — is not an error.
func removeTemp(name string) error {
	err := removeFile(name)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		err = removeFile(name)
	}
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

// publishDownload gives a complete temporary download its final name; the caller removes the
// temporary name afterwards (a link leaves it in place). Without force it
// links rather than renames: a hard link fails if the name exists, so a file created there
// while the download ran is not overwritten either. Where hard links are unavailable there is
// no portable no-replace publish, and check-then-rename would leave a window in which another
// process's file is clobbered — so that case fails closed and leaves the choice to --force.
func publishDownload(tmp, final string, force bool) error {
	if force {
		return os.Rename(tmp, final)
	}
	err := linkFile(tmp, final)
	if err == nil {
		return nil
	}
	if errors.Is(err, fs.ErrExist) {
		return fmt.Errorf("%s appeared while downloading — refusing to overwrite it (use --force to replace it)", final)
	}
	return fmt.Errorf("cannot publish %s without risking an overwrite (%v) — the download was discarded; "+
		"use --force to allow replacing, or write to a filesystem that supports hard links", final, err)
}
