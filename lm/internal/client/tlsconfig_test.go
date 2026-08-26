package client

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// selfSignedCAFile writes a freshly-generated self-signed cert (PEM) to a temp
// file and returns its path — stands in for an internal CA an operator points
// ca-file at.
func selfSignedCAFile(t *testing.T) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test-internal-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	path := filepath.Join(t.TempDir(), "ca.pem")
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("create ca file: %v", err)
	}
	defer f.Close()
	if err := pem.Encode(f, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		t.Fatalf("pem encode: %v", err)
	}
	return path
}

func TestTLSConfigFor_caFile_addsRootCAs(t *testing.T) {
	path := selfSignedCAFile(t)
	tlsCfg, err := TLSConfigFor(config.Context{CAFile: path})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tlsCfg == nil || tlsCfg.RootCAs == nil {
		t.Fatal("expected a TLS config with RootCAs populated")
	}
}

func TestTLSConfigFor_missingFile_isError(t *testing.T) {
	_, err := TLSConfigFor(config.Context{CAFile: "/no/such/file.pem"})
	if err == nil {
		t.Fatal("expected an error for a missing ca-file")
	}
	if !strings.Contains(err.Error(), "ca-file") {
		t.Errorf("error should mention ca-file, got: %v", err)
	}
}

func TestTLSConfigFor_garbagePEM_isError(t *testing.T) {
	path := filepath.Join(t.TempDir(), "garbage.pem")
	if err := os.WriteFile(path, []byte("not a certificate"), 0o600); err != nil {
		t.Fatalf("write garbage file: %v", err)
	}
	_, err := TLSConfigFor(config.Context{CAFile: path})
	if err == nil {
		t.Fatal("expected an error for a garbage PEM file")
	}
	if !strings.Contains(err.Error(), "no PEM certificates") {
		t.Errorf("error should mention no PEM certificates, got: %v", err)
	}
}

func TestTLSConfigFor_neitherSet_isNil(t *testing.T) {
	tlsCfg, err := TLSConfigFor(config.Context{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tlsCfg != nil {
		t.Errorf("expected nil TLS config, got: %+v", tlsCfg)
	}
}

func TestTLSConfigFor_insecureOnly_disablesVerification(t *testing.T) {
	tlsCfg, err := TLSConfigFor(config.Context{InsecureSkipVerify: true})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tlsCfg == nil || !tlsCfg.InsecureSkipVerify {
		t.Fatal("expected InsecureSkipVerify to be true")
	}
}

func TestTLSConfigFor_bothSet_caFileWins(t *testing.T) {
	path := selfSignedCAFile(t)
	tlsCfg, err := TLSConfigFor(config.Context{CAFile: path, InsecureSkipVerify: true})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tlsCfg == nil || tlsCfg.RootCAs == nil {
		t.Fatal("expected RootCAs to be populated when both are set")
	}
	if tlsCfg.InsecureSkipVerify {
		t.Error("ca-file should win over insecure-skip-verify — verification must stay armed")
	}
}
