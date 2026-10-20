package output

import (
	"net/url"
	"strings"
)

// TargetHost is the part of a webhook target URL safe to show: scheme and host. The user, the path
// and the query are where a webhook URL carries its credential (#1144). `lm integration` tables and
// :integrations both show it (#1158).
func TargetHost(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "(hidden)"
	}
	return u.Scheme + "://" + u.Host
}

// ScrubURL replaces a target's full URL, should a hub or transport message quote it, by the part
// of it that is safe to show.
func ScrubURL(text, target string) string {
	if target == "" {
		return text
	}
	return strings.ReplaceAll(text, target, TargetHost(target))
}
