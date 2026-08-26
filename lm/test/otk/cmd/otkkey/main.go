//go:build otk

// otkkey prints an RSA public key PEM (stdout) and writes the matching private
// key PEM to $OTK_KEY_FILE, so the hub (public) and the OTK harness (private) can
// share one keypair across processes.
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "otkkey:", err)
		os.Exit(1)
	}
}

// run does the work and returns errors instead of panicking, so CI and users get
// a clear message and a non-zero exit rather than a stack trace.
func run() error {
	keyFile := os.Getenv("OTK_KEY_FILE")
	if keyFile == "" {
		return fmt.Errorf("set OTK_KEY_FILE to the path the private key should be written to")
	}
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return fmt.Errorf("generate RSA key: %w", err)
	}
	privDER := x509.MarshalPKCS1PrivateKey(priv)
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: privDER}), 0o600); err != nil {
		return fmt.Errorf("write private key to %s: %w", keyFile, err)
	}
	pubDER, err := x509.MarshalPKIXPublicKey(&priv.PublicKey)
	if err != nil {
		return fmt.Errorf("marshal public key: %w", err)
	}
	fmt.Print(string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: pubDER})))
	return nil
}
