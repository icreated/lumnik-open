package client

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// WarnOut receives lm's one-line network warnings (#2004); a test swaps it.
var WarnOut io.Writer = os.Stderr

// SameOriginRedirect is the CheckRedirect of every http.Client lm builds: a redirect that changes scheme or
// host:port is not followed (#2004). Go drops the Authorization header across hosts, but not on https→http on
// the same host, and a 307/308 replays a POST body — a refresh token, a device code — wherever it points.
func SameOriginRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return fmt.Errorf("stopped after 10 redirects")
	}
	from := via[0].URL
	if !config.SameOrigin(from.String(), req.URL.String()) {
		return fmt.Errorf("refusing a redirect from %s://%s to %s://%s: lm follows a redirect on the same origin only",
			from.Scheme, from.Host, req.URL.Scheme, req.URL.Host)
	}
	return nil
}

var insecureWarned sync.Map // context name → struct{}: the insecure-skip-verify warning, once per process

// WarnInsecure says once per context that its certificates are not checked — the hub's and Keycloak's, since
// login and refresh build their clients from the same TLS settings (#2004).
func WarnInsecure(name string, ctx config.Context) {
	if !ctx.InsecureSkipVerify || ctx.CAFile != "" {
		return
	}
	if _, seen := insecureWarned.LoadOrStore(name, struct{}{}); !seen {
		fmt.Fprintf(WarnOut, "lm: insecure-skip-verify is on for context '%s': the hub's and Keycloak's certificates are not checked\n", name)
	}
}

// withheldMessage says why a context's stored session is not sent to the effective server (#2004).
func withheldMessage(name string, ctx config.Context) string {
	return fmt.Sprintf("not sending the token of context '%s' (%s) to %s: add a context for that hub "+
		"(lm config set-context), or pass --token", name, config.Origin(ctx.StoredServer()), config.Origin(ctx.Server))
}
