package io.lumnik.hub.search.projection;

import java.util.UUID;

/** §3.1's own trigger outcomes. RunLock is shared by rebuild/catch-up/purge-drain (design doc §1),
 *  so the lock being held does not by itself mean a rebuild is in flight — RebuildAlreadyRunning and
 *  PairBusy exist to keep that distinction visible all the way to the REST response. */
public sealed interface RebuildClaim {
    record Started(UUID runId) implements RebuildClaim {}
    record RebuildAlreadyRunning(UUID runId) implements RebuildClaim {}
    record PairBusy() implements RebuildClaim {}
    record DeclarationChanged() implements RebuildClaim {}

    /** #703 finding: this HUB INSTANCE's own on-demand rebuild concurrency gate ({@code
     *  SearchProjectionWorker#rebuildPermits}) is exhausted — a transient-retry-later signal, never a
     *  lock attempt (the permit is checked BEFORE {@code RunLock.tryAcquire} is ever called). Not
     *  deployment-wide (code review finding): the permit is an in-process {@code Semaphore}, so with
     *  N hub replicas the real deployment-wide bound is N &times; {@code max-concurrent-rebuilds}. */
    record TooManyInFlight() implements RebuildClaim {}
}
