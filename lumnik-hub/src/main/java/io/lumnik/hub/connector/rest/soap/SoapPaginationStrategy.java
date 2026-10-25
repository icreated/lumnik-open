package io.lumnik.hub.connector.rest.soap;

import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.pagination.EndpointConfig;
import io.lumnik.hub.connector.rest.pagination.JsonPathExtractor;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;

import io.lumnik.hub.connector.api.helpers.EnvSecrets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Offset-style SOAP pagination — the real Siebel QueryPage shape. {@code StartRowNum} (the
 * {@code {{cursor}}} placeholder) starts at 0 and advances by {@code page_size} each page. It stops
 * when (first match wins): {@code last_page_path} is {@code true}; else {@code has_more_path} is
 * {@code false}; else the last page returned fewer than {@code page_size} items.
 *
 * <p>The request is built by {@link SoapRequestBuilder} (POST + envelope). The render context
 * (templates, action, base_url, path) is captured in {@link #first} — a fresh strategy is built per
 * chunk, so no cross-run state leaks. Reads {@code resp.json()}, which the reader has already
 * converted from XML via {@link XmlBodyParser}.
 */
public class SoapPaginationStrategy implements PaginationStrategy {

    private static final UnaryOperator<String> ENV = EnvSecrets::lookup;

    private final UnaryOperator<String> secrets;
    private final int pageSize;
    private final String lastPagePath;   // optional: TRUE => stop
    private final String hasMorePath;    // optional: FALSE => stop
    private final String responsePath;

    // Render context captured in first().
    private String baseUrl, path, soapAction, headerTemplate, bodyTemplate;
    private long offset;

    /** {@code {{secret:}}} from the environment only — no registry behind it (unit tests). */
    public SoapPaginationStrategy(Map<String, String> c) { this(c, ENV); }

    /** @param secrets what {@code {{secret:NAME}}} resolves through; {@code {{env:}}} stays {@link EnvSecrets}. */
    public SoapPaginationStrategy(Map<String, String> c, UnaryOperator<String> secrets) {
        this.secrets = secrets;
        this.pageSize = parsePositive(c.get("page_size"));
        this.lastPagePath = blankToNull(c.get("last_page_path"));
        this.hasMorePath = blankToNull(c.get("has_more_path"));
        this.responsePath = c.get("response_path");
    }

    @Override
    public RestRequest first(EndpointConfig cfg, Object incremental) {
        Map<String, Object> raw = cfg.raw();
        this.baseUrl = cfg.baseUrl();
        this.path = cfg.path();
        this.soapAction = str(raw.get("soap_action"));
        this.headerTemplate = str(raw.get("header"));
        this.bodyTemplate = str(raw.get("body"));
        this.offset = 0L;
        return render(offset);
    }

    @Override
    public RestRequest next(RestResponse resp, RestRequest current) {
        if (!hasMore(resp)) return null;
        offset += pageSize;
        RestRequest next = render(offset);
        if (current == null) return next;
        // Carry forward connector-level headers and query params from the current page.
        // SOAP-specific headers set by render() (Content-Type, SOAPAction) override the base.
        // Auth headers in current are re-applied/overwritten by the reader — no double-auth risk.
        Map<String, String> h = new HashMap<>(current.headers());
        h.putAll(next.headers());
        Map<String, String> q = new HashMap<>(current.queryParams());
        q.putAll(next.queryParams()); // next queryParams is empty for SOAP; future-safe merge
        return new RestRequest(next.method(), next.url(), h, q, next.body());
    }

    @Override
    public List<Map<String, Object>> items(RestResponse resp) {
        return PaginationSupport.items(resp.json(), responsePath);
    }

    private RestRequest render(long cursor) {
        return SoapRequestBuilder.render(baseUrl, path, soapAction, headerTemplate, bodyTemplate,
            String.valueOf(pageSize), String.valueOf(cursor), String.valueOf(cursor / pageSize + 1), secrets, ENV);
    }

    /** Stop signals, in priority order. A null/missing flag is treated as "stop" (never loops forever).
     *  XML carries no types, so a flag may arrive as the STRING "true"/"false" (XmlMapper) OR a real
     *  JSON boolean (if a service returns JSON-ish) — {@link #truthy}/{@link #falsey} accept both, and
     *  must read into Object (a String value would ClassCastException if read into Boolean). */
    private boolean hasMore(RestResponse resp) {
        if (lastPagePath != null) {
            Object last = JsonPathExtractor.read(resp.json(), lastPagePath);
            return falsey(last);                          // more only when LastPage is explicitly false
        }
        if (hasMorePath != null) {
            Object hm = JsonPathExtractor.read(resp.json(), hasMorePath);
            return truthy(hm);
        }
        return items(resp).size() >= pageSize;            // no flag → stop on a short page
    }

    private static boolean truthy(Object v) { return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim()); }
    private static boolean falsey(Object v) { return v != null && "false".equalsIgnoreCase(String.valueOf(v).trim()); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static int parsePositive(String s) {
        if (s == null || s.isBlank())
            throw new IllegalStateException("SOAP page_size is required and must be a positive integer");
        try {
            int n = Integer.parseInt(s.trim());
            if (n <= 0) throw new IllegalStateException("SOAP page_size must be positive, got: " + n);
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("SOAP page_size must be a positive integer, got: " + s, e);
        }
    }
}
