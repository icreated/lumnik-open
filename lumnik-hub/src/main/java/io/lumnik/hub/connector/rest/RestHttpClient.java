package io.lumnik.hub.connector.rest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lumnik.hub.connector.rest.auth.SecretRedactor;
import io.lumnik.hub.connector.rest.reliability.NoopResponseValidator;
import io.lumnik.hub.connector.rest.reliability.ResponseValidator;
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

    private final HttpClient client;
    /** The protocol every request asks for (#1859); {@code null} leaves it to the JDK, which negotiates HTTP/2 by ALPN. */
    private final HttpClient.Version version;
    /**
     * The clients trusting an internal CA (#1609), one per PEM: a run must not build a pool per chunk. Bounded and
     * expiring, so a rotated CA's client is dropped — and its selector thread with it — instead of kept for life.
     */
    private final Cache<String, RestHttpClient> trusted =
        Caffeine.newBuilder().maximumSize(64).expireAfterAccess(Duration.ofHours(1)).build();

    public RestHttpClient() { this(builder().build()); }

    private RestHttpClient(HttpClient client) { this(client, null); }

    private RestHttpClient(HttpClient client, HttpClient.Version version) {
        this.client = client;
        this.version = version;
    }

    private static HttpClient.Builder builder() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER, not NORMAL: an auto-followed redirect is a second, unvalidated path to an arbitrary host
            // (PaginationUrlGuard only vets URLs the pagination strategies read out of the response BODY/
            // headers, not a 3xx Location the JDK client would otherwise chase on its own). No test or shipped
            // connector manifest relies on redirect-following (grepped for 30x stubs / Location headers /
            // "redirect" across lumnik-hub/src and the rest-generic connector manifest — none found).
            // The 3xx that now reaches the caller is refused by ResponseStatusGuard, not chased here.
            .followRedirects(HttpClient.Redirect.NEVER);
    }

    /**
     * This client, trusting the CA certificates of {@code caPem} and only them, in place of the JDK's public trust
     * (#1609, {@link CaTrust}). Same timeouts, same refusal to follow redirects. An unreadable PEM is an
     * {@link IllegalArgumentException}.
     */
    public RestHttpClient trusting(String caPem) {
        RestHttpClient trusting = trusted.get(caPem, pem -> new RestHttpClient(builder().sslContext(CaTrust.sslContext(pem)).build()));
        return version == null ? trusting : new RestHttpClient(trusting.client, version);
    }

    /**
     * This client, asking for HTTP {@code httpVersion} ({@code "1.1"} or {@code "2"}) on every request (#1859). A
     * service that resets its HTTP/2 stream after a complete answer loses that answer to the JDK client: over
     * HTTP/1.1 the same answer — a SOAP Fault naming a refused credential — arrives. Shares this client's
     * connections and trust. Anything else is an {@link IllegalArgumentException}.
     */
    public RestHttpClient speaking(String httpVersion) {
        HttpClient.Version asked = switch (httpVersion) {
            case "1.1" -> HttpClient.Version.HTTP_1_1;
            case "2" -> HttpClient.Version.HTTP_2;
            default -> throw new IllegalArgumentException("http_version must be 1.1 or 2");
        };
        return new RestHttpClient(client, asked);
    }

    /**
     * Sends a request a credential signed, and hands back its response without that credential: a source that
     * echoes it in a body would otherwise carry it into a validator's refusal, a run's failure, a pre-flight's
     * sample or a table (#1393). The data plane — a run's pages, the pre-flight, the probe, all signed through
     * {@code AuthProvider.send} — goes through here; {@link #execute} is the wire, which the oauth2 token request
     * uses directly: its body is the token answer, and its refusals never quote it.
     */
    public RestResponse exchange(RestRequest signed) throws Exception {
        return exchange(signed, new NoopResponseValidator());
    }

    /**
     * As {@link #exchange(RestRequest)}, where {@code validator} says whether a 2xx is data: a body it refuses (a
     * GraphQL {@code errors}, a SOAP Fault) is never ingested, so it loses the bare credential too.
     */
    public RestResponse exchange(RestRequest signed, ResponseValidator validator) throws Exception {
        RestResponse resp = execute(signed);
        return SecretRedactor.withoutEchoedCredentials(resp, signed, resp.status() / 100 == 2 && accepts(validator, resp));
    }

    private static boolean accepts(ResponseValidator validator, RestResponse resp) {
        try {
            validator.validate(resp);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    public RestResponse execute(RestRequest req) throws Exception {
        String url = buildUrl(req.url(), req.queryParams());
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri(url))
            .timeout(Duration.ofSeconds(60));
        if (version != null) b.version(version);
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
        return base + (base.contains("?") ? "&" : "?") + formEncode(q);
    }

    /** {@code k=v&k=v}, each side {@code application/x-www-form-urlencoded} in UTF-8: a query string or a form body. */
    public static String formEncode(Map<String,String> fields) {
        return fields.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                      URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
