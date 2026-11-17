package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.outbound.OutboundAddress;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.Collections;
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
     * {@code addresses} judges an {@code endpoint} override (#2002): it is where every signed request goes, so a
     * refused one is an {@code OutboundRefusedException} before the client is built.
     */
    public static S3Client build(Map<String, Object> cfg, SecretRefs secrets, OutboundAddress addresses) {
        String region    = (String) cfg.getOrDefault("region", "eu-west-3");
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
        if (override != null) addresses.requireAllResolved(Collections.singletonList(override.getHost()));
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .httpClient(UrlConnectionHttpClient.builder().build());
        if (override != null) {
            builder.endpointOverride(override).forcePathStyle(true);
        }
        return builder.build();
    }
}
