package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.SeekableSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public class S3FileSource implements FileSource, SeekableSource {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final String suffix;

    public S3FileSource(S3Client s3, String bucket, String prefix, String suffix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
        this.suffix = suffix == null ? "" : suffix;
    }

    @Override
    public Stream<FileDescriptor> listSince(Instant cursor) {
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))
            .contents().stream()
            .filter(o -> suffix.isEmpty() || o.key().endsWith(suffix))
            .filter(o -> !o.lastModified().isBefore(cursor))   // >= cursor: inclusive boundary (callers dedup via FileProcessingCursor)
            .filter(o -> o.size() > 0)
            .sorted(Comparator.comparing(S3Object::lastModified))
            .map(o -> new FileDescriptor(o.key(), o.size(), o.lastModified(), o.eTag()));
    }

    @Override
    public InputStream open(FileDescriptor file) {
        return s3.getObject(b -> b.bucket(bucket).key(file.path()));
    }

    /**
     * Ranged {@code GetObject} ({@code Range: bytes=offset-}) — a real server-side seek, not
     * a client-side discard: S3 never sends the skipped prefix over the wire.
     */
    @Override
    public InputStream openAt(FileDescriptor file, long byteOffset) {
        if (byteOffset < 0) {
            throw new IllegalArgumentException(
                "S3FileSource.openAt: negative offset " + byteOffset + " for " + file.path());
        }
        if (byteOffset == 0) {
            return open(file);
        }
        // SeekableSource contract: an offset at or beyond EOF is an empty stream, not an error —
        // Local (SeekableByteChannel.position past size) and SFTP (read past EOF) both honour it.
        // A ranged GetObject "bytes=size-" would instead draw a 416 from S3, so short-circuit here.
        // Guarded on a known positive size only; a 0/unknown size falls through to the ranged GET.
        if (file.size() > 0 && byteOffset >= file.size()) {
            return InputStream.nullInputStream();
        }
        return s3.getObject(b -> b.bucket(bucket).key(file.path()).range("bytes=" + byteOffset + "-"));
    }

    @Override
    public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
        switch (action) {
            case LEAVE -> {}
            case DELETE -> s3.deleteObject(b -> b.bucket(bucket).key(file.path()));
            case MOVE -> {
                String archiveBucket = (String) cfg.getOrDefault("archive_bucket", bucket);
                String archivePrefix = (String) cfg.getOrDefault("archive_prefix", "archive/");
                String name = file.path().substring(file.path().lastIndexOf('/') + 1);
                String newKey = archivePrefix + name;
                s3.copyObject(b -> b
                    .sourceBucket(bucket).sourceKey(file.path())
                    .destinationBucket(archiveBucket).destinationKey(newKey));
                s3.deleteObject(b -> b.bucket(bucket).key(file.path()));
            }
            case CUSTOM_KOTLIN -> throw new IllegalStateException(
                "CUSTOM_KOTLIN must be handled by the connector runtime");
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
