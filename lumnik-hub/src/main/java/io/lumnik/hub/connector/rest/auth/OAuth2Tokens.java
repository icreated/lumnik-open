package io.lumnik.hub.connector.rest.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The OAuth2 tokens the hub holds: one per connector, per tenant (#1393). It outlives the providers: a run builds
 * one per chunk, and asking for a token per chunk is what a token's lifetime exists to avoid.
 *
 * <p>A slot also remembers the grant its token was issued for: a re-apply that changes the grant finds the old
 * token and drops it, so the map holds one slot per connector however often it is re-applied. One lock per slot
 * covers the first fetch and the renewal after a 401, so concurrent reads of one connector ask once — a
 * {@link ReentrantLock}, not {@code synchronized}: runs read on virtual threads.
 */
@ApplicationScoped
public class OAuth2Tokens {

    @FunctionalInterface
    interface Fetch {
        OAuth2Token fetch(Instant now) throws Exception;
    }

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        String grant;
        OAuth2Token token;
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
        Slot slot = slots.computeIfAbsent(owner, k -> new Slot());
        slot.lock.lock();
        try {
            Instant now = clock.instant();
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
