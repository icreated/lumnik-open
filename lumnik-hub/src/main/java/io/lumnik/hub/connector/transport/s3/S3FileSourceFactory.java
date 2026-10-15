package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
        String bucket = (String) cfg.get("bucket");
        String prefix = (String) cfg.getOrDefault("prefix", "");
        String suffix = (String) cfg.getOrDefault("suffix", "");
        return new S3FileSource(S3Clients.build(cfg, secrets), bucket, prefix, suffix);
    }
}
