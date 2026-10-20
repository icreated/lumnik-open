package io.lumnik.hub.webhook;

import io.lumnik.hub.connector.rest.OutboundFailure;
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
    static { SERVICE.cipher = new SecretCipher(); }   // no secret → decrypt(null) is null, no database

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
}
