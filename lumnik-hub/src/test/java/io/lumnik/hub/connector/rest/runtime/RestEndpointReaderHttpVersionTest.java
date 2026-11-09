package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #1859: the connector's {@code http_version} decides the protocol of the client a run, the pre-flight and the probe use. */
class RestEndpointReaderHttpVersionTest {

    /** Hands back a marked client for each version it is asked to speak. */
    private static final class Recording extends RestHttpClient {
        final List<String> asked = new ArrayList<>();
        final RestHttpClient speaking = new RestHttpClient();

        @Override public RestHttpClient speaking(String httpVersion) {
            asked.add(httpVersion);
            return speaking;
        }
    }

    private static final AuthProviderRegistry NONE = new AuthProviderRegistry() {
        @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
    };

    private static RestConnection connect(Map<String, Object> cfg, RestHttpClient http) {
        return RestEndpointReader.connect(cfg, new AuthSpec.Owner(1L, UUID.randomUUID()), NONE, http, SecretRefs.environmentOnly(k -> null));
    }

    @Test
    void a_declared_version_is_the_one_the_client_speaks() {
        Recording http = new Recording();

        RestConnection c = connect(Map.of("base_url", "https://wcf-orders:18443", "http_version", "1.1"), http);

        assertThat(http.asked).containsExactly("1.1");
        assertThat(c.client()).isSameAs(http.speaking);
    }

    @Test
    void without_a_declared_version_the_client_negotiates() {
        Recording http = new Recording();

        RestConnection c = connect(Map.of("base_url", "https://wcf-orders:18443"), http);

        assertThat(http.asked).isEmpty();
        assertThat(c.client()).isSameAs(http);
    }

    @Test
    void a_stored_version_nobody_can_speak_fails_the_run_by_name() {
        assertThatThrownBy(() -> connect(Map.of("base_url", "https://x", "http_version", "3"), new RestHttpClient()))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("http_version must be 1.1 or 2");
    }
}
