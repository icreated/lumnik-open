package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;

import java.util.List;
import java.util.Map;

/**
 * Follows a {@code next} URL found in the response BODY until it is null/absent. Covers the modern
 * Node/Python conventions that put the full next URL in the payload (Django REST {@code $.next},
 * JSON:API / NestJS {@code $.links.next}, Spring HATEOAS {@code $._links.next.href}) — the client
 * follows URLs and is agnostic to whether the server paginates by page or cursor.
 */
public class BodyLinkPaginationStrategy implements PaginationStrategy {

    private final String linkPath;
    private final String responsePath;

    public BodyLinkPaginationStrategy(Map<String, String> c) {
        this.linkPath     = c.get("link_path");
        this.responsePath = c.get("response_path");
        // Fail fast on a misconfigured strategy (the validator also requires these): without them
        // next() would silently never advance and items() would always be empty — partial ingestion.
        if (Text.blank(linkPath)) {
            throw new IllegalArgumentException("body-link pagination requires 'link_path'");
        }
        if (Text.blank(responsePath)) {
            throw new IllegalArgumentException("body-link pagination requires 'response_path'");
        }
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        return PaginationSupport.baseGet(cfg);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        String url = PaginationSupport.nextLink(resp.json(), linkPath);
        if (url == null) return null;
        // The URL came out of the upstream response body — untrusted. Vet it before following (SSRF).
        String nextUrl = PaginationUrlGuard.vet(url, current.url());
        // following (not RestRequest.get) so the connector-level RequestHeaders — applied by the reader
        // to the FIRST request only, not re-decorated per page — and a query param the next URL lacks
        // survive onto it; one it already carries is not sent twice (#1633). (Auth is re-signed per page by RestEndpointReader, so it's not at stake
        // here.) Mirrors LinkHeaderPaginationStrategy, the other "follow an absolute next URL" strategy.
        return current.following(nextUrl);
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }
}
