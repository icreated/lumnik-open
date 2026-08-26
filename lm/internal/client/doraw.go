package client

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"strings"
	"time"
)

// doRaw performs one authorized request against the context's server and returns the
// response body verbatim — no JSON decoding, so YAML manifests and raw payloads ride
// through untouched. It is the single spelling of the sequence every non-JSON method
// used to hand-build: join the URL, set the headers it was given, go through
// c.authorize (the fresh `lm login` token path, never the static ctx.Token alone),
// read the whole body, and map any status >= 400 onto *APIError (3xx is followed
// automatically by http.Client, so it never reaches this check).
//
// contentType and accept are set only when non-empty; body may be nil (GET).
func (c *Client) doRaw(method, path, contentType, accept string, body []byte) ([]byte, error) {
	return c.doRawOn(c.http, 0, method, path, contentType, accept, body)
}

// doRawSlow is doRaw for model-bound endpoints (view generate): the stream client
// bounded by modelCallTimeout — see POSTSlow.
func (c *Client) doRawSlow(method, path, contentType, accept string, body []byte) ([]byte, error) {
	return c.doRawOn(c.stream, modelCallTimeout, method, path, contentType, accept, body)
}

func (c *Client) doRawOn(hc *http.Client, timeout time.Duration, method, path, contentType, accept string, body []byte) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.ctx.Server, "/")+path, reader)
	if err != nil {
		return nil, err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if accept != "" {
		req.Header.Set("Accept", accept)
	}
	if err := c.authorize(req); err != nil {
		return nil, err
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	// The body IS the payload here — a truncated read must fail loudly, never pass
	// for a short-but-valid manifest.
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		return nil, &APIError{Status: resp.StatusCode, Body: string(data), RetryAfter: resp.Header.Get("Retry-After")}
	}
	return data, nil
}
