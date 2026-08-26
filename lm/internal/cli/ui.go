package cli

import (
	"github.com/icreated/lumnik-open/lm/internal/tui"
	"github.com/spf13/cobra"
)

func newUiCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "ui",
		Short: "Launch the interactive TUI (k9s-style)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			return tui.Run(c)
		},
	}
}
