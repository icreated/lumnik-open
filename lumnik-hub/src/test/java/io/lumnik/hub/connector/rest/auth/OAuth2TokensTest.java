package io.lumnik.hub.connector.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** #1393: one token per connector, per tenant, per grant — asked for once, renewed when due. */
class OAuth2TokensTest {

    private static final AuthSpec.Owner OWNER = new AuthSpec.Owner(1L, UUID.randomUUID());

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-23T08:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final OAuth2Tokens tokens = new OAuth2Tokens(clock);
    private final AtomicInteger fetches = new AtomicInteger();

    private OAuth2Token fetch(Instant now) {
        return new OAuth2Token("t" + fetches.incrementAndGet(), now.plusSeconds(27));
    }

    @Test
    void a_token_is_reused_until_it_is_due_then_renewed() throws Exception {
        assertThat(tokens.current(OWNER, "grant", this::fetch).value()).isEqualTo("t1");
        clock.now = clock.now.plusSeconds(26);
        assertThat(tokens.current(OWNER, "grant", this::fetch).value()).isEqualTo("t1");
        clock.now = clock.now.plusSeconds(1);
        assertThat(tokens.current(OWNER, "grant", this::fetch).value()).isEqualTo("t2");
    }

    @Test
    void only_the_refused_token_is_dropped() throws Exception {
        OAuth2Token first = tokens.current(OWNER, "grant", this::fetch);
        tokens.invalidate(OWNER, first);
        OAuth2Token renewed = tokens.current(OWNER, "grant", this::fetch);
        // A second reader that saw the same 401 on the first token must not drop the renewed one.
        tokens.invalidate(OWNER, first);
        assertThat(tokens.current(OWNER, "grant", this::fetch)).isSameAs(renewed);
        assertThat(fetches).hasValue(2);
    }

    @Test
    void concurrent_reads_of_one_connector_ask_once() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<OAuth2Token>> got = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                got.add(pool.submit(() -> {
                    start.await();
                    return tokens.current(OWNER, "grant", now -> { Thread.sleep(20); return fetch(now); });
                }));
            }
            start.countDown();
            for (Future<OAuth2Token> f : got) assertThat(f.get().value()).isEqualTo("t1");
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void two_connectors_or_two_tenants_sharing_a_grant_each_get_their_own_token() throws Exception {
        UUID connector = UUID.randomUUID();
        OAuth2Token a = tokens.current(new AuthSpec.Owner(1L, connector), "same", this::fetch);
        OAuth2Token otherTenant = tokens.current(new AuthSpec.Owner(2L, connector), "same", this::fetch);
        OAuth2Token otherConnector = tokens.current(new AuthSpec.Owner(1L, UUID.randomUUID()), "same", this::fetch);
        assertThat(List.of(a.value(), otherTenant.value(), otherConnector.value())).containsExactly("t1", "t2", "t3");
    }

    @Test
    void a_re_apply_with_another_grant_drops_the_token_of_the_old_one() throws Exception {
        assertThat(tokens.current(OWNER, "grant", this::fetch).value()).isEqualTo("t1");
        assertThat(tokens.current(OWNER, "another scope", this::fetch).value()).isEqualTo("t2");
        assertThat(tokens.current(OWNER, "another scope", this::fetch).value()).isEqualTo("t2");
    }

    @Test
    void a_failed_fetch_holds_nothing_and_the_next_read_asks_again() throws Exception {
        try {
            tokens.current(OWNER, "grant", now -> { throw new AuthConfigException("refused"); });
        } catch (AuthConfigException expected) {
            // the grant was refused
        }
        assertThat(tokens.current(OWNER, "grant", this::fetch).value()).isEqualTo("t1");
    }
}
