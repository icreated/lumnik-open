package io.lumnik.hub.manifest;

import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1489: the hub reads YAML booleans as YAML 1.2 does — only true/false — and reads YAML in one place.
 */
class HubYamlTest {

  @Test
  void only_true_and_false_are_booleans() throws IOException {
    String yaml = "column: no\nstate: OFF\nin: [yes, NO, On, off, Y, n, true, True, TRUE, false, False, FALSE, tRue]\n";
    assertThat(HubYaml.mapper().readTree(yaml).toString()).isEqualTo(
        "{\"column\":\"no\",\"state\":\"OFF\",\"in\":[\"yes\",\"NO\",\"On\",\"off\",\"Y\",\"n\","
            + "true,true,true,false,false,false,\"tRue\"]}");
  }

  private static final Pattern BUILDS_A_READER = Pattern.compile(
      "new\\s+YAMLFactory\\s*\\(|YAMLFactory\\s*\\.\\s*builder\\s*\\(|new\\s+YAMLMapper\\s*\\("
          + "|YAMLMapper\\s*\\.\\s*builder\\s*\\(|new\\s+Yaml\\s*\\(");

  /**
   * The rule held in one place: no other hub source builds a YAML mapper, factory or SnakeYAML reader. One that did
   * would read yes/no/on/off as booleans again, at one door only.
   */
  @Test
  void no_other_hub_source_builds_a_yaml_reader() throws IOException {
    SourceTree tree = SourceTree.of("lumnik-hub", "io/lumnik/hub");
    List<String> offenders = new ArrayList<>();
    for (Path p : tree.javaFiles()) {
      if (p.getFileName().toString().equals("HubYaml.java")) continue;
      String code = SourceTree.code(Files.readString(p));   // comments may name a reader; only code builds one
      if (BUILDS_A_READER.matcher(code).find() || code.contains("org.yaml.snakeyaml")) {
        offenders.add(tree.root().relativize(p).toString());
      }
    }
    assertThat(offenders).as("build YAML mappers through HubYaml").isEmpty();
  }

  /** The guard is not blind: its pattern sees every way the hub ever built one. */
  @Test
  void the_guard_sees_each_way_to_build_a_reader() {
    for (String line : List.of("new ObjectMapper(new YAMLFactory())", "YAMLFactory.builder()", "new YAMLMapper()",
        "YAMLMapper.builder()", "new Yaml().load(body)")) {
      assertThat(BUILDS_A_READER.matcher(line).find()).as(line).isTrue();
    }
    assertThat(BUILDS_A_READER.matcher("HubYaml.builder()").find()).isFalse();
  }
}
