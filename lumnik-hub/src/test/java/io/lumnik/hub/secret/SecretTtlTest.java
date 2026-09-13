package io.lumnik.hub.secret;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A miss must expire far sooner than a hit, and the reason is a deployment shape no unit test can
 * stand up: the cache is per hub instance with no cross-node invalidation, so a second replica that
 * had already asked for a name keeps answering from its own copy. A stale VALUE was always possible
 * after a rotation; "this name exists now" used to be visible everywhere at once, and remembering
 * misses is what took that away.
 *
 * <p>Pinned on the policy rather than on the clock — a timing test would either sleep for thirty
 * seconds or need a ticker injected into production for its sake.
 */
class SecretTtlTest {

    private final PgcryptoSecretResolver.SecretTtl ttl = new PgcryptoSecretResolver.SecretTtl();

    @Test
    void aRememberedMissExpiresSoonerThanAResolvedValue() {
        long miss = ttl.expireAfterCreate("1:NAME", Optional.empty(), 0L);
        long hit = ttl.expireAfterCreate("1:NAME", Optional.of("v"), 0L);

        assertThat(miss)
            .as("a miss reused as long as a hit would refuse a run for minutes after `lm secret set`")
            .isLessThan(hit);
        assertThat(miss).isEqualTo(PgcryptoSecretResolver.MISS_TTL.toNanos());
        assertThat(hit).isEqualTo(PgcryptoSecretResolver.HIT_TTL.toNanos());
    }

    @Test
    void aMissStillOutlivesAChunkLoop() {
        // What the short TTL must NOT undo: the reader is rebuilt once per chunk, so a miss that
        // expired between chunks would put the per-chunk database round-trip straight back.
        assertThat(PgcryptoSecretResolver.MISS_TTL.toSeconds()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void replacingAnEntryReDecidesItsLife_andReadingItDoesNot() {
        // A miss overwritten by a resolved value must get the LONG life, not inherit the short one.
        assertThat(ttl.expireAfterUpdate("1:NAME", Optional.of("v"), 0L, PgcryptoSecretResolver.MISS_TTL.toNanos()))
            .isEqualTo(PgcryptoSecretResolver.HIT_TTL.toNanos());
        // And a read must not extend either — expiry is from write, as it has always been.
        assertThat(ttl.expireAfterRead("1:NAME", Optional.of("v"), 0L, 123L)).isEqualTo(123L);
    }
}
