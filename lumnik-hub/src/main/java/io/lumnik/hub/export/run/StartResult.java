package io.lumnik.hub.export.run;

import java.util.UUID;

/** What {@link ExportRunner#start} answered. Only {@link Started} means a run now exists. */
public sealed interface StartResult {

    /** A row was claimed: {@code Running} with its worker submitted, or — with the run queue on — {@code Queued} for the dispatcher. */
    record Started(UUID runId) implements StartResult {}

    /** Another run of this export holds the lock or the in-flight slot. {@code runId} is that
     *  run when the ledger could name it, and null when only the lock answered (no row yet). */
    record AlreadyRunning(UUID runId) implements StartResult {}

    /** This occurrence was already claimed at this attempt, or already delivered. */
    record OccurrenceTaken() implements StartResult {}

    /** No export of that name in this tenant. */
    record NotFound() implements StartResult {}
}
