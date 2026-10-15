package io.lumnik.hub.search.projection;

import java.util.UUID;

/** §3.2's own cancel-transaction outcomes. */
public sealed interface CancelResult {
    record Requested(UUID runId) implements CancelResult {}
    record NoActiveRun() implements CancelResult {}
    record PastCancellablePoint(UUID runId) implements CancelResult {}
}
