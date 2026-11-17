package io.lumnik.hub.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.outbound.OutboundAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** The stored error of a delivery that threw: see {@link OutboundFailure#describe}. */
class WebhookDeliveryErrorTest {

    private static final String TARGET = "https://user:pw@hooks.example/p/SECRET?k=v";

    /** Each message quotes the target in some form — whole, without userinfo, path alone, encoded, in a cause. */
    static Stream<Arguments> quotingExceptions() {
        return Stream.of(
            arguments(new ConnectException("Connection refused: " + TARGET),
                      "connection failed (ConnectException)"),
            arguments(new HttpConnectTimeoutException("HTTP connect timed out https://hooks.example/p/%53ECRET?k%3Dv"),
                      "connect timeout (HttpConnectTimeoutException)"),
            arguments(new HttpTimeoutException("request timed out: /p/SECRET?k=v"),
                      "timeout (HttpTimeoutException)"),
            arguments(new IOException("hooks.example/p/SECRET?k=v", new UnknownHostException("user:pw@hooks.example")),
                      "host not resolved (IOException)"),
            arguments(new ConnectException("hooks.example/p/SECRET").initCause(new UnresolvedAddressException()),
                      "host not resolved (ConnectException)"),
            arguments(new IOException("/p/SECRET", new SSLHandshakeException("PKIX path building failed for /p/SECRET")),
                      "TLS failure (IOException)"),
            arguments(new IllegalArgumentException("Illegal character in path at index 9: " + TARGET),
                      "invalid URL (IllegalArgumentException)"),
            arguments(new IOException("closed: /p/SECRET?k=v"),
                      "I/O error (IOException)"),
            // the kind is read anywhere in the cause chain, not only at its top
            arguments(new IOException("/p/SECRET", new ConnectException("Connection refused: " + TARGET)),
                      "connection failed (IOException)"),
            arguments(new IOException("/p/SECRET", new HttpConnectTimeoutException("timed out: " + TARGET)),
                      "connect timeout (IOException)"),
            arguments(new IllegalStateException("user pw SECRET k=v"),
                      "other (IllegalStateException)"));
    }

    @ParameterizedTest
    @MethodSource("quotingExceptions")
    void the_error_names_the_kind_never_the_message(Exception e, String expected) {
        assertThat(OutboundFailure.describe(e)).isEqualTo(expected);
    }

    // ---- a real JDK failure, through deliver(): thrown before any I/O (no network in tests) ----

    private static final WebhookDeliveryService SERVICE = new WebhookDeliveryService();
    static {
        SERVICE.cipher = new SecretCipher();   // no secret → decrypt(null) is null, no database
        SERVICE.addresses = new OutboundAddress(false, true);   // as in production: loopback refused
    }

    private static String deliverTo(String url) {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setTargetUrl(url);
        ep.setAuthMode(AuthMode.NONE);
        var r = SERVICE.deliver(ep, "order.created", 1L, "{}");
        assertThat(r.ok()).isFalse();
        return r.error();
    }

    @Test
    void an_invalid_url_stores_the_kind_not_the_url() {
        assertThat(deliverTo("https://user:pw@hooks.example/p/SECRET?k=v w"))   // a space: URI.create throws
            .isEqualTo("invalid URL (IllegalArgumentException)");
    }

    /** The cloud metadata address is never a webhook (#2002): refused before any connection. */
    @Test
    void a_link_local_target_is_refused_before_any_connection() {
        assertThat(deliverTo("http://169.254.169.254/latest/meta-data/")).contains("never connects to");
    }

    @Test
    void a_loopback_target_is_refused_on_a_hub_that_refuses_loopback() {
        assertThat(deliverTo("http://127.0.0.1:1/hook")).contains("loopback");
    }

    /** The receiver's body is stored, never put in the error a door hands back (#2002). */
    @Test
    void an_http_failure_names_the_status_not_the_body() {
        assertThat(WebhookDeliveryService.DeliveryResult.httpFailure(500, "internal SECRET", 3).error())
            .isEqualTo("HTTP 500");
    }

    /**
     * #2002: a receiver that answers its status, then trickles its body, holds a delivery no longer
     * than the body's deadline. The status is what the delivery reports.
     */
    @Test
    void a_body_that_trickles_is_given_up_at_its_deadline() {
        WireMockServer wm = new WireMockServer(0);
        wm.start();
        try {
            wm.stubFor(WireMock.post("/slow").willReturn(
                WireMock.ok("x".repeat(100)).withChunkedDribbleDelay(10, 20_000)));
            WebhookDeliveryService service = new WebhookDeliveryService();
            service.cipher = new SecretCipher();
            service.addresses = new OutboundAddress(true, true);
            service.bodyDeadline = Duration.ofSeconds(1);
            WebhookEndpoint ep = new WebhookEndpoint();
            ep.setTargetUrl("http://localhost:" + wm.port() + "/slow");
            ep.setAuthMode(AuthMode.NONE);

            long start = System.nanoTime();
            var r = service.deliver(ep, "order.created", 1L, "{}");

            assertThat(System.nanoTime() - start).isLessThan(10_000_000_000L);
            assertThat(r.ok()).isTrue();
            assertThat(r.httpStatus()).isEqualTo(200);
        } finally {
            wm.stop();
        }
    }
}
