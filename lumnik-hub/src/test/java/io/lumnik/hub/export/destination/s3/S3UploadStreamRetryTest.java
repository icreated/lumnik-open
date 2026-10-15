package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.export.destination.s3.S3FileDestinationStatusMappingTest.StubS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.UncheckedIOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static io.lumnik.hub.export.destination.s3.S3FileDestinationStatusMappingTest.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The SDK's retry loop only catches {@code SdkException | IOException}, and the URL-connection
 * client raises a dropped socket as an {@link UncheckedIOException} — so a single reset while a
 * part is being sent reaches this stream unretried (#744). A part upload is idempotent (same
 * upload id, same part number), so the stream retries it itself, and only that failure shape:
 * a store answer is the SDK's business, and the SDK has already decided about it.
 */
class S3UploadStreamRetryTest {

    static final int PART = S3UploadStream.MIN_PART_SIZE;

    static UncheckedIOException droppedSocket() {
        return new UncheckedIOException(new SocketException("Unexpected end of file from server"));
    }

    /** Creates upload {@code up-1}; each part upload asks {@code onPart} whether to fail. */
    static StubS3 storeWhosePartUploads(IntFunction<RuntimeException> onPart) {
        var s3 = new StubS3();
        var attempt = new AtomicInteger();
        s3.answer = request -> {
            if (request instanceof CreateMultipartUploadRequest)
                return CreateMultipartUploadResponse.builder().uploadId("up-1").build();
            if (request instanceof UploadPartRequest u) {
                RuntimeException failure = onPart.apply(attempt.incrementAndGet());
                if (failure != null) throw failure;
                return UploadPartResponse.builder().eTag("etag-" + u.partNumber()).build();
            }
            return null;
        };
        return s3;
    }

    /** One byte past a full buffer: the first part is flushed, the byte waits for the next. */
    static void writeOnePartAndABit(S3UploadStream stream) {
        stream.write(new byte[PART + 1], 0, PART + 1);
    }

    @Test
    void a_dropped_socket_on_a_part_is_retried_on_the_same_part() {
        StubS3 s3 = storeWhosePartUploads(n -> n == 1 ? droppedSocket() : null);

        writeOnePartAndABit(new S3UploadStream(s3.client(), "b", "k", PART));

        assertThat(s3.of(UploadPartRequest.class))
            .extracting(UploadPartRequest::partNumber, UploadPartRequest::uploadId)
            .containsExactly(tuple(1, "up-1"), tuple(1, "up-1"));
    }

    @Test
    void a_part_that_keeps_dropping_the_socket_fails_after_three_attempts() {
        StubS3 s3 = storeWhosePartUploads(n -> droppedSocket());
        var stream = new S3UploadStream(s3.client(), "b", "k", PART);

        assertThatThrownBy(() -> writeOnePartAndABit(stream))
            .isInstanceOf(FileDestinationException.class)
            .hasMessageContaining("UncheckedIOException")
            .hasCauseInstanceOf(UncheckedIOException.class);
        assertThat(s3.of(UploadPartRequest.class)).hasSize(3);
    }

    @Test
    void a_store_answer_is_not_retried_by_the_stream() {
        StubS3 s3 = storeWhosePartUploads(n -> status(403, "AccessDenied"));
        var stream = new S3UploadStream(s3.client(), "b", "k", PART);

        assertThatThrownBy(() -> writeOnePartAndABit(stream))
            .isInstanceOf(FileDestinationException.class)
            .hasMessageContaining("HTTP 403 AccessDenied");
        assertThat(s3.of(UploadPartRequest.class)).hasSize(1);
    }
}
