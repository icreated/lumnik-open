package io.lumnik.hub.api;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 404 the hub sends must carry a body — {@code ApiErrors.notFound(what)} produces
 * {@code 404 {"error": …}}, so {@code lm} and the PWA can say WHAT was missing. A bare
 * {@code Response.status(404).build()} reaches the client as "no details from the server", the
 * exact gap {@link ApiErrors#notFound} was written to close.
 *
 * <p>This guard reads PRODUCTION SOURCE (comments and imports stripped, so a {@code 404} in a
 * sentence is not a {@code 404} in the code) and fails if any hub source has a bare
 * {@code .status(404).build()} — {@code 404} or {@code Response.Status.NOT_FOUND}, with no
 * {@code .entity(…)} before {@code .build()}. It makes the next silent 404 OF THAT SHAPE impossible
 * to add without either giving it a body or naming it below.
 *
 * <p><b>The scope of THIS guard is the return-site shape:</b> {@code Response.status(404).build()}.
 * The OTHER bodyless-404 channel — a resource that returns a domain type and says "not found" by
 * {@code throw new NotFoundException()} — is a different syntactic dress this regex does not read.
 * That channel is not left silent: {@link NotFoundExceptionMapper} dresses every thrown 404 (and the
 * framework's own) in the same frozen {@code {"error":…}} body. So the two guards divide the work —
 * this one keeps return sites from shipping a bodyless 404, the mapper does the same for throws — and
 * a reader is not left to assume this regex covers a throw it never sees.
 *
 * <p>{@link #SILENT_404_ALLOWED} are the doors where a bare 404 is DELIBERATE silence, not an
 * oversight: {@code DataRowResource} returns the same empty 404 for "no citation store", "unknown
 * row hash" and "not this tenant's row" — that uniformity is a privacy feature (a message that
 * distinguished them would be an existence oracle), and {@code SecretResource} keeps its refusals
 * bare for the same caution around a secret's existence. The list fails BOTH ways: a file on it
 * that no longer has a bare 404 is flagged too, so an excuse cannot outlive the code it covered.
 */
class NotFoundBodyInventoryTest {

  /**
   * A {@code .status(<404>)…​.build()} chain with NO {@code .entity(…)} between the two — the bodyless
   * 404 in every shape it can take: {@code 404}, {@code Response.Status.NOT_FOUND},
   * {@code Status.NOT_FOUND}, or a bare {@code NOT_FOUND} (static import); with any builder call in
   * between ({@code .type(…)} and the like) but not another statement. The tempered middle stops at
   * {@code .entity(} (so a 404 that carries a body is NOT matched), at the next {@code .status(} and
   * at a statement/scope boundary ({@code ; &#123; &#125;}), so a match cannot cross into another chain.
   * {@link Pattern#DOTALL} because a builder chain wraps across lines.
   */
  private static final Pattern BARE_404 = Pattern.compile(
      "\\.status\\(\\s*(?:404|(?:Response\\.)?Status\\.NOT_FOUND|NOT_FOUND)\\s*\\)"
          + "(?:(?!\\.entity\\()(?!\\.status\\()(?![;{}]).)*?"
          + "\\.build\\(\\)",
      Pattern.DOTALL);

  /** File name → why a bare 404 there is deliberate silence rather than a missing body. */
  private static final Map<String, String> SILENT_404_ALLOWED = Map.of(
      "DataRowResource.java",
          "one empty 404 for 'no store' / 'unknown hash' / 'not this tenant's row' — the "
              + "uniformity is a privacy feature; a distinguishing message would be an existence oracle",
      "SecretResource.java",
          "a secret's refusals stay bare — caution around whether a secret name exists");

  @Test
  void the_scanner_recognises_every_bodyless_404_shape_and_ignores_one_that_carries_a_body() {
    // Self-test: the guard is only worth its green if it can go red. Every bodyless shape matches —
    assertThat(BARE_404.matcher("return Response.status(404).build();").find()).isTrue();
    assertThat(BARE_404.matcher("return Response.status(Response.Status.NOT_FOUND).build();").find()).isTrue();
    assertThat(BARE_404.matcher("return Response.status(Status.NOT_FOUND).build();").find()).isTrue();
    assertThat(BARE_404.matcher("return Response.status(NOT_FOUND).build();").find()).isTrue();   // static import
    assertThat(BARE_404.matcher("Response.status(404).type(JSON).build()").find()).isTrue();      // bodyless with a type
    assertThat(BARE_404.matcher("Response.status(404)\n    .type(JSON)\n    .build()").find()).isTrue(); // wrapped
    // — and a 404 that carries a body does NOT, even with a type or across lines, and a match must
    // not span two statements (a later .build() on another chain is not this status's body).
    assertThat(BARE_404.matcher("Response.status(404).entity(ApiErrors.error(\"x\")).build()").find()).isFalse();
    assertThat(BARE_404.matcher("Response.status(404).type(JSON).entity(error(w)).build()").find()).isFalse();
    assertThat(BARE_404.matcher("Response.status(404)\n    .entity(error(w))\n    .build()").find()).isFalse();
    assertThat(BARE_404.matcher("Response.status(404).entity(x); other.build();").find()).isFalse();
  }

  @Test
  void no_hub_door_returns_a_bare_404_outside_the_allowlist() throws IOException {
    SourceTree hub = SourceTree.of("lumnik-hub", "io/lumnik/hub");
    Map<String, Integer> offenders = new TreeMap<>();
    Set<String> allowedStillBare = new TreeSet<>();

    for (Path file : hub.javaFiles()) {
      String name = file.getFileName().toString();
      if (name.equals("ApiErrors.java")) continue;  // it DEFINES notFound; its 404 always carries a body
      String code = SourceTree.code(Files.readString(file));
      int hits = 0;
      Matcher m = BARE_404.matcher(code);
      while (m.find()) hits++;
      if (hits == 0) continue;
      if (SILENT_404_ALLOWED.containsKey(name)) allowedStillBare.add(name);
      else offenders.put(hub.root().relativize(file).toString(), hits);
    }

    assertThat(offenders)
        .as("these hub doors send a bodyless 404 — use ApiErrors.notFound(what), or add the "
            + "file to SILENT_404_ALLOWED with the reason its silence is deliberate")
        .isEmpty();

    // Reverse: an allowlist entry must still describe a real bare 404, or the excuse has outlived
    // its code (e.g. the door was later given a body) and the entry should be removed.
    assertThat(allowedStillBare)
        .as("SILENT_404_ALLOWED names a file that no longer has a bare 404 — drop the stale entry")
        .containsExactlyInAnyOrderElementsOf(SILENT_404_ALLOWED.keySet());
  }
}
