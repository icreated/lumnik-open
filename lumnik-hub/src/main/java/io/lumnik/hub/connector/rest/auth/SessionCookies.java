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
import java.util.stream.Collectors;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * The form-login sessions the hub holds: one per connector, per tenant (#1751), as {@link OAuth2Tokens} holds tokens —
 * a run builds a provider per chunk, and one login per chunk is what a session exists to avoid. The slot protocol is
 * {@link OAuth2Tokens}'s: a change to one belongs in the other. A session has no
 * stated lifetime: it is held until the source refuses it. A slot remembers the login it was opened for, so a
 * re-apply that changes the login drops it; one lock per slot, so concurrent reads of one connector log in once.
 * A slot nobody asked for in {@link #IDLE_TTL} is dropped by a sweep, never while its lock is held.
 */
@ApplicationScoped
public class SessionCookies {

    /** The cookies a login earned, by name. Compared by identity: a refused session drops only itself. */
    record Session(Map<String, String> jar, String cookie) {
        Session(Map<String, String> jar) {
            this(Map.copyOf(jar), header(jar));
        }
    }

    /** The {@code Cookie} header a jar is sent as. */
    static String header(Map<String, String> jar) {
        return jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).sorted().collect(Collectors.joining("; "));
    }

    @FunctionalInterface
    interface Open {
        Session open() throws Exception;
    }

    /** A slot nobody asked for in this long is dropped. */
    static final Duration IDLE_TTL = Duration.ofHours(1);

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        String login;
        Session session;
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

    public SessionCookies() {
        this(Clock.systemUTC());
    }

    SessionCookies(Clock clock) {
        this.clock = clock;
    }

    /** The session held for {@code owner} under {@code login} (its non-secret fingerprint), opened first when there is none. */
    Session current(AuthSpec.Owner owner, String login, Open open) throws Exception {
        while (true) {
            Slot slot = slots.computeIfAbsent(owner, k -> new Slot(clock.instant()));
            slot.lock.lock();
            try {
                if (slot.dropped) continue;
                slot.askedAt = clock.instant();
                if (slot.session == null || !Objects.equals(slot.login, login)) {
                    slot.session = null;   // a login that throws leaves no session, never the one opened for another login
                    slot.login = login;
                    slot.session = open.open();
                }
                return slot.session;
            } finally {
                slot.lock.unlock();
            }
        }
    }

    @Scheduled(every = "1m", concurrentExecution = SKIP)
    void tick() { dropIdle(); }

    /** Drops every slot idle for {@link #IDLE_TTL} whose lock is free: a login in flight is not idle. */
    void dropIdle() {
        Instant limit = clock.instant().minus(IDLE_TTL);
        slots.forEach((owner, slot) -> {
            if (!slot.lock.tryLock()) return;
            try {
                if (slot.askedAt.isAfter(limit)) return;
                slot.dropped = true;
                slot.session = null;   // a reader still holding the slot must not keep the cookies alive
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

    /** Drops {@code refused} — only if it is still the one held: a concurrent read may already have logged in again. */
    void invalidate(AuthSpec.Owner owner, Session refused) {
        Slot slot = slots.get(owner);
        if (slot == null) return;
        slot.lock.lock();
        try {
            if (slot.session == refused) slot.session = null;
        } finally {
            slot.lock.unlock();
        }
    }
}
