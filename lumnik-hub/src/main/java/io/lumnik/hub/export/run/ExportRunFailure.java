package io.lumnik.hub.export.run;

import io.lumnik.hub.export.AuthoredFailure;

/**
 * A run failure whose code and reason the runner decided itself — a stored definition that no
 * longer holds, say. The message is authored here, so {@link ExportFailureText} stores it as
 * written, and {@link #code()} is the ledger's {@code failure_code} as is.
 */
public final class ExportRunFailure extends RuntimeException implements AuthoredFailure {

  private final ExportFailureCode code;

  public ExportRunFailure(ExportFailureCode code, String authoredReason) {
    super(authoredReason);
    this.code = code;
  }

  public ExportRunFailure(ExportFailureCode code, String authoredReason, Throwable cause) {
    super(authoredReason, cause);
    this.code = code;
  }

  public ExportFailureCode code() {
    return code;
  }
}
