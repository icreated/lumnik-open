package client

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"os"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// TLSConfigFor builds the TLS config a context asks for: nil (system trust), an
// added internal CA (ca-file), or verification disabled. ca-file beats
// insecure-skip-verify when both are set — trusting a CA is the safer intent.
// Exported so the login package's HTTP client builder shares this exact logic
// instead of duplicating it.
func TLSConfigFor(ctx config.Context) (*tls.Config, error) {
	if ctx.CAFile != "" {
		pem, err := os.ReadFile(ctx.CAFile)
		if err != nil {
			return nil, fmt.Errorf("ca-file: %w", err)
		}
		pool, err := x509.SystemCertPool()
		if err != nil {
			pool = x509.NewCertPool()
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("ca-file %s: no PEM certificates found", ctx.CAFile)
		}
		return &tls.Config{RootCAs: pool}, nil
	}
	if ctx.InsecureSkipVerify {
		return &tls.Config{InsecureSkipVerify: true}, nil
	}
	return nil, nil
}
