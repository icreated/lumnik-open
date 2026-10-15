package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.s3.S3Clients;
import io.lumnik.hub.export.destination.FileDestination;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.FileDestinationFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;

import static io.lumnik.hub.export.destination.FileDestinationFactory.required;

/**
 * Builds one S3 destination per call, with the same client wiring as the reader side
 * ({@link S3Clients}). The pre-check is eager, like the SFTP factory's connect: one
 * {@code HeadObject} on the final key, so an obvious collision or a broken endpoint surfaces at
 * build time, before any row has been read from the entity being exported.
 *
 * <p>The pre-check is advisory. The guarantee is the conditional commit ({@code If-None-Match: *});
 * the {@code HeadObject} only narrows the window on a store that ignores it. A {@code 403} proceeds,
 * because a least-privilege, write-only partner bucket answers {@code 403} for an absent key too.
 */
@ApplicationScoped
public class S3FileDestinationFactory implements FileDestinationFactory {

    private final SecretRefs secrets;
    private final int partSize;

    @Inject
    public S3FileDestinationFactory(SecretRefs secrets) {
        this(secrets, S3UploadStream.DEFAULT_PART_SIZE);
    }

    /** Test seam: the ITs lower the part size to S3's 5 MiB minimum. */
    S3FileDestinationFactory(SecretRefs secrets, int partSize) {
        this.secrets = secrets;
        this.partSize = partSize;
    }

    @Override
    public String kind() {
        return "s3";
    }

    @Override
    public FileDestination build(Map<String, Object> cfg) {
        String bucket = required(cfg, "S3", "bucket");
        String key = resolveKey((String) cfg.getOrDefault("prefix", ""), required(cfg, "S3", "filename"));

        S3Client s3 = S3Clients.build(cfg, secrets);
        try {
            precheck(s3, bucket, key);
        } catch (RuntimeException e) {
            s3.close();
            throw e;
        }
        return new S3FileDestination(s3, bucket, key, partSize);
    }

    /** The export side treats {@code prefix} as a folder: {@code customers} and {@code customers/}
     *  both publish {@code customers/x.csv}. A leading {@code /} is refused — {@code /x.csv} is a
     *  different S3 key from {@code x.csv}, a split no partner expects — and under a prefix it would
     *  become {@code customers//x.csv}, so the filename is checked on its own before the join. */
    static String resolveKey(String prefix, String filename) {
        if (filename.startsWith("/"))
            throw new FileDestinationException("S3 destination: filename " + filename + " starts with '/'"
                + " — refusing to publish under a leading-slash key");
        String key = prefix == null || prefix.isEmpty()
            ? filename
            : (prefix.endsWith("/") ? prefix : prefix + "/") + filename;
        if (key.startsWith("/"))
            throw new FileDestinationException("S3 destination: key " + key + " starts with '/' — refusing"
                + " to publish under a leading-slash key");
        return key;
    }

    /** 200 → collision; 404 / 403 → proceed; anything else → failure. */
    static void precheck(S3Client s3, String bucket, String key) {
        try {
            s3.headObject(r -> r.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            if (e instanceof AwsServiceException ase && (ase.statusCode() == 404 || ase.statusCode() == 403))
                return;
            throw new FileDestinationException("S3 destination: pre-check of s3://" + bucket + "/" + key
                + " failed (" + S3UploadStream.describe(e) + ")", e);
        }
        throw new FileDestinationCollisionException("S3 destination: s3://" + bucket + "/" + key
            + " already exists — refusing to overwrite a previously delivered artifact", null);
    }
}
