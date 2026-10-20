package io.lumnik.hub.api;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every subtype of {@code ConnectorException} must have a stated verdict on what a caller sees when it
 * is raised — because the one that had none reached {@code lm} as a bodyless 500. That was
 * {@code ConfigValidationException}: the message naming the broken rule existed, and no
 * {@link jakarta.ws.rs.ext.ExceptionMapper} carried it across the door
 * ({@link ConfigValidationExceptionMapper} now does).
 *
 * <p>The question that found it — <em>who else does the same?</em> — is the one this guard keeps asking
 * after the session that asked it first. The answer today is "nobody": the other four never become a
 * caller's 400. But that answer is a property of call paths, and call paths change silently.
 *
 * <p><b>What this guard can and cannot check.</b> Reachability is not greppable, so it does not pretend
 * to derive a verdict — it makes the verdict a DECLARATION and then pins what source can prove, each
 * check failing in both directions:
 *
 * <ol>
 *   <li>{@link #VERDICTS} names exactly the subtypes that exist. A new one fails the build until someone
 *       states what a caller sees; a deleted one fails until its stale entry goes. This is the check
 *       that carries the guard.</li>
 *   <li>{@link Verdict#NEVER_THROWN} holds if and only if no {@code throw new That(…)} exists. The day
 *       someone throws it, the excuse expires.</li>
 *   <li>{@link Verdict#MAPPED_400} holds if and only if an {@code @Provider ExceptionMapper<That>}
 *       exists.</li>
 * </ol>
 *
 * <p>Deliberately NOT checked: that a {@code NO_GLOBAL_MAPPER} type really cannot become a 400. A guard
 * that fails for a reason which is not a defect is worth nothing, and a source regex cannot follow a
 * call graph. The reason beside each entry is where a reader looks, and check 1 is what forces them to.
 *
 * <p>Scope is {@code lumnik-hub}: {@code ConnectorException} is declared there and nothing outside
 * extends it. Its siblings one hierarchy over — {@code AuthConfigException} and the other refusal
 * {@code RuntimeException}s the hub declares — are NOT covered; widening the root is a worthwhile
 * separate lot, not a thing to claim here.
 */
class ConnectorExceptionReachInventoryTest {

  /** What a caller sees when this exception is raised. */
  private enum Verdict {
    /** Crosses a door; an {@code ExceptionMapper} dresses it as {@code 400 {"error": …}}. */
    MAPPED_400,
    /** Must NOT have a global mapper — it never reaches a caller, or a door already dresses it. */
    NO_GLOBAL_MAPPER,
    /** Declared and raised nowhere — no caller can see it. */
    NEVER_THROWN
  }

  /** Subtype → what a caller sees, with the reason beside it. Read the javadoc above before editing. */
  private static final Map<String, Verdict> VERDICTS = Map.of(
      // the apply door would let it out if a type's validateConfig disagreed with its ConfigDef.
      // Kafka is the only type a production caller reaches validateConfig for, and its two tiers now
      // agree — so the mapper is a net over a drift that is closed where it could be seen. NOT "no
      // type disagrees": csv's validateConfig refuses a missing local file with no ConfigDef
      // counterpart, and hello-world has no validator at all. Those are unreachable, not absent
      "ConfigValidationException", Verdict.MAPPED_400,
      // ConnectorTypeRegistry.find is reached from ConnectorEngine only — a run whose persisted typeId
      // has no type fails the run, it does not answer a request
      "UnknownConnectorTypeException", Verdict.NO_GLOBAL_MAPPER,
      // the connector CredentialResolver is injected into ChunkExecutor only; no resource resolves a
      // connector credential on the request path
      "CredentialNotFoundException", Verdict.NO_GLOBAL_MAPPER,
      // RetryExecutor replays it inside a run; the one door that can surface it, RestDiscoveryResource,
      // deliberately answers 502 — a transient upstream failure is not the caller's 400
      "TransientFailureException", Verdict.NO_GLOBAL_MAPPER,
      // RestEndpointReader raises it inside a run, where ChunkExecutor turns it into the run's failure
      // text (#1166); the pre-flight and the probe call the client themselves and never see it
      "SourceCallException", Verdict.NO_GLOBAL_MAPPER,
      // a complete-looking vestige: declared, never raised. Not dead weight this guard may remove on
      // its own, but weight a reader should be told about
      "MappingException", Verdict.NEVER_THROWN);

  /** {@code class X extends ConnectorException} — any modifiers, the name captured. */
  private static final Pattern SUBTYPE = Pattern.compile(
      "\\bclass\\s+(\\w+)\\s+extends\\s+ConnectorException\\b");

  /** {@code throw new XException(} — the name captured. */
  private static final Pattern THROW_NEW = Pattern.compile(
      "\\bthrow\\s+new\\s+(\\w+Exception)\\s*\\(");

  /** {@code implements ExceptionMapper<X>} — the name captured, whitespace tolerated. */
  private static final Pattern MAPPER = Pattern.compile(
      "\\bExceptionMapper\\s*<\\s*(\\w+)\\s*>");

  /**
   * The hub's production sources, comments and imports stripped, read ONCE. Every sibling inventory
   * test sweeps the tree once; four sweeps of 519 files would have made this the slowest of them for
   * no added guarantee.
   */
  private static List<String> sources;

  @BeforeAll
  static void readTheTreeOnce() throws IOException {
    SourceTree hub = SourceTree.of("lumnik-hub", "io/lumnik/hub");
    List<String> stripped = new ArrayList<>();
    for (Path file : hub.javaFiles()) stripped.add(SourceTree.code(Files.readString(file)));
    sources = stripped;
  }

  @Test
  void the_scanners_read_a_declaration_and_not_a_mention_of_one() {
    // A guard is only worth its green if it can go red, and only trustworthy if it cannot go red for
    // the wrong reason. The declaration shapes match —
    assertThat(firstGroup(SUBTYPE, "public class MappingException extends ConnectorException {")).isEqualTo("MappingException");
    assertThat(firstGroup(SUBTYPE, "final class X extends ConnectorException {")).isEqualTo("X");
    assertThat(firstGroup(THROW_NEW, "throw new MappingException(\"x\");")).isEqualTo("MappingException");
    assertThat(firstGroup(MAPPER, "implements ExceptionMapper<ConfigValidationException> {")).isEqualTo("ConfigValidationException");
    // — and a near miss does not: a subtype of a SUBTYPE is not a direct subtype, and catching,
    // rethrowing or merely naming the type is not declaring it.
    assertThat(SUBTYPE.matcher("class Y extends ConfigValidationException {").find()).isFalse();
    assertThat(SUBTYPE.matcher("} catch (ConnectorException e) {").find()).isFalse();
    assertThat(THROW_NEW.matcher("} catch (MappingException e) { throw e; }").find()).isFalse();
    assertThat(MAPPER.matcher("ExceptionMapper is a JAX-RS interface").find()).isFalse();
  }

  @Test
  void every_subtype_has_a_stated_verdict_and_every_verdict_a_subtype() {
    assertThat(scan(SUBTYPE))
        .as("a subtype of ConnectorException with no stated verdict — add it to VERDICTS with the "
            + "reason a caller can or cannot see it; that question is what found the bodyless 500")
        .containsExactlyInAnyOrderElementsOf(new TreeSet<>(VERDICTS.keySet()));
  }

  @Test
  void a_subtype_declared_never_thrown_is_still_never_thrown() {
    TreeSet<String> thrown = scan(THROW_NEW);
    thrown.retainAll(VERDICTS.keySet());

    TreeSet<String> expectedThrown = new TreeSet<>(VERDICTS.keySet());
    expectedThrown.removeAll(verdictsWith(Verdict.NEVER_THROWN));

    assertThat(thrown)
        .as("a subtype declared NEVER_THROWN has gained a throw, or a thrown one is marked as never "
            + "raised — either way the verdict has outlived its code")
        .containsExactlyInAnyOrderElementsOf(expectedThrown);
  }

  @Test
  void a_global_mapper_exists_for_exactly_the_subtypes_declared_mapped() {
    TreeSet<String> mapped = scan(MAPPER, code -> code.contains("@Provider"));
    mapped.retainAll(VERDICTS.keySet());   // check 1 already pins keySet == the real subtype set

    assertThat(mapped)
        .as("the set of ConnectorException subtypes an @Provider ExceptionMapper dresses must equal "
            + "the set declared MAPPED_400 — a mapper deleted, or one added without a verdict")
        .containsExactlyInAnyOrderElementsOf(verdictsWith(Verdict.MAPPED_400));
  }

  // ── the sweeps, over the corpus read once ──

  private static TreeSet<String> scan(Pattern p) {
    return scan(p, code -> true);
  }

  /** Every first-group capture of {@code p}, over the files whose stripped code satisfies {@code in}. */
  private static TreeSet<String> scan(Pattern p, Predicate<String> in) {
    TreeSet<String> found = new TreeSet<>();
    for (String code : sources) {
      if (!in.test(code)) continue;
      Matcher m = p.matcher(code);
      while (m.find()) found.add(m.group(1));
    }
    return found;
  }

  private static TreeSet<String> verdictsWith(Verdict v) {
    TreeSet<String> names = new TreeSet<>();
    VERDICTS.forEach((name, verdict) -> { if (verdict == v) names.add(name); });
    return names;
  }

  private static String firstGroup(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}
