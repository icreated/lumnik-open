package io.lumnik.hub.security;

import io.lumnik.common.security.ScopeGrants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
 * <p>This is the shape {@code lm}'s TUI already uses for its describe-body escaping
 * ({@code TestEveryDescribePageIsInTheEscapeInventory}), which is why that sweep has no holes.
 */
class ScopeBoundaryInventoryTest {

  /**
   * Resolved rather than hardcoded: surefire runs with the module as cwd, but an IDE or a custom
   * runner may use the reactor root. A guardrail that only works under one runner is a guardrail
   * someone will quietly stop running.
   */
  private static final Path RESOURCES_ROOT = resolveResourcesRoot();

  private static Path resolveResourcesRoot() {
    Path fromModule = Path.of("src/main/java/io/lumnik/hub");
    Path fromReactor = Path.of("lumnik-hub/src/main/java/io/lumnik/hub");
    if (Files.isDirectory(fromModule)) return fromModule;
    if (Files.isDirectory(fromReactor)) return fromReactor;
    throw new IllegalStateException(
        "cannot locate the hub resources from " + Path.of("").toAbsolutePath()
            + " — the scope inventory cannot police what it cannot find");
  }

  /** Touching either of these means the door is working inside one métier scope's schema. */
  private static final List<String> SCOPE_TOUCHING = List.of("SchemaCardStore", "DiscoveryService");

  /**
   * Doors that touch a scope's schema and deliberately do NOT bind to it. Each needs a reason a
   * reader can check, not just a name — an exemption without one is how the rule erodes.
   */
  private static final Map<String, String> EXEMPT = Map.of();

  @Test
  void everyDoorThatTouchesAScopeDecidesWhetherTheCallerHoldsIt() throws IOException {
    Map<String, String> unguarded = new LinkedHashMap<>();
    List<String> seen = new ArrayList<>();

    try (Stream<Path> files = Files.walk(RESOURCES_ROOT)) {
      for (Path p : files.filter(f -> f.getFileName().toString().endsWith("Resource.java")).toList()) {
        String src = Files.readString(p);
        if (!touchesAScope(src)) continue;

        String name = p.getFileName().toString();
        seen.add(name);
        if (!consultsScopeGrants(src) && !EXEMPT.containsKey(name)) {
          unguarded.put(name, pathOf(src));
        }
      }
    }

    assertThat(seen)
        .as("the scan must find the doors it is meant to police — an empty sweep is not a clean one")
        .isNotEmpty();

    assertThat(unguarded)
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
      Path p = find(e.getKey());
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
    String code = code(javaSource);
    return SCOPE_TOUCHING.stream().anyMatch(code::contains);
  }

  /**
   * The calls that ASK the question, derived from {@link ScopeGrants} itself rather than copied
   * from it. A hand-kept list goes stale the moment the primitive grows a method — which is exactly
   * what happened: adding {@code allowsAll} for write doors made this sweep report an
   * already-guarded door as unguarded. Reflection over the public static METHODS also excludes
   * {@code REFUSAL} for free, because a constant is a field: it renders the answer's words without
   * asking anything.
   */
  private static final List<String> DECIDING_CALLS = Arrays.stream(ScopeGrants.class.getMethods())
      .filter(m -> Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()))
      .filter(m -> m.getDeclaringClass() == ScopeGrants.class)
      .map(m -> "ScopeGrants." + m.getName() + "(")
      .distinct()
      .toList();

  /**
   * Does this door actually ASK whether the caller holds the scope?
   *
   * <p>Twice narrowed, each time for the same reason. A bare mention is not a guard — an unused
   * import, a javadoc {@code @link} or a comment explaining the check's absence all contain the
   * type name. And a reference is not a decision: {@code ScopeGrants.REFUSAL} is a constant, so a
   * door can render the refusal's words without ever asking the question. Only the three deciding
   * calls count.
   */
  static boolean consultsScopeGrants(String javaSource) {
    String code = code(javaSource);
    return DECIDING_CALLS.stream().anyMatch(code::contains);
  }

  /**
   * The source with comments AND import lines removed. A sentence about the rule never passes for
   * the rule, and a type someone forgot to stop importing is not a type the door uses — the same
   * mention-is-not-use test applied to both predicates, so neither can be tripped by a leftover.
   */
  private static String code(String javaSource) {
    return javaSource
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//[^\n]*", " ")
        .replaceAll("(?m)^\\s*import\\s+[^;]+;", " ");
  }

  private static Path find(String fileName) throws IOException {
    try (Stream<Path> files = Files.walk(RESOURCES_ROOT)) {
      return files.filter(f -> f.getFileName().toString().equals(fileName)).findFirst().orElse(null);
    }
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
        .as("every public static method of ScopeGrants asks something, so all of them count — "
            + "and a new one is picked up without editing this test")
        .contains("ScopeGrants.allows(", "ScopeGrants.allowsAny(",
                  "ScopeGrants.allowsAll(", "ScopeGrants.granted(");
    assertThat(DECIDING_CALLS)
        .as("REFUSAL is a field, so it can never enter this list")
        .noneSatisfy(c -> assertThat(c).contains("REFUSAL"));
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
