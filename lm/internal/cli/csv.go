package cli

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

func newCsvCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "csv",
		Short: "Quick CSV operations (one-shot ingest)",
	}
	cmd.AddCommand(newCsvIngestCmd())
	return cmd
}

func newCsvIngestCmd() *cobra.Command {
	var name string
	var scopes []string
	var limit int

	// Remote transport flags
	var sftpKey, sftpPwd string
	var sftpPort int
	var strictHostKey bool
	var hostKeyFingerprint string
	var s3Region, s3Endpoint, s3AccessKey, s3SecretKey string
	var pattern, suffix string

	cmd := &cobra.Command{
		Use:   "ingest <file-or-url>",
		Short: "One-shot CSV ingest: infer schema, write to ext.*, optionally index for RAG",
		Long:  "Ingest a CSV from a local path, sftp://..., or s3://...",
		Args:  cobra.ExactArgs(1),
		RunE: func(c *cobra.Command, args []string) error {
			cli, err := ClientFromCmd(c)
			if err != nil {
				return err
			}
			arg := args[0]
			// --scope is repeatable: the first goes in the hub's `scope` form field (it is the one
			// the rows carry), the rest ride `tags`. Same wire as before, one flag instead of two.
			// An empty scope is the hub's to default (CsvIngestService.connectorTags → "system"),
			// not ours to guess.
			var scopeVal string
			var tags []string
			if len(scopes) > 0 {
				scopeVal, tags = scopes[0], scopes[1:]
			}

			if strings.HasPrefix(arg, "sftp://") || strings.HasPrefix(arg, "s3://") {
				transport, err := buildTransport(arg, sftpKey, sftpPwd, s3Region, s3Endpoint, s3AccessKey, s3SecretKey, pattern, suffix, sftpPort, strictHostKey, hostKeyFingerprint)
				if err != nil {
					return err
				}
				result, err := cli.CsvIngestRemote(transport, name, scopeVal, tags, limit)
				if err != nil {
					return err
				}
				result.SourceFile = arg
				printCsvIngestSummary(c.OutOrStdout(), result)
				return nil
			}

			// Local file path (existing behavior)
			f, err := os.Open(arg)
			if err != nil {
				return err
			}
			defer f.Close()
			result, err := cli.CsvIngest(f, arg, client.CsvIngestOpts{
				Name:  name,
				Scope: scopeVal,
				Tags:  tags,
				Limit: limit,
			})
			if err != nil {
				return err
			}
			printCsvIngestSummary(c.OutOrStdout(), result)
			return nil
		},
	}

	cmd.Flags().StringVar(&name, "name", "", "connector name; a globbed sftp/s3 source must match a single file (default = filename basename)")
	cmd.Flags().StringSliceVar(&scopes, "scope", nil, "métier scope(s) the ingested table is askable in (repeatable; the first is the one the rows carry; default: system)")
	cmd.Flags().IntVar(&limit, "limit", -1, "max rows (default: all)")

	// SFTP flags
	cmd.Flags().StringVar(&sftpKey, "key-secret", "", "SFTP: secret name for private SSH key")
	cmd.Flags().StringVar(&sftpPwd, "password-secret", "", "SFTP: secret name for password")
	cmd.Flags().IntVar(&sftpPort, "port", 22, "SFTP: port")
	cmd.Flags().BoolVar(&strictHostKey, "strict-host-key", true,
		"SFTP: refuse to connect unless the host key matches --host-key-fingerprint")
	cmd.Flags().StringVar(&hostKeyFingerprint, "host-key-fingerprint", "",
		"SFTP: expected host key, as ssh-keygen -lf prints it (SHA256:...)")
	cmd.Flags().StringVar(&pattern, "pattern", "*.csv", "SFTP: glob pattern under URL path")

	// S3 flags
	cmd.Flags().StringVar(&s3Region, "region", "", "S3: region (required for s3://)")
	cmd.Flags().StringVar(&s3Endpoint, "endpoint", "", "S3: custom endpoint (MinIO/R2/GCS)")
	cmd.Flags().StringVar(&s3AccessKey, "access-key-secret", "", "S3: secret name for access key id")
	cmd.Flags().StringVar(&s3SecretKey, "secret-key-secret", "", "S3: secret name for secret access key")
	cmd.Flags().StringVar(&suffix, "suffix", "", "S3: filter objects by suffix")

	return cmd
}

func printCsvIngestSummary(w io.Writer, r *client.CsvIngestResult) {
	if r.Refreshed {
		fmt.Fprintf(w, "Re-ingested %s into %s — one more breath (rows dedup by key)\n", r.SourceFile, r.TableName)
	} else {
		fmt.Fprintf(w, "Ingested %s into %s\n", r.SourceFile, r.TableName)
	}
	fmt.Fprintf(w, "  • rows written:    %d\n", r.RowsWritten)
	fmt.Fprintf(w, "  • promoted cols:   %d\n", len(r.PromoteColumns))
	for _, pc := range r.PromoteColumns {
		fmt.Fprintf(w, "      - %s (%s)\n", pc.Name, pc.Type)
	}
	if len(r.Warnings) > 0 {
		fmt.Fprintln(w, "  • warnings:")
		for _, msg := range r.Warnings {
			fmt.Fprintf(w, "      - %s\n", msg)
		}
	}
	// The hub reports which scopes the ingest made askable (spec 2026-09-02: schema card +
	// descriptor chunk ride the ingest itself) AND whether this build carries the chat door.
	// Both must be true before promising: an older hub reports no scopes, and a doorless
	// build keeps the plain summary — the schema card it builds silently is our accounting,
	// not the reader's, and a paid feature's absence is not announced (the GraphQL rule).
	if len(r.AskableScopes) > 0 && r.ChatAvailable {
		scope := r.AskableScopes[0]
		fmt.Fprintf(w, "\n  • askable:         scope '%s' — try: lm ask --scope %s \"how many rows are in %s?\"\n", scope, scope, r.TableName)
		fmt.Fprintf(w, "A dormant connector is registered — see `lm connector list`.\n")
		return
	}
	fmt.Fprintf(w, "\nA one-shot: no run recorded, nothing indexed for chat.\n")
	fmt.Fprintf(w, "The rows are in %s, and a dormant connector is registered — see `lm connector list`.\n", r.TableName)
}
