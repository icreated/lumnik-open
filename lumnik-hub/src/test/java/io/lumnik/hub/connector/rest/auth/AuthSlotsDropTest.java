package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * #1775: a deleted connector's slot goes with it, not an hour later with the idle sweep. Both holders follow one
 * protocol, so both are pinned here.
 */
class AuthSlotsDropTest {

    private static final AuthSpec.Owner OWNER = new AuthSpec.Owner(1L, UUID.randomUUID());
    private static final AuthSpec.Owner OTHER = new AuthSpec.Owner(1L, UUID.randomUUID());

    private final OAuth2Tokens tokens = new OAuth2Tokens();
    private final SessionCookies sessions = new SessionCookies();
    private final AtomicInteger issued = new AtomicInteger();

    private OAuth2Token token(Instant now) { return new OAuth2Token("t" + issued.incrementAndGet(), now.plusSeconds(86_400)); }
    private SessionCookies.Session session() { return new SessionCookies.Session(Map.of("sid", "s" + issued.incrementAndGet())); }

    @Test
    void a_dropped_token_slot_is_gone_and_only_that_one() throws Exception {
        tokens.current(OWNER, "g", this::token);
        tokens.current(OTHER, "g", this::token);

        tokens.drop(OWNER);

        assertThat(tokens.size()).isEqualTo(1);
        assertThat(tokens.current(OTHER, "g", this::token).value()).as("the other connector keeps its token").isEqualTo("t2");
        assertThat(tokens.current(OWNER, "g", this::token).value()).as("fetched again").isEqualTo("t3");
    }

    @Test
    void a_dropped_session_slot_is_gone_and_only_that_one() throws Exception {
        sessions.current(OWNER, "l", this::session);
        sessions.current(OTHER, "l", this::session);

        sessions.drop(OWNER);

        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessions.current(OTHER, "l", this::session).jar()).containsEntry("sid", "s2");
        assertThat(sessions.current(OWNER, "l", this::session).jar()).as("logged in again").containsEntry("sid", "s3");
    }

    @Test
    void dropping_what_is_not_held_is_a_no_op() {
        tokens.drop(OWNER);
        sessions.drop(OWNER);

        assertThat(tokens.size()).isZero();
        assertThat(sessions.size()).isZero();
    }

    /**
     * A reader is queued on the slot's lock while the first login runs; the connector is deleted meanwhile. It must
     * not take the deleted connector's session: it goes back to the map and opens its own.
     */
    @Test
    void a_session_reader_queued_on_a_dropped_slot_does_not_reuse_its_session() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                sessions.current(OWNER, "l", () -> {
                    inside.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return session();
                });
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        SessionCookies.Session[] second = new SessionCookies.Session[1];
        Thread queued = new Thread(() -> {
            try { second[0] = sessions.current(OWNER, "l", this::session); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        queued.start();
        awaitParked(queued);

        sessions.drop(OWNER);
        release.countDown();
        first.join(5_000);
        queued.join(5_000);

        assertThat(second[0].jar()).as("opened for the live slot, not the dropped one's").containsEntry("sid", "s2");
    }

    @Test
    void a_token_reader_queued_on_a_dropped_slot_does_not_reuse_its_token() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                tokens.current(OWNER, "g", now -> {
                    inside.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return token(now);
                });
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        OAuth2Token[] second = new OAuth2Token[1];
        Thread queued = new Thread(() -> {
            try { second[0] = tokens.current(OWNER, "g", this::token); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        queued.start();
        awaitParked(queued);

        tokens.drop(OWNER);
        release.countDown();
        first.join(5_000);
        queued.join(5_000);

        assertThat(second[0].value()).as("fetched for the live slot, not the dropped one's").isEqualTo("t2");
    }

    /** Parked on the slot's lock: the only thing a reader can wait on while the first login is held open. */
    private static void awaitParked(Thread t) throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < limit) Thread.sleep(5);
        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
    }

    /** The delete door calls this: it must not wait out a login the source is slow to answer. */
    @Test
    void a_drop_does_not_wait_for_a_login_in_flight() throws Exception {
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

            Future<?> dropped = pool.submit(() -> sessions.drop(OWNER));
            dropped.get(2, TimeUnit.SECONDS);
            assertThat(sessions.size()).isZero();

            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
