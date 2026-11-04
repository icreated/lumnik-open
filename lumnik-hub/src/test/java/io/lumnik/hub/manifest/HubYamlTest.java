package io.lumnik.hub.manifest;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.lumnik.testkit.sources.SourceTree;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  record Named(String name) {}

  @Test
  void the_default_mapper_refuses_a_key_the_target_does_not_declare() {
    assertThatThrownBy(() -> HubYaml.mapper().readValue("name: a\nextra: 1\n", Named.class))
        .isInstanceOf(UnrecognizedPropertyException.class);
  }

  @Test
  void the_lenient_mapper_drops_it_and_still_reads_yaml_1_2_booleans() throws IOException {
    assertThat(HubYaml.lenientMapper().readValue("name: a\nextra: 1\n", Named.class)).isEqualTo(new Named("a"));
    assertThat(HubYaml.lenientMapper().readTree("column: no\nflag: true\n").toString())
        .isEqualTo("{\"column\":\"no\",\"flag\":true}");
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
