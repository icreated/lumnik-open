package io.lumnik.hub.search.projection;

/**
 * What one catch-up attempt on one (tenant, index) pair did (#754). The tick reads it to decide whether the pair
 * deserves another batch inside the same tick.
 */
enum CatchUpResult {
    /** A batch was applied: another round for this pair is allowed. */
    PROGRESSED,
    /** Nothing to do for this pair (no marks, lock held elsewhere, declaration gone or invalid). */
    IDLE,
    /** The batch failed and the failure is recorded: the pair is not retried within this tick. */
    FAILED,
    /** An {@code ALL} mark surfaced: the caller runs a fresh {@code rebuild}, outside the catch-up lock. */
    REBUILD
}
