package io.lumnik.hub.api.rest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The 502 body of a probe whose source could not be reached: see
 * {@link RestDiscoveryResource#unreachable}. Tested on the method that builds it, because the
 * resource itself needs a database and a transaction to reach that catch (#1164).
 */
class RestDiscoveryUnreachableTest {

    private static final String SOURCE = "https://user:pw@api.example/p/SECRET?k=v";

    /** Each message quotes the source in some form: whole, without userinfo, path alone, encoded, in a cause. */
    static Stream<Arguments> quotingExceptions() {
        return Stream.of(
            arguments(new ConnectException("Connection refused: " + SOURCE),
                      "could not reach the source: connection failed (ConnectException)"),
            arguments(new HttpConnectTimeoutException("HTTP connect timed out https://api.example/p/%53ECRET?k%3Dv"),
                      "could not reach the source: connect timeout (HttpConnectTimeoutException)"),
            arguments(new HttpTimeoutException("request timed out: /p/SECRET?k=v"),
                      "could not reach the source: timeout (HttpTimeoutException)"),
            arguments(new IOException("api.example/p/SECRET?k=v", new UnknownHostException("user:pw@api.example")),
                      "could not reach the source: host not resolved (IOException)"));
    }

    @ParameterizedTest
    @MethodSource("quotingExceptions")
    void the_502_names_the_kind_never_the_message(IOException e, String expected) {
        assertThat(RestDiscoveryResource.unreachable(e))
            .isEqualTo(expected)
            .doesNotContain("user", "pw", "ECRET", "k=v");
    }
}
