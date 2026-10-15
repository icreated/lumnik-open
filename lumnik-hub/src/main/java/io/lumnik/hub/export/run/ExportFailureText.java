package io.lumnik.hub.export.run;

import io.lumnik.hub.connector.rest.Snippets;
import io.lumnik.hub.export.destination.FileDestinationException;
import io.lumnik.hub.sql.SqlFailureText;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.sql.SQLException;
import java.util.List;

/**
 * The {@code failure_reason} of a failed run: a sentence safe to store and show. Our own messages
 * ({@link FileDestinationException}, {@link ExportRunFailure}) travel as written, recognised by
 * type; a third-party message (an SDK, a JSch session) can quote a URL, a key or a header, so it
 * is replaced by its class name — plus, for AWS, the status and error code an operator actually acts on. SQL goes through
 * the shared withheld-class rule. The runner logs the whole exception before calling this.
 */
public final class ExportFailureText {

  private ExportFailureText() {}

  public static String of(Throwable failure) {
    List<Throwable> chain = SqlFailureText.causeChain(failure);
    for (Throwable t : chain) {
      if (t instanceof ExportRunFailure || t instanceof FileDestinationException)
        return Snippets.bound(t.getMessage());
    }
    for (Throwable t : chain) {
      if (t instanceof SQLException) return Snippets.bound(SqlFailureText.withCause(failure));
    }
    for (Throwable t : chain) {
      if (t instanceof AwsServiceException aws) {
        String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
        return Snippets.bound(aws.getClass().getSimpleName() + " (status " + aws.statusCode()
            + (code == null ? "" : ", " + code) + ")");
      }
    }
    return Snippets.bound(failure.getClass().getSimpleName());
  }
}
