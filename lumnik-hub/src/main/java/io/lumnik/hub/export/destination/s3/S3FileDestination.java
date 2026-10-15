package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.export.destination.FileDestination;
import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.PublishedArtifact;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Streams into a multipart upload (or, for a small artifact, a buffer that was never sent), then
 * publishes it on {@link #commit()} with a conditional {@code PutObject} /
 * {@code CompleteMultipartUpload} carrying {@code If-None-Match: *}. The uncommitted upload is the
 * S3 counterpart of the SFTP {@code .part} file, and the conditional write that of the bare
 * no-clobber rename. See the design doc / issue #679.
 */
public class S3FileDestination implements FileDestination {

    private enum State { READY, OPEN, COMMITTED, ABORTED }

    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final int partSize;

    private State state = State.READY;
    private S3UploadStream stream;

    S3FileDestination(S3Client s3, String bucket, String key, int partSize) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.partSize = partSize;
    }

    @Override
    public synchronized OutputStream open() {
        if (state != State.READY)
            throw new IllegalStateException("open() called twice, or after commit/abort/close: state=" + state);
        stream = new S3UploadStream(s3, bucket, key, partSize);
        state = State.OPEN;
        return stream;
    }

    @Override
    public synchronized PublishedArtifact commit() {
        if (state != State.OPEN)
            throw new IllegalStateException("commit() requires an open, uncommitted destination: state=" + state);
        stream.close();
        S3UploadStream.Published published;
        try {
            published = stream.finish();
        } catch (RuntimeException e) {
            state = State.ABORTED;
            stream.abortUpload();
            if (isCollision(e)) {
                throw new FileDestinationCollisionException(
                    "S3 destination: s3://" + bucket + "/" + key + " already exists — refusing to overwrite"
                        + " a previously delivered artifact (" + S3UploadStream.describe(e) + ")", e);
            }
            // A 409 ConditionalRequestConflict lands here too: a concurrent conditional write is in
            // flight, nothing is delivered yet, so it is an ordinary failure, not a collision.
            throw new FileDestinationException(
                "S3 destination: publishing s3://" + bucket + "/" + key + " failed ("
                    + S3UploadStream.describe(e) + uploadIdSuffix() + ")", e);
        }
        state = State.COMMITTED;
        return new PublishedArtifact("s3", "s3://" + bucket + "/" + key, published.versionId(), published.etag());
    }

    /** {@code 412 PreconditionFailed} is the store refusing {@code If-None-Match: *} because the
     *  key already holds an object — told apart by status, never by parsing a message. */
    private static boolean isCollision(RuntimeException e) {
        return e instanceof AwsServiceException ase && ase.statusCode() == 412;
    }

    private String uploadIdSuffix() {
        return stream.uploadId() == null ? "" : ", uploadId=" + stream.uploadId();
    }

    @Override
    public synchronized void abort() {
        if (state == State.ABORTED) return;
        if (state == State.COMMITTED)
            throw new IllegalStateException("abort() called after commit() already published the artifact");
        if (stream != null) {
            stream.close();
            stream.abortUpload();
        }
        state = State.ABORTED;
    }

    @Override
    public synchronized void close() {
        try {
            if (state != State.COMMITTED) abort();
        } finally {
            s3.close();
        }
    }

    /** The multipart upload behind the open stream, or {@code null} — for the ITs' cleanup
     *  assertions. */
    synchronized String uploadId() {
        return stream == null ? null : stream.uploadId();
    }
}
