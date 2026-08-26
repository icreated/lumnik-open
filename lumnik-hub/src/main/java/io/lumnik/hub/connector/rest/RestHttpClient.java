package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class RestHttpClient {

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // NEVER, not NORMAL: an auto-followed redirect is a second, unvalidated path to an arbitrary host
        // (PaginationUrlGuard only vets URLs the pagination strategies read out of the response BODY/
        // headers, not a 3xx Location the JDK client would otherwise chase on its own). No test or shipped
        // connector manifest relies on redirect-following (grepped for 30x stubs / Location headers /
        // "redirect" across lumnik-hub/src and the rest-generic connector manifest — none found).
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public RestResponse execute(RestRequest req) throws Exception {
        String url = buildUrl(req.url(), req.queryParams());
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(Duration.ofSeconds(60));
        req.headers().forEach(b::header);
        HttpRequest.BodyPublisher body = req.body() != null
            ? HttpRequest.BodyPublishers.ofString(req.body())
            : HttpRequest.BodyPublishers.noBody();
        switch (req.method()) {
            case "GET"    -> b.GET();
            case "POST"   -> b.POST(body);
            case "PUT"    -> b.PUT(body);
            case "DELETE" -> b.DELETE();
            default       -> b.method(req.method(), body);
        }
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return RestResponse.of(r.statusCode(), r.headers().map(), r.body());
    }

    /**
     * {@code followRedirects(NEVER)} above means a 3xx now reaches the caller as an ordinary
     * response instead of being chased. Nothing downstream inspects {@code status()} by default —
     * the redirect's (typically HTML) body fails JSON parsing and becomes an empty object, so an
     * unguarded caller would silently ingest zero items instead of failing. Callers that read a
     * response body as data (the connector reader, discovery) MUST call this before doing so;
     * callers that exist purely to report status back to a human (e.g. the endpoint test/dry-run)
     * deliberately do not — showing the 3xx to the operator IS the point there.
     */
    public static void refuseRedirect(RestResponse resp) {
        if (resp.status() >= 300 && resp.status() < 400) {
            throw new ConnectorException("upstream returned a redirect (" + resp.status() + ") to '"
                + resp.header("Location").orElse("<no Location header>")
                + "' — refusing to follow it or ingest its body as data");
        }
    }

    static String buildUrl(String base, Map<String,String> q) {
        if (q == null || q.isEmpty()) return base;
        String enc = q.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                      URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        return base + (base.contains("?") ? "&" : "?") + enc;
    }
}
