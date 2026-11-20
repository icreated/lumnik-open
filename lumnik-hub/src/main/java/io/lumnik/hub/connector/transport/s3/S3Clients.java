package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.outbound.OutboundAddress;
import io.lumnik.hub.outbound.OutboundUnresolvedException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.endpoints.S3EndpointProvider;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Builds one authenticated {@link S3Client} from a transport config. Extracted verbatim from the
 * original {@code S3FileSourceFactory#build}, so the S3 export destination shares the exact same
 * region, endpoint/path-style and credential wiring the reader side already has, instead of a
 * second copy that could silently drift from it.
 */
public final class S3Clients {

    private S3Clients() {
    }

    /**
     * AWS's own endpoint rules, minus any endpoint the hub's settings name (#2027). Without an {@code endpoint}, the
     * SDK takes one from the hub's {@code AWS_ENDPOINT_URL(_S3)}, {@code aws.endpointUrl(S3)} or a profile's
     * {@code endpoint_url}, and sends the signed requests there. Dropped before the rules run: what they choose — a
     * region, an access point, FIPS — stays AWS's.
     */
    static final S3EndpointProvider AWS_ONLY = params ->
        S3EndpointProvider.defaultProvider().resolveEndpoint(params.toBuilder().endpoint(null).build());

    /**
     * {@code addresses} judges an {@code endpoint} override (#2002): it is where every signed request goes, so a
     * refused one is an {@code OutboundRefusedException} before the client is built.
     */
    public static S3Client build(Map<String, Object> cfg, SecretRefs secrets, OutboundAddress addresses) {
        Region region    = Region.of((String) cfg.getOrDefault("region", "eu-west-3"));
        String endpoint  = (String) cfg.get("endpoint");
        String accessEnv = (String) cfg.getOrDefault("access_key_env", "AWS_ACCESS_KEY_ID");
        String secretEnv = (String) cfg.getOrDefault("secret_key_env", "AWS_SECRET_ACCESS_KEY");

        String accessKey = secrets.lookup(accessEnv);
        String secretKey = secrets.lookup(secretEnv);
        // Names the secrets, never their values.
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                "S3 credentials unavailable (access_key_env=" + accessEnv + ", secret_key_env=" + secretEnv + ")");
        }

        URI override = endpoint == null ? null : URI.create(endpoint);
        // Judged before anything is built, and resolved: the client looks the name up again on each new connection.
        if (override != null) addresses.requireResolved(override);
        S3ClientBuilder builder = S3Client.builder()
            .region(region)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .httpClientBuilder(httpClient(addresses, override == null ? 443 : OutboundAddress.port(override)));
        if (override != null) {
            builder.endpointOverride(override).forcePathStyle(true);
        } else {
            builder.endpointProvider(AWS_ONLY);
        }
        return builder.build();
    }

    /**
     * Every connection the client opens, judged on the addresses it connects to (#2027): the client looks a name up
     * again for each new connection, all run long, and could land wherever the name then leads. No proxy, whatever
     * the hub's {@code https_proxy} or {@code https.proxyHost} say: a proxy would pick the address itself. A builder,
     * so the S3 client owns the HTTP client and closes it with itself.
     */
    static ApacheHttpClient.Builder httpClient(OutboundAddress addresses, int port) {
        return ApacheHttpClient.builder()
            .dnsResolver(host -> {
                try {
                    return addresses.resolve(host, port);
                } catch (UnknownHostException unresolved) {
                    throw new OutboundUnresolvedException();   // as at build: host not resolved, the run retries
                }
            })
            .proxyConfiguration(ProxyConfiguration.builder()
                .useSystemPropertyValues(false).useEnvironmentVariableValues(false).build());
    }
}
