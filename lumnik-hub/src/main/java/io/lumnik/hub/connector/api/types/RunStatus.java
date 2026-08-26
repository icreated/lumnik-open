package io.lumnik.hub.connector.api.types;

import java.time.Duration;
import java.time.Instant;

public sealed interface RunStatus {
  record Created(Instant at)                                              implements RunStatus {}
  record Running(Instant since, int chunksDone)                          implements RunStatus {}
  record Completed(Instant at, long records, Duration duration)          implements RunStatus {}
  record Partial(Instant at, long processed, long skipped)               implements RunStatus {}
  record Failed(Instant at, String message, boolean retryable)           implements RunStatus {}
  record Cancelled(Instant at, String reason)                            implements RunStatus {}
}
