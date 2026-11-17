package io.lumnik.hub.export.run;

import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.outbound.OutboundRefusedException;
import io.lumnik.hub.outbound.OutboundUnresolvedException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFailureTextTest {

  static final String SECRET = "AKIAFAKESECRETVALUE123";

  @Test
  void anSdkMessageNeverTravels() {
    S3Exception sdk = (S3Exception) S3Exception.builder()
        .message("signature mismatch for key " + SECRET)
        .statusCode(403)
        .awsErrorDetails(AwsErrorDetails.builder().errorCode("SignatureDoesNotMatch")
            .errorMessage("secret " + SECRET).build())
        .build();
    String text = ExportFailureText.of(new RuntimeException("wrap " + SECRET, sdk));
    assertThat(text).doesNotContain(SECRET)
        .isEqualTo("S3Exception (status 403, SignatureDoesNotMatch)");
  }

  @Test
  void aThirdPartyMessageIsReplacedByItsClassName() {
    String text = ExportFailureText.of(new UncheckedIOException(new IOException("auth fail " + SECRET)));
    assertThat(text).isEqualTo("UncheckedIOException");
  }

  @Test
  void ourDestinationMessageTravels() {
    String text = ExportFailureText.of(new RuntimeException("x",
        new FileDestinationException("sftp: directory /out does not exist")));
    assertThat(text).isEqualTo("sftp: directory /out does not exist");
  }

  @Test
  void anAuthoredRunFailureKeepsItsMessage() {
    assertThat(ExportFailureText.of(new ExportRunFailure(ExportFailureCode.DEFINITION,
        "spec.scope 'x' is not declared")))
        .isEqualTo("spec.scope 'x' is not declared");
  }

  @Test
  void anUnauthoredIllegalStateIsNamedByItsClassOnly() {
    // Recognised by type, never by where it was thrown: an ISE's message may quote anything.
    assertThat(ExportFailureText.of(new IllegalStateException("token " + SECRET)))
        .isEqualTo("IllegalStateException");
  }

  @Test
  void theResultIsBounded() {
    String text = ExportFailureText.of(new FileDestinationException("a".repeat(5000)));
    assertThat(text.length()).isLessThanOrEqualTo(201);
  }

  @Test
  void aSqlDataExceptionIsWithheld() {
    SQLException sql = new SQLException("invalid input syntax for type bigint: \"" + SECRET + "\"", "22P02");
    String text = ExportFailureText.of(new RuntimeException("snapshot failed", sql));
    assertThat(text).doesNotContain(SECRET).contains("SQLSTATE 22P02").startsWith("snapshot failed");
  }

  /**
   * #2002: a destination the address rule refuses keeps the hub's sentence, and is the export's definition to fix,
   * not a transport that failed.
   */
  @Test
  void an_address_refusal_is_said_as_written_and_is_the_definitions() {
    var refused = new OutboundRefusedException("the host resolves to a link-local address, which the hub never connects to");
    assertThat(ExportFailureText.of(refused)).isEqualTo(refused.getMessage());
    assertThat(ExportRunner.classify(refused, ExportFailureCode.PUBLICATION)).isEqualTo(ExportFailureCode.DEFINITION);
  }

  /** #2002: an S3 endpoint that does not resolve keeps the hub's sentence too. */
  @Test
  void an_unresolved_address_is_said_as_written() {
    var unresolved = new OutboundUnresolvedException();
    assertThat(ExportFailureText.of(unresolved)).isEqualTo(unresolved.getMessage());
  }
}
