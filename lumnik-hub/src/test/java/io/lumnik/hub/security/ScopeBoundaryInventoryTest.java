package io.lumnik.hub.security;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApplyScopeBoundary;
import io.lumnik.testkit.sources.ScopeBoundaryRule;
import io.lumnik.testkit.sources.ScopeBoundaryRule.Sweep;
import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The métier scope boundary, enforced as an INVENTORY rather than as a habit.
 *
 * <p>Every door was already supposed to check {@code ScopeGrants}; the four kind:View doors did and
 * the kind:Process, kind:Workflow and kind:Entity doors did not. Nothing said so out loud, so the
 * rule held only as long as whoever wrote the next door remembered it. This test makes forgetting
 * fail the build, and it is deliberately anchored on a signal from the PRODUCTION source rather than
 * on a hand-kept list of resources:
 *
 * <p><b>A door that touches a métier scope's schema — by loading its card, or by refreshing it —
 * is operating inside that scope, and must decide whether the caller holds it.</b>
 *
 * <p>Both directions break the build. A new resource that loads a card without consulting
 * {@code ScopeGrants} fails; an exemption whose file no longer exists, or that no longer touches a
 * scope at all, fails too — a stale exemption is a rule quietly widening.
 *
 * <p>This walk and its shared mechanics live behind {@link ScopeBoundaryRule}, in
 * {@code lumnik-testkit}, because the rule is not this module's alone: the rag module runs its own
 * copy of this same test, against its own axis and its own decider ({@code ScopeAccess}), from its
 * own module, so the check holds even when only that module's tests run (#508).
 *
 * <p>This is the shape {@code lm}'s TUI already uses for its describe-body escaping
 * ({@code TestEveryDescribePageIsInTheEscapeInventory}), which is why that sweep has no holes.
 */
class ScopeBoundaryInventoryTest {

  private static final SourceTree HUB_SOURCES = SourceTree.of("lumnik-hub", "io/lumnik/hub");

  /**
   * Touching any of these means the door is working inside a métier scope. The first two are
   * the scope's SCHEMA; the third is the scope's DECLARATION — a door that reads
   * {@code metadata.scopes} off a manifest is about to stamp those scopes on something, and
   * the connector apply door did exactly that for months without asking. Its scope side-effect
   * is asynchronous (SchemaCardRefresher, on the run-completed event), so the schema signal
   * alone never saw it.
   */
  private static final List<String> SCOPE_TOUCHING =
      List.of("SchemaCardStore", "DiscoveryService", "ApplyUpsert.scopes(");

  private static final List<String> DECIDING_CALLS = ScopeBoundaryRule.decidingCalls(ScopeGrants.class);

  /**
   * Doors that touch a scope's schema and deliberately do NOT bind to it. Each needs a reason a
   * reader can check, not just a name — an exemption without one is how the rule erodes.
   */
  private static final Map<String, String> EXEMPT = Map.of();

  @Test
  void everyDoorThatTouchesAScopeDecidesWhetherTheCallerHoldsIt() throws IOException {
    Sweep sweep = ScopeBoundaryRule.sweep(HUB_SOURCES, "Resource.java", SCOPE_TOUCHING,
        DECIDING_CALLS, EXEMPT.keySet());

    assertThat(sweep.seen())
        .as("the scan must find the doors it is meant to police — an empty sweep is not a clean one")
        .isNotEmpty();

    assertThat(sweep.unguarded())
        .as("""
            These doors work inside a métier scope's schema but never ask whether the caller holds \
            that scope. Either call ScopeGrants (403 "%s", the uniform refusal), or add the file to \
            EXEMPT with a reason a reader can check.""".formatted("scope not granted"))
        .isEmpty();
  }

  @Test
  void everyExemptionStillNamesADoorThatTouchesAScope() throws IOException {
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, String> e : EXEMPT.entrySet()) {
      Path p = HUB_SOURCES.find(e.getKey()).orElse(null);
      if (p == null) {
        stale.add(e.getKey() + " — no such resource any more");
        continue;
      }
      String src = Files.readString(p);
      if (!touchesAScope(src)) {
        stale.add(e.getKey() + " — no longer touches a scope, so the exemption says nothing");
      }
      if (e.getValue().isBlank()) stale.add(e.getKey() + " — exempted without a reason");
    }
    assertThat(stale)
        .as("a stale exemption is a rule quietly widening — delete it or restate why it holds")
        .isEmpty();
  }

  /** Does this door work inside one métier scope's schema? Comments do not count as code. */
  static boolean touchesAScope(String javaSource) {
    return ScopeBoundaryRule.touches(SCOPE_TOUCHING, javaSource);
  }

  /**
   * Does this door actually ASK whether the caller holds the scope?
   *
   * <p>Twice narrowed, each time for the same reason. A bare mention is not a guard — an unused
   * import, a javadoc {@code @link} or a comment explaining the check's absence all contain the
   * type name. And a reference is not a decision: {@code ScopeGrants.REFUSAL} is a constant, so a
   * door can render the refusal's words without ever asking the question. Only a deciding call
   * counts.
   */
  static boolean consultsScopeGrants(String javaSource) {
    return ScopeBoundaryRule.consults(DECIDING_CALLS, javaSource);
  }

  // ---- the second signal: WHICH scope a door asks about ----

  /**
   * The first sweep asks whether a door consults {@code ScopeGrants} at all. All five apply doors
   * did — and all five were wrong anyway, because they asked about the scope the caller DECLARES
   * and never about the scope the row they were overwriting already lives in. A bound admin could
   * post a manifest named after another métier's and the upsert re-homed it, silently (#509).
   *
   * <p>So: a door that applies a manifest must also consult {@link ApplyScopeBoundary}. That is a
   * different question from the first sweep's — "consults the guard" and "consults it about the
   * right argument" are not the same property, and only the second one was ever missing.
   *
   * <p>The axis is the FILE NAME. An earlier version keyed on the source containing
   * {@code "apply(String body"}, which made the rule depend on a local parameter name: renaming
   * {@code body} to {@code yaml} in one door would have dropped it out of the sweep while the other
   * four kept the sweep non-empty and the test green — the rule narrowing in silence, which is the
   * exact failure the sibling test below exists to catch for the other predicate. A file name is a
   * package-level convention; changing it is a visible, reviewable act.
   */
  @Test
  void everyApplyDoorAlsoAsksAboutTheScopeItIsAboutToOverwrite() throws IOException {
    Sweep sweep = sweep(HUB_SOURCES, "ApplyResource.java",
        ScopeBoundaryInventoryTest::consultsTheStoredScope);

    assertThat(sweep.seen())
        .as("the sweep must find the apply doors it is meant to police")
        .isNotEmpty();

    assertThat(sweep.unguarded())
        .as("""
            These doors apply a manifest and upsert it on its NAME, but only ever ask whether the \
            caller holds the scope the manifest DECLARES. Call \
            ApplyScopeBoundary.refuseUnlessCallerHolds(…) on the STORED scope too, or the apply \
            re-homes a manifest out of a métier its owner holds and nothing is said.""")
        .isEmpty();
  }

  /**
   * The file-name axis, cross-checked against the bodies. Two independent signals have to agree:
   * every resource that takes a manifest body must ALSO be named {@code *ApplyResource.java}, or it
   * is invisible to the sweep above. This is what keeps the cheap anchor honest without a hand-kept
   * list of the five — the expectation is derived from the production sources, both times.
   */
  @Test
  void noDoorTakesAManifestWithoutBeingNamedLikeOne() throws IOException {
    List<String> takesAManifest = new ArrayList<>();
    for (Path p : HUB_SOURCES.javaFiles("Resource.java")) {
      if (SourceTree.code(Files.readString(p)).contains("apply(String ")) {
        takesAManifest.add(p.getFileName().toString());
      }
    }

    assertThat(takesAManifest)
        .as("a resource whose body takes a manifest but whose name does not end in "
            + "ApplyResource.java would never be swept for the stored-scope check")
        .isNotEmpty()
        .allSatisfy(name -> assertThat(name).endsWith("ApplyResource.java"));
  }

  /** Does this door ask about the row it is about to overwrite? */
  static boolean consultsTheStoredScope(String javaSource) {
    return SourceTree.code(javaSource).contains("ApplyScopeBoundary.refuseUnless");
  }

  /**
   * A grep cannot tell a call from an acted-on call — so the boundary is shaped to remove the
   * difference.
   *
   * <p>While it returned the refusal, {@code refuseUnlessCallerHolds(store.findScope(name));} on a
   * line of its own compiled, refused nothing, and satisfied the sweep above. The guardian was
   * guarded against comments and unused imports but not against the one failure that mattered.
   * {@code void} + throw is what makes the grep sound, so the return type is itself the invariant.
   */
  @Test
  void theBoundaryCannotBeCalledAndIgnored() throws NoSuchMethodException {
    for (Method m : ApplyScopeBoundary.class.getMethods()) {
      if (m.getDeclaringClass() != ApplyScopeBoundary.class) continue;
      assertThat(m.getReturnType())
          .as("%s must not hand back a refusal a caller can drop on the floor", m.getName())
          .isEqualTo(void.class);
    }
    assertThat(ApplyScopeBoundary.class.getMethod("refuseUnlessCallerHolds", Optional.class))
        .as("and the sweep's signal must name a method that exists")
        .isNotNull();
  }

  @Test
  void theStoredScopeSignalIsCodeNotProse() {
    assertThat(consultsTheStoredScope(
        "class R { /* ApplyScopeBoundary.refuseUnlessCallerHolds is not needed here */ }"))
        .as("a comment explaining the absence of the check is not the check")
        .isFalse();
    assertThat(consultsTheStoredScope("import io.lumnik.hub.api.ApplyScopeBoundary;\nclass R {}"))
        .as("an unused import is not the check either")
        .isFalse();
    assertThat(consultsTheStoredScope(
        "class R { void f() { ApplyScopeBoundary.refuseUnlessCallerHolds(s); } }"))
        .as("the call is the check — and being void, calling it IS acting on it")
        .isTrue();
  }

  // ---- the apply-door sweep (hub-only: ApplyScopeBoundary has no rag counterpart) ----

  /**
   * Walk the hub's resources whose name ends with {@code suffix}, and report the {@code guarded}
   * rejects. Unlike the first sweep (now {@link ScopeBoundaryRule#sweep}), there is no separate
   * on-axis filter here: the file-name suffix IS the axis (see {@link #noDoorTakesAManifestWithoutBeingNamedLikeOne}).
   */
  private static Sweep sweep(SourceTree root, String suffix, Predicate<String> guarded)
      throws IOException {
    List<String> seen = new ArrayList<>();
    Map<String, String> unguarded = new LinkedHashMap<>();
    for (Path p : root.javaFiles(suffix)) {
      String src = Files.readString(p);
      String name = p.getFileName().toString();
      seen.add(name);
      if (!guarded.test(src)) unguarded.put(name, pathOf(src));
    }
    return new Sweep(seen, unguarded);
  }

  // ---- the guardian, guarded ----

  @Test
  void aBareMentionOfScopeGrantsIsNotAGuard() {
    assertThat(consultsScopeGrants("import io.lumnik.common.security.ScopeGrants;\nclass R {}"))
        .as("an unused import must not satisfy the inventory")
        .isFalse();
    assertThat(consultsScopeGrants("class R { /* ScopeGrants is not needed here */ }"))
        .as("a comment explaining the absence of the check is not the check")
        .isFalse();
    assertThat(consultsScopeGrants("class R { /** {@link ScopeGrants} */ void f() {} }"))
        .as("a javadoc link is not the check")
        .isFalse();
    assertThat(consultsScopeGrants("class R { Response no() { return error(ScopeGrants.REFUSAL); } }"))
        .as("naming the refusal CONSTANT is not deciding — a door can render the words "
            + "without ever asking the question")
        .isFalse();
    assertThat(consultsScopeGrants("class R { void f() { if (!ScopeGrants.allows(s)) return; } }"))
        .as("allows() is a decision").isTrue();
    assertThat(consultsScopeGrants("class R { void f() { if (!ScopeGrants.allowsAny(ss)) return; } }"))
        .as("allowsAny() is a decision").isTrue();
    assertThat(consultsScopeGrants("class R { void f() { var g = ScopeGrants.granted(); } }"))
        .as("granted() is a decision — it is how a list filters itself").isTrue();
  }

  @Test
  void theDecidingCallsAreDerivedFromThePrimitiveNotCopiedFromIt() {
    assertThat(DECIDING_CALLS)
        .as("every @ScopeDecision method of ScopeGrants asks something, so all of them count — "
            + "and a new one is picked up without editing this test")
        .contains("ScopeGrants.allows(", "ScopeGrants.allowsAny(",
                  "ScopeGrants.allowsAll(", "ScopeGrants.granted(");
    assertThat(DECIDING_CALLS)
        .as("REFUSAL is a field, so it can never enter this list")
        .noneSatisfy(c -> assertThat(c).contains("REFUSAL"));
    assertThat(DECIDING_CALLS)
        .as("nameProblem() validates a NAME's shape and grantedForLog() renders a log line — "
            + "neither asks whether the caller holds a scope, so neither may enter this list "
            + "just for being public and static")
        .doesNotContain("ScopeGrants.nameProblem(", "ScopeGrants.grantedForLog(");
  }

  @Test
  void aScopeTypeNamedOnlyInACommentDoesNotPutADoorOnTheAxis() {
    assertThat(touchesAScope("class R { /* unlike SchemaCardStore, this door is scopeless */ }"))
        .as("a door is on the axis because of what it USES, not what it mentions")
        .isFalse();
    assertThat(touchesAScope("import io.lumnik.hub.ask.SchemaCardStore;\nclass R {}"))
        .as("an unused import must not put a door on the axis either — the same rule that stops "
            + "an import counting as a guard, applied to the other predicate")
        .isFalse();
    assertThat(touchesAScope("class R { @Inject SchemaCardStore cards; }")).isTrue();
  }

  /** The @Path of the resource, so a failure names the URL an integrator would actually call. */
  private static String pathOf(String src) {
    int at = src.indexOf("@Path(\"");
    if (at < 0) return "(no @Path)";
    int end = src.indexOf('"', at + 7);
    return end < 0 ? "(no @Path)" : src.substring(at + 7, end);
  }
}
