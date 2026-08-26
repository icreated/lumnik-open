package io.lumnik.hub.connector.api.types;

import java.util.List;

public sealed interface WriteOutcome {
  record AllWritten(int count)                                        implements WriteOutcome {}
  record PartialWrite(int written, List<SkippedRecord> skipped)       implements WriteOutcome {}
  record SkippedRecord(Object payload, SkipReason reason) {}
  record AllFailed(String reason, Throwable cause)                    implements WriteOutcome {}
}
