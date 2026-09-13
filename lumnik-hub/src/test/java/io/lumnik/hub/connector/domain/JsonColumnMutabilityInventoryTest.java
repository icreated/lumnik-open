package io.lumnik.hub.connector.domain;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guardian for the next {@code JsonNode} column.
 *
 * <p>A JSON-mapped {@code JsonNode} never compares equal to its Hibernate snapshot, so every
 * transaction that merely LOADS the entity flushes it back — a full-row UPDATE carrying the
 * reader's stale copy, a lost update under concurrency ({@code TransactionalReadIsNotAWriteIT}
 * proves it on the run ledger). The rule, and why it is {@code @Mutability} rather than
 * {@code @Immutable}, lives on {@code ImmutableJsonNodePlan}. This test reads PRODUCTION SOURCE
 * so the column added next week carries it too, and fails in both directions: an unannotated
 * column fails it, and so does a scanner that no longer finds the column the defect was found on.
 *
 * <p>What it proves is the DECLARATION. #529 measured that on a {@code @JdbcTypeCode(SqlTypes.JSON)}
 * column the declaration is not what decides — {@code JsonNodeTypeContributor} is, and
 * {@code TransactionalReadIsNotAWriteIT} asserts that effect. The annotation still decides on its
 * own for a column mapped through an {@code AttributeConverter}, which is why this sweep covers
 * all of them.
 */
class JsonColumnMutabilityInventoryTest {

  /** Module directory → the package its entities live in. Ordered, so a failure reads the same twice. */
  private static final List<Map.Entry<String, String>> MODULES = List.of(
      Map.entry("lumnik-hub", "io/lumnik/hub"),
      Map.entry("lumnik-identity", "io/lumnik/identity"),
      Map.entry("lumnik-rag", "io/lumnik/rag"));

  /**
   * The closed modules, which the open edition prunes ({@code scripts/extract-open-edition.sh}).
   * This sweep runs there too — on the tree the publish ritual proves — and an absent closed module
   * contributes nothing: the entities it could declare are pruned with it. An absent OPEN module is
   * still the guard reading nothing, and fails. The same rule, in the same words, as
   * {@code AdminDatasourceTenantGuardTest}'s migration sweep.
   */
  private static final Set<String> CLOSED_MODULES = Set.of("lumnik-rag");

  /**
   * The trees to sweep. Resolved here rather than in a static initializer: an absent module is a
   * verdict this test must be able to state, and a static field turns it into an
   * {@code ExceptionInInitializerError} that names no rule.
   */
  private static List<SourceTree> trees() {
    List<SourceTree> out = new ArrayList<>();
    for (Map.Entry<String, String> module : MODULES) {
      Optional<SourceTree> tree = SourceTree.find(module.getKey(), module.getValue());
      if (tree.isPresent()) {
        out.add(tree.get());
        continue;
      }
      assertThat(CLOSED_MODULES)
          .as("%s has no sources here — only a closed module may be absent from the tree", module.getKey())
          .contains(module.getKey());
    }
    return out;
  }

  private static final Pattern ANNOTATION = Pattern.compile("^\\s*@\\w+");
  private static final Pattern JSON_NODE_FIELD =
      Pattern.compile("^\\s*private\\s+(?:JsonNode|ObjectNode|ArrayNode)\\s+(\\w+)\\s*(?:=.*)?;");

  @Test
  void every_json_node_column_is_declared_immutable() throws IOException {
    List<String> found = new ArrayList<>();
    List<String> unannotated = new ArrayList<>();
    for (SourceTree module : trees()) {
      for (Path p : module.javaFiles()) {
        String src = SourceTree.code(Files.readString(p));
        if (!src.contains("@Entity")) continue;
        String entity = p.getFileName().toString().replace(".java", "");
        List<String> block = new ArrayList<>();   // the annotations right above the next member
        for (String line : src.split("\n", -1)) {
          if (line.isBlank()) continue;
          if (ANNOTATION.matcher(line).find()) { block.add(line); continue; }
          Matcher m = JSON_NODE_FIELD.matcher(line);
          if (m.find()) {
            String field = entity + "." + m.group(1);
            found.add(field);
            if (block.stream().noneMatch(a -> a.contains("@Mutability(ImmutableJsonNodePlan.class)"))) unannotated.add(field);
          }
          block.clear();
        }
      }
    }
    assertThat(found)
        .as("the scanner must still find the column the defect was found on")
        .contains("ConnectorRun.triggeredMeta");
    assertThat(unannotated)
        .as("JsonNode columns without @Mutability(ImmutableJsonNodePlan.class) — each one turns every transactional read of "
            + "its entity into a full-row UPDATE with the reader's stale copy")
        .isEmpty();
  }
}
