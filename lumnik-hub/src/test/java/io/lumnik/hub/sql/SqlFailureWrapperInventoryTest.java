package io.lumnik.hub.sql;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code catch (SQLException e)} that re-throws with {@code e.getMessage()} (or {@code + e}) in its
 * message pastes the driver's words into a message that travels — a run's served
 * {@code error_message}, a 500 body — and class 22/23 words quote the row (#1648). The rule the
 * helpers apply is {@code Jdbc.failureText}: the label and the SQLSTATE, the cause attached for the
 * log. This guard reads PRODUCTION SOURCE of the hub and of rag (comments and imports stripped).
 *
 * <p>It is hygiene, not the guarantee: it reads {@code catch (SQLException …)} only, and a broader
 * {@code catch (Exception e)} around JDBC is invisible to it. The guarantee is at the sink —
 * {@link SqlFailureText#ownMessage} cuts the driver's words out of whatever wrapper reaches a served
 * message, and {@code ConnectorEngine.finalizeRun} applies it where a run's message is written. This
 * keeps the words right where they are written; it does not see a paste behind a nested block, inside
 * a helper the catch calls, or in anything but a {@code throw new}.
 *
 * <p>{@link #ALLOWED} counts the catches that read the driver's words ON PURPOSE, per file, with the
 * reason beside it. Exact counts, both ways: a new paste in an allowed file is flagged, and so is an
 * entry whose catch is gone.
 */
class SqlFailureWrapperInventoryTest {

  /** A catch clause binding SQLException (alone or in a multi-catch) whose body, before its first brace,
   *  throws with that variable's message ({@code e.getMessage()}) or the variable itself ({@code + e}). */
  private static final Pattern PASTED = Pattern.compile(
      "catch\\s*\\(\\s*(?:final\\s+)?(?:[\\w.]+\\s*\\|\\s*)*(?:java\\.sql\\.)?SQLException(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)"
          + "\\s*\\{[^}]*?throw\\s+new\\s+[\\w.]+\\([^;]*(?:\\b\\1\\.getMessage\\(\\)|\\+\\s*\\1\\s*[,)])");

  /** File name → how many of its catches carry the driver's words on purpose. */
  private static final Map<String, Integer> ALLOWED = Map.of(
      // probe(): 42P01/42703 become MissingSourceTable/Column refusals naming the missing table or
      // column — class 42 is structural: names, never row values.
      "EntityViewCompiler.java", 1);

  @Test
  void the_scanner_sees_the_pasted_message_in_every_shape_and_not_the_safe_one() {
    assertThat(pastes("catch (SQLException e) { throw new RuntimeException(\"X: \" + e.getMessage(), e); }")).isTrue();
    assertThat(pastes("catch (SQLException ex) {\n  throw new IllegalStateException(\"X: \" + ex, ex);\n}")).isTrue();
    assertThat(pastes("catch (SQLException | RuntimeException e) { throw new ConnectorException(\"X: \" + e.getMessage(), e); }")).isTrue();
    assertThat(pastes("catch (java.sql.SQLException e) { throw new RuntimeException(e.getMessage(), e); }")).isTrue();
    assertThat(pastes("catch (final SQLException e) { throw new RuntimeException(\"X: \" + e.getMessage(), e); }")).isTrue();
    assertThat(pastes("catch (SQLException e) { throw new ConnectorException(Jdbc.failureText(\"X\", e), e); }")).isFalse();
    assertThat(pastes("catch (SQLException e) { LOG.warn(e.getMessage()); return null; }")).isFalse();
    assertThat(pastes("catch (RuntimeException e) { throw new ConnectorException(\"X: \" + e.getMessage(), e); }")).isFalse();
  }

  @Test
  void no_catch_pastes_the_drivers_words_into_what_it_throws() throws IOException {
    Map<String, Integer> offenders = new TreeMap<>();
    Map<String, Integer> allowedFound = new TreeMap<>();
    for (SourceTree tree : trees()) {
      for (Path file : tree.javaFiles()) {
        String name = file.getFileName().toString();
        int hits = count(SourceTree.code(Files.readString(file)));
        if (hits == 0) continue;
        if (ALLOWED.containsKey(name)) allowedFound.put(name, hits);
        else offenders.put(tree.root().relativize(file).toString(), hits);
      }
    }
    assertThat(offenders)
        .as("these catches paste the driver's text into what they throw — use Jdbc.failureText(label, e), "
            + "or add the file to ALLOWED with the reason its words are structural")
        .isEmpty();
    assertThat(allowedFound)
        .as("ALLOWED's counts no longer match the code — a new paste in an allowed file, or a stale entry")
        .isEqualTo(new TreeMap<>(ALLOWED));
  }

  /** The hub always; rag when this edition carries it (the open extraction prunes it). */
  private static List<SourceTree> trees() {
    return Stream.concat(Stream.of(SourceTree.of("lumnik-hub", "io/lumnik/hub")),
        SourceTree.find("lumnik-rag", "io/lumnik/rag").stream()).toList();
  }

  private static boolean pastes(String code) {
    return count(code) > 0;
  }

  private static int count(String code) {
    return (int) PASTED.matcher(code).results().count();
  }
}
