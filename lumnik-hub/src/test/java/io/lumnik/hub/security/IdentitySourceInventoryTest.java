package io.lumnik.hub.security;

import io.lumnik.testkit.sources.IdentityHeaderRule;
import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hub's doors, against {@link IdentityHeaderRule} — where the incident happened: five doors
 * took {@code @HeaderParam("X-User-Id")}, so an authenticated caller could act as someone else and
 * three audit sites recorded user 0 for every manual action.
 *
 * <p>The rule and the sweep live in {@code lumnik-testkit} so {@code lumnik-rag} and
 * {@code lumnik-identity} — which owns the user table — are held to the same one, each from its own
 * module (a sweep run from another module's cwd would break the two-runner support SourceTree
 * exists for).
 */
class IdentitySourceInventoryTest {

  /**
   * The one file allowed to turn a header into an identity: it does so only under dev-bypass, and
   * it is the door where deactivation and impersonation are checked.
   */
  private static final String THE_FILTER = "TenantContextFilter.java";

  private static final SourceTree HUB = SourceTree.of("lumnik-hub", "io/lumnik/hub");

  @Test
  void noDoorReadsTheCallersIdentityFromAHeaderItWrote() throws IOException {
    assertThat(IdentityHeaderRule.offendersIn(HUB, THE_FILTER))
        .as(IdentityHeaderRule.RULE + " Here: RequireUser.current() / RequireTenant.current() when the "
            + "door needs an identity, TenantContextFilter.currentUser() when null legitimately "
            + "means \"no actor\".")
        .isEmpty();
  }

  @Test
  void theSweepWouldSeeABreach() {
    var rule = IdentityHeaderRule.IDENTITY_HEADER;
    assertThat(rule.matcher(SourceTree.code(
        "class R { void f(@HeaderParam(\"X-User-Id\") Long u) {} }")).find())
        .as("a door taking the user header must be seen").isTrue();
    assertThat(rule.matcher(SourceTree.code(
        "class R { void f(@HeaderParam(value = \"X-User-Id\") Long u) {} }")).find())
        .as("the explicit-member form is the same annotation — a guard that only knows one "
            + "spelling is a guard with a door in it")
        .isTrue();
    assertThat(rule.matcher(SourceTree.code(
        "class R { void f(@jakarta.ws.rs.HeaderParam(\"X-User-Id\") Long u) {} }")).find())
        .as("the fully-qualified form needs no import, so stripping imports never reaches it — "
            + "a mutation planted exactly this and walked past the guard")
        .isTrue();
    assertThat(rule.matcher(SourceTree.code(
        "class R { /* @HeaderParam(\"X-User-Id\") is forbidden */ }")).find())
        .as("a comment naming the rule is not a breach of it").isFalse();
    assertThat(rule.matcher(SourceTree.code(
        "class R { void f(@HeaderParam(\"X-Impersonate-Tenant\") String t) {} }")).find())
        .as("only the two identity headers are on the axis — impersonation is the filter's business")
        .isFalse();
  }

  @Test
  void anExemptionThatNamesNothingIsRefused() {
    // A stale exemption is a rule quietly widening: if the filter is renamed and this test is not,
    // the sweep must fail rather than silently exempt a file that no longer exists.
    assertThatThrownBy(() -> IdentityHeaderRule.offendersIn(HUB, "NoSuchFilter.java"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("quietly widening");
  }
}
