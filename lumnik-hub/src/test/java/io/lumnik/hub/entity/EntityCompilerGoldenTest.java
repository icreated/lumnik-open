package io.lumnik.hub.entity;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility pin (#846): every Entity manifest that shipped before joins compiles to exactly the SQL
 * recorded from main before joins existed. A manifest the parser refuses is recorded as REFUSED, so
 * a refusal can neither vanish nor appear silently. Re-record only on purpose:
 * {@code mvn test -Dtest=EntityCompilerGoldenTest -Dgolden.write=true}.
 */
class EntityCompilerGoldenTest {

  private static final Path MODULE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
  private static final Path DOCS = MODULE.getParent().resolve("docs");
  private static final Path GOLDEN = MODULE.resolve("src/test/resources/entity-golden");
  private final EntityManifestParser parser = new EntityManifestParser();
  private final EntityViewCompiler compiler = new EntityViewCompiler();

  /**
   * The manifests that existed BEFORE joins — a closed list on purpose: this pin protects the
   * historical SQL, it does not turn every new docs example into a mandatory snapshot. The SQL of a
   * join is specified by EntityViewCompilerJoinTest and the ITs.
   */
  private static final List<String> INVENTORY = List.of(
      "demo/wow-customer/customer-entity.yaml",
      "apps/demo/entities/10-customer.yaml",
      "apps/demo/entities/20-crm-first.yaml",
      "apps/demo/entities/60-refused-no-match-column.yaml",
      "apps/demo/entities/61-refused-case-collision.yaml",
      "apps/demo/entities/62-refused-reserved.yaml",
      "apps/demo/entities/63-refused-unknown-key.yaml",
      "apps/demo/entities/64-refused-domain-whitespace.yaml",
      "apps/demo/entities/65-refused-missing-table.yaml");

  @TestFactory
  Stream<DynamicTest> every_docs_entity_compiles_to_the_recorded_sql() {
    List<Path> manifests = INVENTORY.stream().map(DOCS::resolve).toList();
    manifests.forEach(p -> assertThat(p).as("inventoried manifest exists").exists());
    return manifests.stream().map(p -> DynamicTest.dynamicTest(DOCS.relativize(p).toString(), () -> {
      String actual = compile(read(p));
      Path golden = GOLDEN.resolve(DOCS.relativize(p).toString().replace('/', '_') + ".sql");
      if (Boolean.getBoolean("golden.write")) {
        Files.createDirectories(GOLDEN);
        Files.writeString(golden, actual, StandardCharsets.UTF_8);
      }
      assertThat(golden).as("record with -Dgolden.write=true").exists();
      assertThat(actual).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }));
  }

  private String compile(String yaml) {
    try {
      return String.join("\n;;\n", compiler.buildStatements(parser.parse(yaml)));
    } catch (IllegalArgumentException refused) {
      return "REFUSED";
    }
  }

  private static String read(Path p) {
    try { return Files.readString(p, StandardCharsets.UTF_8); }
    catch (IOException e) { throw new IllegalStateException(e); }
  }
}
