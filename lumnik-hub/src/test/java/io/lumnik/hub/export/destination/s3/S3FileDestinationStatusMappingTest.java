package io.lumnik.hub.export.destination.s3;

import io.lumnik.hub.export.destination.FileDestinationCollisionException;
import io.lumnik.hub.export.destination.FileDestinationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The status mapping and the request shapes, without a store: a hand-written {@link S3Client}
 * proxy answers each call from a per-test script. What MinIO cannot show directly — that every
 * one of the three multipart calls carries its SHA-256 — is asserted here on the requests
 * themselves.
 */
class S3FileDestinationStatusMappingTest {

    static final int PART = S3UploadStream.MIN_PART_SIZE;

    /** Records every request; {@code answer} maps a request to its response, or throws. */
    static final class StubS3 {
        final List<Object> requests = new ArrayList<>();
        Function<Object, Object> answer = request -> null;

        S3Client client() {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.isDefault() && args != null && args.length >= 1 && args[0] instanceof Consumer<?>)
                    return InvocationHandler.invokeDefault(proxy, method, args);
                return switch (method.getName()) {
                    case "close" -> null;
                    case "serviceName" -> "s3";
                    default -> {
                        requests.add(args[0]);
                        yield answer.apply(args[0]);
                    }
                };
            };
            return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, handler);
        }

        <T> List<T> of(Class<T> type) {
            return requests.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    static S3Exception status(int status, String code) {
        return (S3Exception) S3Exception.builder().statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    /** Answers every write successfully except the commit call, which throws {@code failure}. */
    static Function<Object, Object> commitFailsWith(RuntimeException failure) {
        return request -> {
            if (request instanceof CreateMultipartUploadRequest)
                return CreateMultipartUploadResponse.builder().uploadId("up-1").build();
            if (request instanceof UploadPartRequest u)
                return UploadPartResponse.builder().eTag("etag-" + u.partNumber()).build();
            if (request instanceof PutObjectRequest || request instanceof CompleteMultipartUploadRequest)
                throw failure;
            if (request instanceof AbortMultipartUploadRequest)
                return AbortMultipartUploadResponse.builder().build();
            return null;
        };
    }

    @Test
    void precheck_200_is_a_collision() {
        var stub = new StubS3();
        stub.answer = request -> HeadObjectResponse.builder().build();

        assertThatThrownBy(() -> S3FileDestinationFactory.precheck(stub.client(), "b", "k"))
            .isInstanceOf(FileDestinationCollisionException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void precheck_403_and_404_proceed(int status) {
        var stub = new StubS3();
        stub.answer = request -> { throw status(status, null); };

        S3FileDestinationFactory.precheck(stub.client(), "b", "k"); // must not throw
    }

    @Test
    void precheck_any_other_failure_is_an_ordinary_failure() {
        var stub = new StubS3();
        stub.answer = request -> { throw status(500, "InternalError"); };
        assertThatThrownBy(() -> S3FileDestinationFactory.precheck(stub.client(), "b", "k"))
            .isInstanceOf(FileDestinationException.class)
            .isNotInstanceOf(FileDestinationCollisionException.class)
            .hasMessageContaining("HTTP 500 InternalError");

        stub.answer = request -> { throw SdkClientException.create("connection refused"); };
        assertThatThrownBy(() -> S3FileDestinationFactory.precheck(stub.client(), "b", "k"))
            .isInstanceOf(FileDestinationException.class)
            .isNotInstanceOf(FileDestinationCollisionException.class);
    }

    @Test
    void single_part_412_at_commit_is_a_collision() throws Exception {
        var stub = new StubS3();
        stub.answer = commitFailsWith(status(412, "PreconditionFailed"));
        var destination = new S3FileDestination(stub.client(), "b", "k", PART);
        destination.open().write(new byte[]{1, 2, 3});

        assertThatThrownBy(destination::commit).isInstanceOf(FileDestinationCollisionException.class);

        PutObjectRequest put = stub.of(PutObjectRequest.class).get(0);
        assertThat(put.ifNoneMatch()).isEqualTo("*");
        assertThat(put.checksumSHA256()).isEqualTo("A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc+4E=");
        assertThat(put.metadata()).containsEntry("lumnik-sha256",
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        assertThat(stub.of(AbortMultipartUploadRequest.class)).as("nothing was sent, nothing to abort").isEmpty();
    }

    @Test
    void single_part_409_at_commit_is_an_ordinary_failure() throws Exception {
        var stub = new StubS3();
        stub.answer = commitFailsWith(status(409, "ConditionalRequestConflict"));
        var destination = new S3FileDestination(stub.client(), "b", "k", PART);
        destination.open().write(1);

        assertThatThrownBy(destination::commit)
            .isInstanceOf(FileDestinationException.class)
            .isNotInstanceOf(FileDestinationCollisionException.class)
            .hasMessageContaining("HTTP 409 ConditionalRequestConflict");
    }

    @ParameterizedTest
    @ValueSource(ints = {412, 409})
    void multipart_commit_failure_aborts_and_carries_sha256_on_all_three_calls(int status) throws Exception {
        var stub = new StubS3();
        stub.answer = commitFailsWith(status(status, status == 412 ? "PreconditionFailed" : "ConditionalRequestConflict"));
        var destination = new S3FileDestination(stub.client(), "b", "k", PART);
        OutputStream out = destination.open();
        out.write(new byte[PART * 2 + 10]); // 3 parts

        Throwable thrown = catchThrowable(destination::commit);
        if (status == 412) {
            assertThat(thrown).isInstanceOf(FileDestinationCollisionException.class);
        } else {
            assertThat(thrown).isInstanceOf(FileDestinationException.class)
                .isNotInstanceOf(FileDestinationCollisionException.class)
                .hasMessageContaining("uploadId=up-1");
        }

        assertThat(stub.of(CreateMultipartUploadRequest.class)).singleElement()
            .satisfies(c -> assertThat(c.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256));
        List<UploadPartRequest> parts = stub.of(UploadPartRequest.class);
        assertThat(parts).extracting(UploadPartRequest::partNumber).containsExactly(1, 2, 3);
        assertThat(parts).allSatisfy(p -> assertThat(p.checksumSHA256()).isNotBlank());
        CompleteMultipartUploadRequest complete = stub.of(CompleteMultipartUploadRequest.class).get(0);
        assertThat(complete.ifNoneMatch()).isEqualTo("*");
        assertThat(complete.multipartUpload().parts())
            .extracting(p -> p.checksumSHA256())
            .containsExactlyElementsOf(parts.stream().map(UploadPartRequest::checksumSHA256).toList());
        assertThat(stub.of(AbortMultipartUploadRequest.class)).singleElement()
            .satisfies(a -> assertThat(a.uploadId()).isEqualTo("up-1"));
    }

    @Test
    void successful_commit_returns_the_receipt_verbatim() throws Exception {
        var stub = new StubS3();
        stub.answer = request -> PutObjectResponse.builder().eTag("\"abc\"").versionId("v1").build();
        var destination = new S3FileDestination(stub.client(), "b", "dir/k.csv", PART);
        destination.open().write(1);

        assertThat(destination.commit()).satisfies(r -> {
            assertThat(r.kind()).isEqualTo("s3");
            assertThat(r.location()).isEqualTo("s3://b/dir/k.csv");
            assertThat(r.etag()).isEqualTo("\"abc\"");
            assertThat(r.versionId()).isEqualTo("v1");
        });
    }

    @Test
    void a_part_size_below_the_s3_minimum_is_refused() {
        assertThatThrownBy(() -> new S3UploadStream(new StubS3().client(), "b", "k", PART - 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefix_is_joined_as_a_folder() {
        assertThat(S3FileDestinationFactory.resolveKey("", "x.csv")).isEqualTo("x.csv");
        assertThat(S3FileDestinationFactory.resolveKey("customers", "x.csv")).isEqualTo("customers/x.csv");
        assertThat(S3FileDestinationFactory.resolveKey("customers/", "x.csv")).isEqualTo("customers/x.csv");
        assertThatThrownBy(() -> S3FileDestinationFactory.resolveKey("/customers", "x.csv"))
            .isInstanceOf(FileDestinationException.class);
        assertThatThrownBy(() -> S3FileDestinationFactory.resolveKey("", "/x.csv"))
            .isInstanceOf(FileDestinationException.class);
        assertThatThrownBy(() -> S3FileDestinationFactory.resolveKey("customers", "/x.csv"))
            .isInstanceOf(FileDestinationException.class);
    }

    @Test
    void a_missing_bucket_or_filename_is_refused_before_any_client_is_built() {
        var factory = new S3FileDestinationFactory(null);
        assertThatThrownBy(() -> factory.build(Map.of("filename", "x.csv")))
            .isInstanceOf(FileDestinationException.class).hasMessageContaining("'bucket' is required");
        assertThatThrownBy(() -> factory.build(Map.of("bucket", "b", "filename", " ")))
            .isInstanceOf(FileDestinationException.class).hasMessageContaining("'filename' is required");
    }
}
