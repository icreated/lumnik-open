package io.lumnik.hub.security;

import io.quarkus.runtime.Startup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1996: one user looping a door spends their own share, never the whole tenant's. */
class RateLimitFilterTest {

    private static final long NOW = 1_800_000_020L;   // 20 s into a window (1_800_000_000 is a multiple of 60)

    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    private boolean call(Long tenant, Long user, int rpm, int tenantRpm) {
        return verdict(tenant, user, rpm, tenantRpm).decision().allowed();
    }

    private RateLimitFilter.Verdict verdict(Long tenant, Long user, int rpm, int tenantRpm) {
        return RateLimitFilter.decide(limiter, "user-chat", String.valueOf(tenant), user, rpm, tenantRpm, NOW);
    }

    @Test
    void aUserAtTheLimitLeavesTheOtherUsersOfTheTenantServed() {
        assertThat(call(7L, 1L, 2, 10)).isTrue();
        assertThat(call(7L, 1L, 2, 10)).isTrue();
        assertThat(call(7L, 1L, 2, 10)).as("user 1's own share is spent").isFalse();
        assertThat(call(7L, 2L, 2, 10)).as("user 2 of the same tenant is still served").isTrue();
    }

    @Test
    void theTenantCeilingStillTrips() {
        assertThat(call(7L, 1L, 2, 3)).isTrue();
        assertThat(call(7L, 1L, 2, 3)).isTrue();
        assertThat(call(7L, 2L, 2, 3)).isTrue();
        assertThat(call(7L, 3L, 2, 3)).as("three calls spent the tenant's ceiling, whoever made them").isFalse();
    }

    @Test
    void aCallTheCeilingRefusesGivesItsUsersSlotBack() {
        assertThat(call(7L, 1L, 2, 2)).isTrue();
        assertThat(call(7L, 2L, 2, 2)).isTrue();
        assertThat(call(7L, 3L, 2, 2)).as("the ceiling is spent").isFalse();
        assertThat(call(7L, 3L, 2, 2)).isFalse();
        assertThat(limiter.tryAcquire("user-chat|7|3", 2, NOW).allowed())
            .as("user 3 was refused twice by the ceiling: their own share is untouched").isTrue();
    }

    @Test
    void theVerdictNamesTheLimitThatRefused() {
        call(7L, 1L, 1, 5);
        assertThat(verdict(7L, 1L, 1, 5).byCeiling()).as("its own share").isFalse();
        call(8L, 1L, 5, 1);
        assertThat(verdict(8L, 2L, 5, 1).byCeiling()).as("the tenant's ceiling").isTrue();
    }

    /** A call with no user has a budget of its own: it neither fills the users' ceiling nor is refused by it. */
    @Test
    void aCallWithNoUserDoesNotShareTheUsersCeiling() {
        assertThat(call(7L, 1L, 2, 2)).isTrue();
        assertThat(call(7L, 2L, 2, 2)).isTrue();
        assertThat(call(7L, null, 2, 2)).as("the users' ceiling is spent, not the user-less budget").isTrue();
        assertThat(call(7L, null, 2, 2)).isTrue();
        assertThat(call(7L, null, 2, 2)).isFalse();
    }

    @Test
    void aNegativeFactorRefusesTheBoot() {
        assertThat(RateLimitFilter.class.isAnnotationPresent(Startup.class))
            .as("@Startup is what makes the check a boot refusal, not a first-request one").isTrue();
        RateLimitFilter filter = new RateLimitFilter();
        filter.tenantFactor = -1;
        assertThatThrownBy(filter::refuseANegativeFactor).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lumnik.ratelimit.tenant-factor");
        filter.tenantFactor = 0;
        filter.refuseANegativeFactor();   // 0 is the documented "no ceiling"
    }

    @Test
    void theCeilingSaturatesInsteadOfWrappingNegative() {
        assertThat(RateLimitFilter.tenantRpm(60, 5)).isEqualTo(300);
        assertThat(RateLimitFilter.tenantRpm(Integer.MAX_VALUE, 5)).isEqualTo(Integer.MAX_VALUE);
        assertThat(RateLimitFilter.tenantRpm(60, 0)).as("0 switches the ceiling off").isZero();
    }

    @Test
    void aRefusedUserDoesNotSpendTheTenantsCeiling() {
        for (int i = 0; i < 10; i++) call(7L, 1L, 2, 3);   // user 1: two served, eight refused on their own share
        assertThat(call(7L, 2L, 2, 3)).as("the tenant has spent 2 of 3").isTrue();
        assertThat(call(7L, 3L, 2, 3)).isFalse();
    }

    @Test
    void aRequestWithNoUserIsKeyedOnItsTenantAsBefore() {
        assertThat(call(7L, null, 2, 10)).isTrue();
        assertThat(call(7L, null, 2, 10)).isTrue();
        assertThat(call(7L, null, 2, 10)).isFalse();
        assertThat(call(8L, null, 2, 10)).as("another tenant").isTrue();
    }

    @Test
    void aZeroRpmLimitsNothing() {
        for (int i = 0; i < 100; i++) assertThat(call(7L, 1L, 0, 0)).isTrue();
    }

    @Test
    void theRefusalSaysWhenToRetry() {
        call(7L, 1L, 1, 10);
        assertThat(verdict(7L, 1L, 1, 10).decision().retryAfterSeconds()).isEqualTo(40);
    }
}
