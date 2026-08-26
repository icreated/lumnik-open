package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply a REST connector manifest (YAML)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readYamlFile(file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.ApplyManifest(body)
			if err != nil {
				var apiErr *client.APIError
				if errors.As(err, &apiErr) && apiErr.Status == 400 {
					var vr client.ValidationResult
					if json.Unmarshal([]byte(apiErr.Body), &vr) == nil &&
						(len(vr.Errors) > 0 || len(vr.Warnings) > 0) {
						return output.FormatValidation(cmd.OutOrStdout(), file, &vr)
					}
				}
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML manifest file (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

func readYamlFile(path string) ([]byte, error) {
	return os.ReadFile(path)
}
