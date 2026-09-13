package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newValidateCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "validate",
		Short: "Validate a connector manifest without applying it",
		Long: `Posts the manifest to the server's validator and prints per-field errors.

Example:
  lm validate -f stripe.yaml`,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if file == "" {
				return fmt.Errorf("flag -f required")
			}
			body, err := readManifest(cmd, file)
			if err != nil {
				return fmt.Errorf("read %s: %w", file, err)
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			result, err := c.ValidateManifest(body)
			if err != nil {
				return err
			}
			return output.FormatValidation(cmd.OutOrStdout(), manifestLabel(file), rerunCommand(file), result)
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "manifest file (YAML or JSON), or - for stdin")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
