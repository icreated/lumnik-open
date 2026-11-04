package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * #1768: the slot of a connector nobody reads any more is dropped. Both holders follow one protocol, so both are
 * pinned here, on one timeline the test writes.
 */
class AuthSlotsIdleTest {

    private static final AuthSpec.Owner OWNER = new AuthSpec.Owner(1L, UUID.randomUUID());
    private static final AuthSpec.Owner OTHER = new AuthSpec.Owner(1L, UUID.randomUUID());

    private static final class MovableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-03T08:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final OAuth2Tokens tokens = new OAuth2Tokens(clock);
    private final SessionCookies sessions = new SessionCookies(clock);
    private final AtomicInteger fetches = new AtomicInteger();

    private OAuth2Token token(Instant now) { return new OAuth2Token("t" + fetches.incrementAndGet(), now.plusSeconds(86_400)); }
    private SessionCookies.Session session() { return new SessionCookies.Session(Map.of("sid", "s" + fetches.incrementAndGet())); }

    @Test
    void a_token_slot_nobody_asked_for_is_dropped_and_fetched_again_on_the_next_ask() throws Exception {
        tokens.current(OWNER, "g", this::token);

        clock.now = clock.now.plus(OAuth2Tokens.IDLE_TTL).minusSeconds(1);
        tokens.dropIdle();
        assertThat(tokens.size()).as("one second short of the limit").isEqualTo(1);

        clock.now = clock.now.plusSeconds(1);
        tokens.dropIdle();
        assertThat(tokens.size()).isZero();

        assertThat(tokens.current(OWNER, "g", this::token).value()).isEqualTo("t2");
    }

    @Test
    void asking_again_restarts_the_token_clock() throws Exception {
        tokens.current(OWNER, "g", this::token);
        clock.now = clock.now.plus(OAuth2Tokens.IDLE_TTL).minusSeconds(1);
        tokens.current(OWNER, "g", this::token);

        clock.now = clock.now.plusSeconds(60);
        tokens.dropIdle();
        assertThat(tokens.size()).isEqualTo(1);
    }

    @Test
    void only_the_idle_token_slot_goes() throws Exception {
        tokens.current(OWNER, "g", this::token);
        clock.now = clock.now.plus(OAuth2Tokens.IDLE_TTL).minusSeconds(1);
        tokens.current(OTHER, "g", this::token);

        clock.now = clock.now.plusSeconds(1);
        tokens.dropIdle();
        assertThat(tokens.size()).isEqualTo(1);
    }

    @Test
    void a_session_slot_nobody_asked_for_is_dropped_and_opened_again_on_the_next_ask() throws Exception {
        sessions.current(OWNER, "l", this::session);

        clock.now = clock.now.plus(SessionCookies.IDLE_TTL).minusSeconds(1);
        sessions.dropIdle();
        assertThat(sessions.size()).as("one second short of the limit").isEqualTo(1);

        clock.now = clock.now.plusSeconds(1);
        sessions.dropIdle();
        assertThat(sessions.size()).isZero();

        assertThat(sessions.current(OWNER, "l", this::session).jar()).containsEntry("sid", "s2");
    }

    @Test
    void asking_again_restarts_the_session_clock() throws Exception {
        sessions.current(OWNER, "l", this::session);
        clock.now = clock.now.plus(SessionCookies.IDLE_TTL).minusSeconds(1);
        sessions.current(OWNER, "l", this::session);

        clock.now = clock.now.plusSeconds(60);
        sessions.dropIdle();
        assertThat(sessions.size()).isEqualTo(1);
    }

    /** A login in flight holds the lock for as long as the source takes: it is not idle, whatever the clock says. */
    @Test
    void a_slot_whose_lock_is_held_is_not_dropped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> slow = pool.submit(() -> sessions.current(OWNER, "l", () -> {
                inside.countDown();
                release.await(10, TimeUnit.SECONDS);
                return session();
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            clock.now = clock.now.plus(SessionCookies.IDLE_TTL).plusSeconds(1);
            sessions.dropIdle();
            assertThat(sessions.size()).isEqualTo(1);

            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Two readers are queued on a slot's lock while its login runs; the sweep then drops the slot. They must go back
     * to the map and share the live slot's single login — not take the orphan's session, not each log in.
     * The sweep runs on the login's own thread, inside it: the lock is reentrant there, so no timing is involved.
     */
    @Test
    void readers_queued_on_a_dropped_slot_retry_onto_the_live_one_and_log_in_once() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                sessions.current(OWNER, "l", () -> {
                    inside.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    clock.now = clock.now.plus(SessionCookies.IDLE_TTL).plusSeconds(1);
                    sessions.dropIdle();   // drops the slot this very login is filling
                    return session();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        Thread[] queued = new Thread[2];
        for (int i = 0; i < queued.length; i++) {
            queued[i] = new Thread(() -> {
                try {
                    sessions.current(OWNER, "l", this::session);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            queued[i].start();
        }
        for (Thread t : queued) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!parked(t) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(parked(t)).as("parked on the slot's lock").isTrue();
        }

        release.countDown();
        first.join(5_000);
        for (Thread t : queued) t.join(5_000);

        assertThat(fetches).as("the orphan's login, then one on the live slot").hasValue(2);
        assertThat(sessions.size()).isEqualTo(1);
    }

    /** Blocked in any way the JVM reports it: waiting on the lock is WAITING in practice, but it does not promise it. */
    private static boolean parked(Thread t) {
        Thread.State state = t.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED;
    }
}
