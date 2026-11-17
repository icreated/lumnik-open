package io.lumnik.hub.connector.rest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lumnik.common.error.Throwables;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.auth.SecretRedactor;
import io.lumnik.hub.connector.rest.reliability.NoopResponseValidator;
import io.lumnik.hub.connector.rest.reliability.ResponseValidator;
import io.lumnik.hub.outbound.OutboundAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ApplicationScoped
public class RestHttpClient {


    private final HttpClient client;
    /** The protocol every request asks for (#1859); {@code null} leaves it to the JDK, which negotiates HTTP/2 by ALPN. */
    private final HttpClient.Version version;
    /** Where a request may go (#2002), judged before each one. */
    private final OutboundAddress addresses;
    private final long maxResponseBytes;
    /**
     * The clients trusting an internal CA (#1609), one per PEM: a run must not build a pool per chunk. Bounded and
     * expiring, so a rotated CA's client is dropped — and its selector thread with it — instead of kept for life.
     */
    private final Cache<String, RestHttpClient> trusted =
        Caffeine.newBuilder().maximumSize(64).expireAfterAccess(Duration.ofHours(1)).build();

    /** As the hub's own: the cap at its default, 64 MiB. */
    public RestHttpClient(OutboundAddress addresses) { this(addresses, 64); }

    /**
     * {@code maxResponseMb}: the most body a response may carry (#2002, {@code lumnik.outbound.max-response-mb}); past
     * it the exchange fails rather than hold the rest in memory.
     */
    @Inject
    public RestHttpClient(OutboundAddress addresses,
                          @ConfigProperty(name = "lumnik.outbound.max-response-mb", defaultValue = "64") int maxResponseMb) {
        this(builder().build(), null, addresses, maxResponseMb * 1024L * 1024L);
    }

    /** With a cap in bytes, for a test. */
    static RestHttpClient cappedAt(OutboundAddress addresses, long maxResponseBytes) {
        return new RestHttpClient(builder().build(), null, addresses, maxResponseBytes);
    }

    private RestHttpClient(HttpClient client, HttpClient.Version version, OutboundAddress addresses, long maxResponseBytes) {
        this.client = client;
        this.version = version;
        this.addresses = addresses;
        this.maxResponseBytes = maxResponseBytes;
    }

    /** Another client of the same rule and cap, over {@code client} and asking for {@code version}. */
    private RestHttpClient with(HttpClient client, HttpClient.Version version) {
        return new RestHttpClient(client, version, addresses, maxResponseBytes);
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
        RestHttpClient trusting = trusted.get(caPem, pem ->
            with(builder().sslContext(CaTrust.sslContext(pem)).build(), null));
        return version == null ? trusting : with(trusting.client, version);
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
        return with(client, asked);
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

    /**
     * The wire. The host's addresses are judged first ({@link OutboundAddress}): a refused one is an
     * {@code OutboundRefusedException}, and nothing is sent. A body past the cap is a
     * {@link ResponseTooLargeException}, never retried: the same source answers the same size again.
     */
    public RestResponse execute(RestRequest req) throws Exception {
        URI target = uri(buildUrl(req.url(), req.queryParams()));
        addresses.require(target);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(target)
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
        HttpResponse<String> r;
        AtomicReference<Capped> reading = new AtomicReference<>();
        try {
            r = client.send(b.build(), info -> {
                Capped capped = new Capped(HttpResponse.BodyHandlers.ofString().apply(info), maxResponseBytes,
                    declaredLength(info.headers()));
                reading.set(capped);
                return capped;
            });
        } catch (IOException e) {
            // Asked of the reader too, not only of the error (#2033): over HTTP/2 the stream's own "cancelled" can end
            // the exchange before the refusal reaches it.
            Capped capped = reading.get();
            boolean refused = capped != null && capped.over
                || Throwables.causeChain(e).stream().anyMatch(TooLarge.class::isInstance);
            if (refused) throw new ResponseTooLargeException(tooLarge());
            throw e;
        }
        return RestResponse.of(r.statusCode(), r.headers().map(), r.body());
    }

    private String tooLarge() {
        return "the response is larger than " + maxResponseBytes / (1024 * 1024)
            + " MiB, the most the hub reads; page the endpoint, or raise lumnik.outbound.max-response-mb";
    }

    /** The Content-Length a response declares, or 0: a malformed one is left to the byte count. */
    private static long declaredLength(HttpHeaders headers) {
        try {
            return headers.firstValueAsLong("Content-Length").orElse(0);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static final class TooLarge extends IOException {
        TooLarge() { super("response body over the cap"); }
    }

    /** A body subscriber that fails once more than {@code max} bytes arrived or were declared, and stops reading. */
    private static final class Capped implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> body;
        private final long max;
        private final long declared;
        private long seen;
        /** Whether the body was refused: written on the client's thread, read by the caller once the exchange ends. */
        private volatile boolean over;
        private Flow.Subscription upstream;

        Capped(HttpResponse.BodySubscriber<String> body, long max, long declared) {
            this.body = body;
            this.max = max;
            this.declared = declared;
        }

        @Override public CompletionStage<String> getBody() { return body.getBody(); }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            upstream = subscription;
            body.onSubscribe(subscription);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (over) return;
            for (ByteBuffer item : items) seen += item.remaining();
            // A Content-Length past the cap is refused at its first buffer: a cancel from onSubscribe loses to the
            // stream's own "cancelled" error, and the caller would read an I/O failure instead of the cap.
            if (seen > max || declared > max) {
                refuse();
                return;
            }
            body.onNext(items);
        }

        private void refuse() {
            over = true;
            body.onError(new TooLarge());   // first: a cancel completes the exchange with an error of its own
            upstream.cancel();
        }

        @Override public void onError(Throwable failure) { if (!over) body.onError(failure); }

        @Override public void onComplete() { if (!over) body.onComplete(); }
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
