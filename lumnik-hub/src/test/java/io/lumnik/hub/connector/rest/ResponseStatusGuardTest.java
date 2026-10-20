package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.reliability.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The one rule that says whether an HTTP response may be READ AS DATA.
 *
 * <p>Every case here was observed on a live run before it was written: a source answering 500 to
 * everything produced a {@code Completed} run with {@code in=0 out=0 skipped=0} and not one WARN,
 * because the error body carries no items path — a dead source read as a successful empty sync.
 */
class ResponseStatusGuardTest {

    private static RestResponse resp(int status, String body) {
        return RestResponse.of(status, Map.of(), body);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void a_2xx_response_is_data(int status) {
        assertThatCode(() -> ResponseStatusGuard.refuseNonSuccess(resp(status, "{\"items\":[]}")))
            .doesNotThrowAnyException();
    }

    /**
     * A redirect is refused by name, and names where it pointed by its HOST only: a {@code Location}
     * can carry a token or a signed query, and this text reaches the run's failure and the probe's
     * 502 body (#1166). A relative or unreadable one is said as such — never quoted, never thrown on.
     */
    @ParameterizedTest
    @MethodSource("quotingLocations")
    void a_redirect_is_refused_and_names_its_host_only(Map<String, List<String>> headers, String target) {
        RestResponse r = new RestResponse(302, headers, "<html>Moved</html>", null);
        assertThatThrownBy(() -> ResponseStatusGuard.refuseNonSuccess(r))
            .isInstanceOf(ConnectorException.class)
            .isNotInstanceOf(TransientFailureException.class)
            .hasMessage("upstream returned a redirect (302) to " + target
                + " — refusing to follow it or ingest its body as data")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("user", "pw", "ECRET", "k=v", "k%3Dv"));
    }

    private static final String SOURCE = "https://user:pw@api.example/p/SECRET?k=v";

    static Stream<Arguments> quotingLocations() {
        return Stream.of(
            arguments(location(SOURCE), "https://api.example"),
            arguments(location("https://api.example:8443/p/%53ECRET?k%3Dv#frag"), "https://api.example:8443"),
            arguments(location("//user:pw@api.example/p/SECRET?k=v"), "//api.example"),
            arguments(location("/p/SECRET?k=v"), "a relative location"),
            arguments(location(SOURCE + " w"), "an unreadable location"),
            arguments(Map.of(), "no Location header"));
    }

    private static Map<String, List<String>> location(String value) {
        return Map.of("Location", List.of(value));
    }

    /**
     * Anchored on the production policy, not on a copied list: whatever {@link RetryPolicy#defaults()}
     * declares retryable must classify as transient here, and the test moves the day the policy does.
     */
    @ParameterizedTest
    @MethodSource("retryableStatuses")
    void a_retryable_status_is_transient(int status) {
        assertThatThrownBy(() -> ResponseStatusGuard.refuseNonSuccess(resp(status, "<html>upstream is down</html>")))
            .isInstanceOf(TransientFailureException.class)
            .hasMessageContaining(String.valueOf(status));
    }

    static Set<Integer> retryableStatuses() {
        return RetryPolicy.defaults().retryOn();
    }

    /**
     * 401 is the one that stung: an expired token is not retryable, so the page came back empty and
     * the run went green. It must fail, and it must NOT be transient (an endlessly retried chunk on
     * a credential that will never come back is worse than a fast, named failure).
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 410, 422})
    void a_client_error_is_a_permanent_refusal(int status) {
        assertThatThrownBy(() -> ResponseStatusGuard.refuseNonSuccess(resp(status, "{\"error\":\"nope\"}")))
            .isInstanceOf(ConnectorException.class)
            .isNotInstanceOf(TransientFailureException.class)
            .hasMessageContaining(String.valueOf(status));
    }

    @Test
    void the_refusal_carries_a_bounded_excerpt_of_the_body() {
        String huge = "x".repeat(10_000);
        assertThatThrownBy(() -> ResponseStatusGuard.refuseNonSuccess(resp(500, huge)))
            .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(400));
    }
}
