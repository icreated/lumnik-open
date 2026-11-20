package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.outbound.OutboundUnresolvedException;
import io.lumnik.hub.testsupport.TestOutbound;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointParams;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * #2027: with no {@code endpoint}, the SDK hands its endpoint rules one from the hub's settings — an environment
     * variable, a system property, a profile's {@code endpoint_url} — and the signed requests go there. The rules the
     * hub gives them drop it: AWS for the region.
     */
    @Test
    void anEndpointFromTheHubsSettingsIsDropped() throws Exception {
        S3EndpointParams fromTheHub = S3EndpointParams.builder().region(Region.EU_WEST_3).bucket("erp-exports")
            .endpoint("http://collector.example:9000").build();
        assertThat(S3EndpointProvider.defaultProvider().resolveEndpoint(fromTheHub).get().url().getHost())
            .isEqualTo("erp-exports.collector.example");   // the control: what the SDK alone would do
        assertThat(S3Clients.AWS_ONLY.resolveEndpoint(fromTheHub).get().url())
            .isEqualTo(URI.create("https://erp-exports.s3.eu-west-3.amazonaws.com"));
    }

    /** What the rules choose stays AWS's: an access point, FIPS. */
    @Test
    void awsAddressingIsKept() throws Exception {
        assertThat(S3Clients.AWS_ONLY.resolveEndpoint(S3EndpointParams.builder().region(Region.EU_WEST_3)
                .bucket("arn:aws:s3:eu-west-3:123456789012:accesspoint/erp").build()).get().url().getHost())
            .isEqualTo("erp-123456789012.s3-accesspoint.eu-west-3.amazonaws.com");
        assertThat(S3Clients.AWS_ONLY.resolveEndpoint(S3EndpointParams.builder().region(Region.US_EAST_1).useFips(true)
                .bucket("erp-exports").build()).get().url().getHost())
            .isEqualTo("erp-exports.s3-fips.us-east-1.amazonaws.com");
    }

    /** Without an {@code endpoint}, the client resolves through those rules. */
    @Test
    void aClientWithoutAnEndpointUsesThem() {
        try (S3Client client = S3Clients.build(Map.of("region", "eu-west-3"), new SecretRefs(name -> Optional.of("a-key")),
                TestOutbound.ADDRESSES)) {
            assertThat(client.serviceClientConfiguration().endpointProvider()).contains(S3Clients.AWS_ONLY);
        }
    }
}
