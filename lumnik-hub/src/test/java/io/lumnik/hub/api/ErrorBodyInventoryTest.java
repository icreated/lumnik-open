package io.lumnik.hub.api;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hub's error body is {@code {"error": …}}, and {@link ApiErrors} is the one place that spells
 * it (#1646). Its dress — the body AND its JSON content type — is held by the signature:
 * {@link ApiErrors#error} is package-private, so outside {@code io.lumnik.hub.api} a body can only be
 * built through {@link ApiErrors#status} and its family, which always write the type.
 *
 * <p>The one thing no signature can stop is the key spelled by hand — {@code Map.of("error", …)} or a
 * private {@code ERROR = "error"} constant. A typo there is a body the PWA, {@code lm} and the OTK
 * oracle cannot read. This guard reads PRODUCTION SOURCE (comments and imports stripped) for it.
 */
class ErrorBodyInventoryTest {

  /**
   * {@code "error"} as a key: an argument followed by its value ({@code Map.of(…, "error", v)},
   * {@code put}, {@code Map.entry}), a key inside a JSON string written by hand, or a constant that
   * stands in for it.
   */
  private static final Pattern ERROR_KEY_LITERAL = Pattern.compile(
      "[(,]\\s*\"error\"\\s*,|\\\\\"error\\\\\"\\s*:|static\\s+final\\s+String\\s+\\w+\\s*=\\s*\"error\"\\s*;");

  @Test
  void the_scanner_recognises_the_key_spelled_by_hand_and_nothing_else() {
    // Self-test: a guard is only worth its green if it can go red.
    assertThat(ERROR_KEY_LITERAL.matcher("Map.of(\"error\", msg)").find()).isTrue();
    assertThat(ERROR_KEY_LITERAL.matcher("body.put( \"error\" , msg)").find()).isTrue();
    assertThat(ERROR_KEY_LITERAL.matcher("private static final String ERROR = \"error\";").find()).isTrue();
    assertThat(ERROR_KEY_LITERAL.matcher("Map.of(\"ok\", false, \"error\", msg)").find()).isTrue();   // not first
    assertThat(ERROR_KEY_LITERAL.matcher("Map.<String, Object>of(\"error\", msg)").find()).isTrue();
    assertThat(ERROR_KEY_LITERAL.matcher("Map.entry(\"error\", msg)").find()).isTrue();
    assertThat(ERROR_KEY_LITERAL.matcher("String body = \"{\\\"error\\\": \\\"x\\\"}\";").find()).isTrue();  // JSON by hand
    assertThat(ERROR_KEY_LITERAL.matcher("String result = \"error\";").find()).isFalse();   // a local, not the key
    assertThat(ERROR_KEY_LITERAL.matcher("onMissing.equals(\"error\")").find()).isFalse();
    assertThat(ERROR_KEY_LITERAL.matcher("ApiErrors.status(400, msg)").find()).isFalse();
    assertThat(ERROR_KEY_LITERAL.matcher("case \"error\" -> OnConflict.ERROR;").find()).isFalse();
    assertThat(ERROR_KEY_LITERAL.matcher("metrics.recordOpen(kind, label, \"error\");").find()).isFalse();
  }

  @Test
  void the_error_key_is_spelled_only_by_ApiErrors() throws IOException {
    SourceTree hub = SourceTree.of("lumnik-hub", "io/lumnik/hub");
    Set<String> offenders = new TreeSet<>();
    for (Path file : hub.javaFiles()) {
      if (file.getFileName().toString().equals("ApiErrors.java")) continue;   // it defines the key
      if (ERROR_KEY_LITERAL.matcher(SourceTree.code(Files.readString(file))).find()) {
        offenders.add(hub.root().relativize(file).toString());
      }
    }
    assertThat(offenders)
        .as("these hub sources spell the error key by hand — build the body with ApiErrors.status(…) "
            + "or one of its helpers")
        .isEmpty();
  }
}
