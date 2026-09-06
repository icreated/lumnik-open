package io.lumnik.identity;

import io.lumnik.testkit.sources.IdentityHeaderRule;
import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code lumnik-identity}'s doors, against the same {@link IdentityHeaderRule} — and this is the
 * module that owns the user table, so a door here reading {@code X-User-Id} would decide who
 * someone is from what they typed, on the rows that define it.
 *
 * <p>No exempt file: identity authenticates nothing itself; the hub's filter runs in front.
 * It sits beside {@code UrlTenantReconciliationInventoryTest}, the module's other source-anchored
 * guard — same idiom, now the same scaffolding.
 */
class IdentitySourceInventoryTest {

  private static final SourceTree IDENTITY = SourceTree.of("lumnik-identity", "io/lumnik/identity");

  @Test
  void noDoorReadsTheCallersIdentityFromAHeaderItWrote() throws IOException {
    assertThat(IdentityHeaderRule.offendersIn(IDENTITY, null))
        .as(IdentityHeaderRule.RULE + " Here that means: not at all. identity authenticates nothing — "
            + "the hub's filter runs in front, and this module owns the rows that define who "
            + "someone IS.")
        .isEmpty();
  }
}
