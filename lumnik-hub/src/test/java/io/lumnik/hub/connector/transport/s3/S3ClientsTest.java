package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.outbound.OutboundUnresolvedException;
import io.lumnik.hub.testsupport.TestOutbound;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2000: a manifest naming no S3 credential gets the default names, and they resolve in the tenant's lm secret
 * registry only — never to the hub's own AWS credentials, which a custom {@code endpoint} would send to any host.
 */
class S3ClientsTest {

    /** The production wiring, over a registry that holds nothing. */
    private final SecretRefs anEmptyRegistry = new SecretRefs(name -> Optional.empty());

    @Test
    void theHubsOwnAwsCredentialsAreNotAFallback() {
        System.setProperty("AWS_ACCESS_KEY_ID", "the-hubs-key");
        System.setProperty("AWS_SECRET_ACCESS_KEY", "the-hubs-secret");
        try {
            assertThatThrownBy(() -> S3Clients.build(Map.of("endpoint", "https://collector.example"), anEmptyRegistry, TestOutbound.ADDRESSES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("S3 credentials unavailable (access_key_env=AWS_ACCESS_KEY_ID, secret_key_env=AWS_SECRET_ACCESS_KEY)");
        } finally {
            System.clearProperty("AWS_ACCESS_KEY_ID");
            System.clearProperty("AWS_SECRET_ACCESS_KEY");
        }
    }

    /** #2002: an endpoint override is where the signed requests go — judged before the client is built. */
    @Test
    void anEndpointAtARefusedAddressIsRefused() {
        SecretRefs keys = new SecretRefs(name -> Optional.of("a-key"));
        assertThatThrownBy(() -> S3Clients.build(Map.of("endpoint", "http://169.254.169.254/"), keys,
                TestOutbound.ADDRESSES))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("never connects to");
    }

    /** #2002: an endpoint that does not resolve is refused — the SDK would look it up again on each connection. */
    @Test
    void anEndpointThatDoesNotResolveIsRefusedAsRetryable() {
        SecretRefs keys = new SecretRefs(name -> Optional.of("a-key"));
        assertThatThrownBy(() -> S3Clients.build(Map.of("endpoint", "http://no-such-store.invalid:9000"), keys,
                TestOutbound.ADDRESSES))
            .isInstanceOf(OutboundUnresolvedException.class);
    }
}
