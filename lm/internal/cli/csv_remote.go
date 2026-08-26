package cli

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// buildTransport parses an sftp:// or s3:// URL plus flag values into a
// transport map understood by POST /api/platform/sources/csv/ingest.
func buildTransport(
	arg, sftpKey, sftpPwd,
	s3Region, s3Endpoint, s3AccessKey, s3SecretKey,
	pattern, suffix string,
	port int,
	strictHostKey bool,
	hostKeyFingerprint string,
) (map[string]any, error) {
	switch {
	case strings.HasPrefix(arg, "sftp://"):
		u, err := url.Parse(arg)
		if err != nil {
			return nil, err
		}
		path := u.Path
		if path == "" || strings.HasSuffix(path, "/") {
			path = strings.TrimRight(path, "/") + "/" + pattern
		}
		portN := port
		if u.Port() != "" {
			portN, _ = strconv.Atoi(u.Port())
		}
		if sftpKey == "" && sftpPwd == "" {
			return nil, fmt.Errorf("sftp:// requires --key-secret or --password-secret")
		}
		t := map[string]any{
			"kind":            "sftp",
			"host":            u.Hostname(),
			"port":            portN,
			"user":            u.User.Username(),
			"path":            path,
			"strict_host_key": strictHostKey,
		}
		if hostKeyFingerprint != "" {
			t["host_key_fingerprint"] = hostKeyFingerprint
		}
		if sftpKey != "" {
			t["key_env"] = sftpKey
		}
		if sftpPwd != "" {
			t["password_env"] = sftpPwd
		}
		return t, nil

	case strings.HasPrefix(arg, "s3://"):
		u, err := url.Parse(arg)
		if err != nil {
			return nil, err
		}
		if s3Region == "" {
			return nil, fmt.Errorf("s3:// requires --region")
		}
		bucket := u.Host
		key := strings.TrimPrefix(u.Path, "/")
		prefix := key
		if !strings.HasSuffix(key, "/") && !strings.Contains(key, "*") {
			if i := strings.LastIndex(key, "/"); i >= 0 {
				prefix = key[:i+1]
			}
		}
		t := map[string]any{
			"kind":   "s3",
			"region": s3Region,
			"bucket": bucket,
			"prefix": prefix,
		}
		if suffix != "" {
			t["suffix"] = suffix
		}
		if s3Endpoint != "" {
			t["endpoint"] = s3Endpoint
		}
		if s3AccessKey != "" {
			t["access_key_env"] = s3AccessKey
		}
		if s3SecretKey != "" {
			t["secret_key_env"] = s3SecretKey
		}
		return t, nil

	default:
		return nil, fmt.Errorf("unsupported scheme: %s", arg)
	}
}
