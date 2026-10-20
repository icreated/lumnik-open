package io.lumnik.hub.connector.rest;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URISyntaxException;
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
        // The 3xx that now reaches the caller is refused by ResponseStatusGuard, not chased here.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public RestResponse execute(RestRequest req) throws Exception {
        String url = buildUrl(req.url(), req.queryParams());
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri(url))
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
     * {@code URI.create}, minus the URL in its refusal: the JDK's message quotes the whole string,
     * userinfo and query included, and it reaches a run's failure text (#1166). Still an
     * {@link IllegalArgumentException}, the type every caller already handles; the reason and the
     * index say where, like {@link Urls#whyNotAbsolute}. No cause: its message is the same quote.
     */
    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid URL: " + e.getReason() + " at index " + e.getIndex());
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
