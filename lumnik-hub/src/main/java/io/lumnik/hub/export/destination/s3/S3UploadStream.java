package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.export.destination.FileDestinationException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Buffers one part in memory and owns the multipart upload that, until committed, is the S3
 * equivalent of the SFTP {@code .part} file: its parts are never visible under the final key.
 * Memory is one {@code byte[partSize]} plus the list of part records, whatever the export size.
 *
 * <p>A full buffer is flushed lazily — only when the next byte arrives — so a stream of exactly
 * {@code partSize} bytes stays single-part and {@link #finish()} publishes it with one
 * {@code PutObject}, never with a multipart upload that ends in an empty part.
 *
 * <p>{@link #close()} publishes nothing: only {@link #finish()}, called by
 * {@link S3FileDestination#commit()}, ever makes the final key appear.
 *
 * <p>A write this stream refuses — closed, too large, a part the store would not take — throws
 * the unchecked {@link FileDestinationException}, like {@code open()}/{@code commit()} do, so a
 * caller tells an upload failure from a failure of whatever produced the bytes by type alone.
 */
class S3UploadStream extends OutputStream {

    static final int DEFAULT_PART_SIZE = 8 * 1024 * 1024;
    static final int MIN_PART_SIZE = 5 * 1024 * 1024;
    static final int MAX_PARTS = 10_000;
    static final int PART_ATTEMPTS = 3;

    /** What a successful {@link #finish()} got back from the store. */
    record Published(String etag, String versionId) {}

    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final byte[] buffer;
    private final List<CompletedPart> parts = new ArrayList<>();

    private int filled;
    private String uploadId;
    private boolean closed;

    S3UploadStream(S3Client s3, String bucket, String key, int partSize) {
        if (partSize < MIN_PART_SIZE)
            throw new IllegalArgumentException("S3 part size must be at least " + MIN_PART_SIZE + " bytes: " + partSize);
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.buffer = new byte[partSize];
    }

    @Override
    public void write(int b) {
        ensureWritable();
        if (filled == buffer.length) flushFullBuffer();
        buffer[filled++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        ensureWritable();
        Objects.checkFromIndexSize(off, len, b.length);
        while (len > 0) {
            if (filled == buffer.length) flushFullBuffer();
            int n = Math.min(len, buffer.length - filled);
            System.arraycopy(b, off, buffer, filled, n);
            filled += n;
            off += n;
            len -= n;
        }
    }

    private void ensureWritable() {
        if (closed) throw new FileDestinationException("S3 destination stream for s3://" + bucket + "/" + key + " is closed");
    }

    /** A byte is waiting behind a full buffer, so at least one more part will follow this one:
     *  flushing part 10,000 here would leave the export needing part 10,001. */
    private void flushFullBuffer() {
        if (parts.size() >= MAX_PARTS - 1)
            throw new FileDestinationException("export exceeds the S3 destination's maximum size ("
                + MAX_PARTS + " parts of " + buffer.length + " bytes) for s3://" + bucket + "/" + key);
        try {
            uploadBufferAsPart();
        } catch (RuntimeException e) {
            // Not only SdkException: a timed-out attempt can surface as an UncheckedIOException.
            throw new FileDestinationException("S3 destination: part upload to s3://" + bucket + "/" + key
                + " failed (" + describe(e) + ", uploadId=" + uploadId + ")", e);
        }
    }

    /** Creates the multipart upload on first use — with SHA-256 declared up front, since S3 keeps
     *  part checksums only when the algorithm is set at creation — then sends the buffer as the
     *  next part with its own checksum, and records it for the complete call. */
    private void uploadBufferAsPart() {
        if (uploadId == null) {
            uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)).uploadId();
        }
        int partNumber = parts.size() + 1;
        String checksum = base64(sha256(buffer, filled));
        UploadPartResponse response = uploadPartWithRetry(partNumber, checksum);
        parts.add(CompletedPart.builder()
            .partNumber(partNumber).eTag(response.eTag()).checksumSHA256(checksum).build());
        filled = 0;
    }

    /**
     * The SDK's retry loop only catches {@code SdkException | IOException}, and the URL-connection
     * client raises a dropped socket as an {@link UncheckedIOException}, so that failure reaches
     * here without a single SDK retry (#744). Re-sending a part is idempotent — same upload id,
     * same part number — so it is retried here; a store answer is left to the SDK.
     */
    private UploadPartResponse uploadPartWithRetry(int partNumber, String checksum) {
        for (int attempt = 1; ; attempt++) {
            try {
                return s3.uploadPart(r -> r.bucket(bucket).key(key)
                        .uploadId(uploadId).partNumber(partNumber).checksumSHA256(checksum),
                    bufferedBody());
            } catch (UncheckedIOException e) {
                if (attempt == PART_ATTEMPTS) throw e;
            }
        }
    }

    /**
     * Publishes the key, refusing to overwrite an existing one ({@code If-None-Match: *}). SDK
     * failures propagate unwrapped so the caller can tell a {@code 412} apart by status code.
     */
    Published finish() {
        if (uploadId == null) {
            byte[] digest = sha256(buffer, filled);
            PutObjectResponse response = s3.putObject(r -> r.bucket(bucket).key(key)
                    .ifNoneMatch("*")
                    .checksumSHA256(base64(digest))
                    .metadata(Map.of("lumnik-sha256", HexFormat.of().formatHex(digest))),
                bufferedBody());
            return new Published(response.eTag(), response.versionId());
        }
        if (filled > 0) uploadBufferAsPart();
        CompleteMultipartUploadResponse response = s3.completeMultipartUpload(r -> r.bucket(bucket).key(key)
            .uploadId(uploadId)
            .ifNoneMatch("*")
            .multipartUpload(m -> m.parts(parts)));
        return new Published(response.eTag(), response.versionId());
    }

    /** Best-effort: discards the parts of the upload this instance owns. A cleanup failure must
     *  never mask the primary failure that triggered it. */
    void abortUpload() {
        if (uploadId == null) return;
        try {
            s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
        } catch (RuntimeException ignored) {
        }
    }

    /** The multipart upload this stream created, or {@code null} while everything still fits in
     *  the buffer. */
    String uploadId() {
        return uploadId;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** {@code fromByteBuffer} takes its own copy, so the buffer is free to refill afterwards. */
    private RequestBody bufferedBody() {
        return RequestBody.fromByteBuffer(ByteBuffer.wrap(buffer, 0, filled));
    }

    /** Status and S3 error code only — never the request, whose {@code toString()} can carry
     *  headers. Anything that is not a store answer is named by its type only. */
    static String describe(RuntimeException e) {
        if (e instanceof AwsServiceException ase) {
            String code = ase.awsErrorDetails() == null ? null : ase.awsErrorDetails().errorCode();
            return "HTTP " + ase.statusCode() + (code == null ? "" : " " + code);
        }
        return e.getClass().getSimpleName();
    }

    private static byte[] sha256(byte[] bytes, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String base64(byte[] digest) {
        return Base64.getEncoder().encodeToString(digest);
    }
}
