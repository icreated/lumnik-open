package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #846: canonicalColumns() means "everything the view exposes", joined columns included. A new
 * caller must be looked at on purpose — this list is that look. The compiler must NOT be on it: it
 * asks fusedColumns().
 */
class CanonicalColumnsConsumersInventoryTest {

  /** The consumers reviewed in #846 that see joined columns on purpose. */
  private static final Set<String> REVIEWED = Set.of(
      "ask/EntitySchemaBuilder.java", "entity/EntityManifest.java", "entity/EntityManifestParser.java",
      "entity/EntityReadResource.java", "export/EntitySnapshotDownloadResource.java",
      "export/EntitySnapshotStreamer.java", "export/ExportDefinitionCheck.java",
      "export/ExportManifestParser.java", "search/SearchIndexDefinitionCheck.java");

  private static final Path ROOT =
      Path.of(System.getProperty("basedir", ".")).toAbsolutePath().resolve("src/main/java/io/lumnik/hub");

  @Test
  void every_caller_of_canonicalColumns_has_been_reviewed_for_joined_columns() throws Exception {
    Set<String> found = new TreeSet<>();
    try (Stream<Path> walk = Files.walk(ROOT)) {
      for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator)
        if (Files.readString(p).contains("canonicalColumns()")) found.add(ROOT.relativize(p).toString());
    }
    assertThat(found).isSubsetOf(REVIEWED);
  }

  @Test
  void the_compiler_asks_for_the_fused_columns_only() throws Exception {
    assertThat(Files.readString(ROOT.resolve("entity/EntityViewCompiler.java")))
        .doesNotContain("canonicalColumns()");
  }
}
