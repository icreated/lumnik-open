package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.Map;

@ApplicationScoped
public class S3FileSourceFactory implements FileSourceFactory {

    private final SecretRefs secrets;

    @Inject
    public S3FileSourceFactory(SecretRefs secrets) {
        this.secrets = secrets;
    }

    @Override
    public String kind() {
        return "s3";
    }

    @Override
    public FileSource build(Map<String, Object> cfg) {
        String region    = (String) cfg.getOrDefault("region", "eu-west-3");
        String endpoint  = (String) cfg.get("endpoint");
        String accessEnv = (String) cfg.getOrDefault("access_key_env", "AWS_ACCESS_KEY_ID");
        String secretEnv = (String) cfg.getOrDefault("secret_key_env", "AWS_SECRET_ACCESS_KEY");
        String bucket    = (String) cfg.get("bucket");
        String prefix    = (String) cfg.getOrDefault("prefix", "");
        String suffix    = (String) cfg.getOrDefault("suffix", "");

        String accessKey = secrets.lookup(accessEnv);
        String secretKey = secrets.lookup(secretEnv);
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                "S3 credentials unavailable (access_key_env=" + accessEnv + ", secret_key_env=" + secretEnv + ")");
        }

        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .httpClient(UrlConnectionHttpClient.builder().build());
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return new S3FileSource(builder.build(), bucket, prefix, suffix);
    }
}
