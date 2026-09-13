package io.lumnik.hub.connector.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The config an endpoint runs under. The rule was only ever written inside {@code ChunkExecutor},
 * where nothing but a run could reach it — which is how the endpoint pre-flight came to knock on a
 * different door: reading the connector config flat, it found no {@code pagination} (it lives on
 * the endpoint) and, on a manifest with a {@code connection} block, no {@code base_url} either.
 */
class EffectiveConfigTest {

    private static Connector connector(Map<String, Object> config) {
        Connector c = new Connector();
        c.setConfig(config);
        return c;
    }

    private static ConnectorEndpoint endpoint(Map<String, Object> config) {
        ConnectorEndpoint ep = new ConnectorEndpoint();
        ep.setConfig(config);
        return ep;
    }

    @Test
    void aFlatConnectorConfigIsTheBase() {
        var cfg = EffectiveConfig.of(
            connector(Map.of("base_url", "http://erp.invalid")),
            endpoint(Map.of("pagination", Map.of("kind", "offset"))));

        assertThat(cfg).containsEntry("base_url", "http://erp.invalid")
                       .containsKey("pagination");
    }

    @Test
    void anExplicitConnectionBlockIsUnwrapped() {
        var cfg = EffectiveConfig.of(
            connector(Map.of("connection", Map.of("base_url", "http://erp.invalid"),
                             "kind", "rest-generic")),
            endpoint(Map.of()));

        assertThat(cfg).containsEntry("base_url", "http://erp.invalid")
                       .as("outside the connection block is not connection config")
                       .doesNotContainKey("kind");
    }

    @Test
    void theEndpointWinsOnAKeyConflict() {
        var cfg = EffectiveConfig.of(
            connector(Map.of("base_url", "http://erp.invalid", "default_rate_limit", "100/s")),
            endpoint(Map.of("default_rate_limit", "10/s")));

        assertThat(cfg).containsEntry("default_rate_limit", "10/s");
    }

    /** JDBC reads the credential id back out of the config map; the merge is where it is put in. */
    @Test
    void aCredentialIdRidesAlongWhenTheConnectorHasOne() {
        UUID credential = UUID.randomUUID();
        Connector c = connector(Map.of("base_url", "http://erp.invalid"));
        c.setCredentialId(credential);

        assertThat(EffectiveConfig.of(c, endpoint(Map.of())))
            .containsEntry("_credentialId", credential.toString());
        assertThat(EffectiveConfig.of(connector(Map.of()), endpoint(Map.of())))
            .doesNotContainKey("_credentialId");
    }

    /**
     * The connection alone — what a caller holding a connector but no endpoint runs under. The
     * REST probe is that caller: it samples a path that has no endpoint row yet, and used to be
     * handed a {@code baseUrl} in the request body because nothing here could answer it.
     *
     * <p>Asserted against the pair overload rather than restated, so the two cannot drift: adding
     * an endpoint that declares nothing must change nothing.
     */
    @Test
    void theConnectionAloneIsTheBaseWhenThereIsNoEndpoint() {
        Connector c = connector(Map.of("connection", Map.of("base_url", "http://erp.invalid",
                                                            "auth", Map.of("kind", "basic")),
                                       "kind", "rest-generic"));
        c.setCredentialId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"));

        // The unwrap and the credential stamp are pinned by the two tests above; what is new here
        // is only that the single-argument overload exists and answers identically.
        assertThat(EffectiveConfig.of(c))
            .containsEntry("base_url", "http://erp.invalid")
            .isEqualTo(EffectiveConfig.of(c, endpoint(Map.of())));
    }

    /**
     * The endpoint wins on every key it may legitimately declare — but {@code _credentialId} is not
     * one of them. It is stamped by this class from the CONNECTOR's own column, and which stored
     * credential a run resolves must not be steerable from an endpoint's config blob: the flat
     * endpoint CRUD persists that blob without connector-type validation.
     */
    @Test
    void anEndpointMayNotSteerTheCredential() {
        Connector c = connector(Map.of("base_url", "http://erp.invalid"));
        c.setCredentialId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

        var cfg = EffectiveConfig.of(c, endpoint(Map.of("_credentialId",
            "00000000-0000-0000-0000-00000000000b")));

        assertThat(cfg).containsEntry("_credentialId", "00000000-0000-0000-0000-00000000000a");
    }

    @Test
    void theResultIsAFreshMapNeitherSideCanBeMutatedThrough() {
        Connector c = connector(Map.of("base_url", "http://erp.invalid"));

        EffectiveConfig.of(c, endpoint(Map.of())).put("base_url", "http://elsewhere.invalid");

        assertThat(c.getConfig()).containsEntry("base_url", "http://erp.invalid");
    }
}
