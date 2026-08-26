package main

import (
	"os"

	"github.com/icreated/lumnik-open/lm/internal/cli"
)

func main() {
	os.Exit(cli.Run(cli.NewRootCmd(), os.Stdout, os.Stderr))
}
