package io.lumnik.hub.connector.rest.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.rest.InternalCaApi;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.rest.auth.AuthProvider;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import io.lumnik.hub.connector.secret.SecretRefs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** #1609: a run goes through the client that trusts the connector's {@code tls.ca_secret}, and only then. */
class RestEndpointReaderCaTrustTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PEM = "cluster CA";

    private final InternalCaApi api = new InternalCaApi("{\"items\":[{\"name\":\"payment-api\"}]}");

    private RestEndpointReader reader(Map<String, String> secrets) {
        AuthProviderRegistry none = new AuthProviderRegistry() {
            @Override public AuthProvider build(AuthSpec spec) { return r -> r; }
        };
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setName("pods");
        ep.setMethod("GET");
        ep.setPath("/api/v1/pods");
        return new RestEndpointReader(ep, none, new PaginationFactory(), new RateLimiterRegistry(), api, MAPPER,
            SecretRefs.environmentOnly(secrets::get));
    }

    private static ConnectorContext ctx(Map<String, Object> tls) {
        Map<String, Object> config = new HashMap<>(Map.of("base_url", "https://kubernetes.default.svc",
            "pagination", Map.of("kind", "link-header", "response_path", "$.items")));
        if (tls != null) config.put("tls", tls);
        return new ConnectorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L, "k8s", "rest-generic",
            config, new SyncMode.FullRefresh(), null, null, null, null, new OwnedRunScope("test"));
    }

    @Test
    void a_declared_ca_carries_the_run() {
        ReadResult<Map<String, Object>> r = reader(Map.of("CLUSTER_CA", PEM))
            .read(500, null, ctx(Map.of("ca_secret", "CLUSTER_CA")));

        assertThat(api.trustedPems).containsExactly(PEM);
        assertThat(records(r)).extracting(m -> m.get("name")).containsExactly("payment-api");
    }

    @Test
    void without_a_declared_ca_the_public_trust_refuses() {
        assertThatThrownBy(() -> reader(Map.of()).read(500, null, ctx(null)))
            .hasRootCauseInstanceOf(SSLHandshakeException.class);
        assertThat(api.trustedPems).isEmpty();
    }

    @Test
    void a_declared_ca_nothing_holds_fails_the_run_by_its_name() {
        assertThatThrownBy(() -> reader(Map.of()).read(500, null, ctx(Map.of("ca_secret", "CLUSTER_CA"))))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("CA certificate unavailable (secret=CLUSTER_CA)");
    }

    /** A stored config whose tls names a blank CA never reaches the public client (review of #1612). */
    @Test
    void a_stored_blank_ca_secret_fails_closed() {
        assertThatThrownBy(() -> reader(Map.of()).read(500, null, ctx(Map.of("ca_secret", ""))))
            .isInstanceOf(AuthConfigException.class)
            .hasMessage("tls names no CA: ca_secret is missing or blank");
        assertThat(api.trustedPems).isEmpty();
        assertThat(api.sent).isNull();
    }

    private static List<Map<String, Object>> records(ReadResult<Map<String, Object>> r) {
        if (r instanceof ReadResult.HasMore<Map<String, Object>> more) return more.records();
        if (r instanceof ReadResult.EndOfStream<Map<String, Object>> end) return end.records();
        throw new AssertionError(r);
    }
}
