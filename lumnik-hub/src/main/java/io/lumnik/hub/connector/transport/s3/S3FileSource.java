package io.lumnik.hub.connector.transport.s3;

import io.lumnik.hub.connector.transport.AfterProcessAction;
import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.SeekableSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
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
            .filter(this::listable)
            .filter(o -> !o.lastModified().isBefore(cursor))   // >= cursor: inclusive boundary (callers dedup via FileProcessingCursor)
            .sorted(Comparator.comparing(S3Object::lastModified))
            .map(S3FileSource::descriptor);
    }

    /**
     * A listing narrowed to the key ({@code prefix=path}), not {@code HeadObject}: HEAD's
     * {@code Last-Modified} is an HTTP date (whole seconds) while a listing can carry
     * milliseconds (MinIO does), so a HEAD answer would never be {@code sameVersion} with the
     * listing that started the file. The exact key is searched for, never assumed to sort first.
     * A store that refuses the narrowed prefix — a policy pinning {@code s3:prefix} to the
     * configured one (403), a directory bucket that only takes '/'-terminated prefixes (400) —
     * is answered from the configured listing, as before #898.
     */
    @Override
    public Optional<FileDescriptor> stat(String path) {
        if (!path.startsWith(prefix)) return Optional.empty();
        try {
            return find(path, path);
        } catch (S3Exception refused) {
            if (refused.statusCode() != 400 && refused.statusCode() != 403) throw refused;
            return find(prefix, path);
        }
    }

    /** Lazy pages: the search stops at the first page holding {@code key}. */
    private Optional<FileDescriptor> find(String listPrefix, String key) {
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(listPrefix))
            .contents().stream()
            .filter(o -> o.key().equals(key) && listable(o))
            .findFirst()
            .map(S3FileSource::descriptor);
    }

    /** The listing's filters, shared with {@link #stat}: a non-empty object with the suffix. */
    private boolean listable(S3Object o) {
        return (suffix.isEmpty() || o.key().endsWith(suffix)) && o.size() > 0;
    }

    private static FileDescriptor descriptor(S3Object o) {
        return new FileDescriptor(o.key(), o.size(), o.lastModified(), o.eTag());
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
