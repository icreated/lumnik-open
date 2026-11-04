package io.lumnik.hub.connector.rest.auth;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * The OAuth2 tokens the hub holds: one per connector, per tenant (#1393). It outlives the providers: a run builds
 * one per chunk, and asking for a token per chunk is what a token's lifetime exists to avoid.
 *
 * <p>A slot also remembers the grant its token was issued for: a re-apply that changes the grant finds the old
 * token and drops it, so the map holds one slot per connector however often it is re-applied. One lock per slot
 * covers the first fetch and the renewal after a 401, so concurrent reads of one connector ask once — a
 * {@link ReentrantLock}, not {@code synchronized}: runs read on virtual threads.
 *
 * <p>A slot nobody asked for in {@link #IDLE_TTL} — a deleted connector's, or one that stopped running — is dropped
 * by a sweep, and never while its lock is held: the next ask fetches a token again, as after a restart.
 */
@ApplicationScoped
public class OAuth2Tokens {

    @FunctionalInterface
    interface Fetch {
        OAuth2Token fetch(Instant now) throws Exception;
    }

    /** A slot nobody asked for in this long is dropped. */
    static final Duration IDLE_TTL = Duration.ofHours(1);

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        String grant;
        OAuth2Token token;
        // Read and written under the lock.
        Instant askedAt;
        /**
         * Dropped by the sweep or by the connector's delete: a reader that was queued on the lock must go back to the
         * map for a live slot. Volatile: the delete sets it without the lock.
         */
        volatile boolean dropped;

        Slot(Instant now) { this.askedAt = now; }
    }

    private final Map<AuthSpec.Owner, Slot> slots = new ConcurrentHashMap<>();
    private final Clock clock;

    public OAuth2Tokens() {
        this(Clock.systemUTC());
    }

    OAuth2Tokens(Clock clock) {
        this.clock = clock;
    }

    /**
     * The token held for {@code owner} under {@code grant} (its non-secret fingerprint), fetched first when there is
     * none, it is due for renewal, or it was issued for another grant.
     */
    OAuth2Token current(AuthSpec.Owner owner, String grant, Fetch fetch) throws Exception {
        while (true) {
            Slot slot = slots.computeIfAbsent(owner, k -> new Slot(clock.instant()));
            slot.lock.lock();
            try {
                if (slot.dropped) continue;
                Instant now = clock.instant();
                slot.askedAt = now;
                if (slot.token == null || !slot.token.usableAt(now) || !Objects.equals(slot.grant, grant)) {
                    slot.token = null;
                    slot.grant = grant;
                    slot.token = fetch.fetch(now);
                }
                return slot.token;
            } finally {
                slot.lock.unlock();
            }
        }
    }

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void tick() { dropIdle(); }

    /** Drops every slot idle for {@link #IDLE_TTL} whose lock is free: a fetch in flight is not idle. */
    void dropIdle() {
        Instant limit = clock.instant().minus(IDLE_TTL);
        slots.forEach((owner, slot) -> {
            if (!slot.lock.tryLock()) return;
            try {
                if (slot.askedAt.isAfter(limit)) return;
                slot.dropped = true;
                slot.token = null;   // a reader still holding the slot must not keep the secret alive
                slots.remove(owner, slot);
            } finally {
                slot.lock.unlock();
            }
        });
    }

    /**
     * Forgets the slot of {@code owner}: its connector was deleted. Marked dropped, then out of the map, without the
     * slot's lock, so the delete never waits on a call the source is slow to answer: a reader queued on the lock
     * goes back to the map, and one already inside finishes with a slot nothing reaches after.
     */
    public void drop(AuthSpec.Owner owner) {
        Slot slot = slots.get(owner);
        if (slot == null) return;
        slot.dropped = true;   // visible before the slot leaves the map: a reader that still reads false came first
        slots.remove(owner, slot);
    }

    int size() { return slots.size(); }

    /** Drops {@code refused} — only if it is still the one held: a concurrent read may already have renewed it. */
    void invalidate(AuthSpec.Owner owner, OAuth2Token refused) {
        Slot slot = slots.get(owner);
        if (slot == null) return;
        slot.lock.lock();
        try {
            if (slot.token == refused) slot.token = null;
        } finally {
            slot.lock.unlock();
        }
    }
}
