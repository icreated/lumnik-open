package io.lumnik.hub.hook;

import io.lumnik.hub.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.script.SimpleBindings;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1402: the operator puts {@code .kts} files in one directory, a manifest names one as
 * {@code hooks:<name>.kts}. A bare name only — the manifest never carries a path.
 */
class KotlinScriptHostHooksDirTest {

  static final String UPPERCASE = """
      @Suppress("UNCHECKED_CAST")
      val cells = bindings["cells"] as MutableMap<String, Any?>
      cells["code"] = (cells["code"] as? String)?.uppercase()
      """;

  @TempDir Path dir;

  private KotlinScriptHost host() {
    KotlinScriptHost host = new KotlinScriptHost();
    host.hooksDir = Optional.of(dir.toString());
    return host;
  }

  private static Map<String, Object> row(String code) {
    return new HashMap<>(Map.of("code", code));
  }

  private static void run(KotlinScriptHost host, String reference, Map<String, Object> row) throws Exception {
    host.compiled(reference).evaluate(new SimpleBindings(new HashMap<>(Map.of("cells", row, "context", Map.of()))));
  }

  @Test
  void a_script_of_the_hooks_directory_runs_by_its_name() throws Exception {
    Files.writeString(dir.resolve("upper-code.kts"), UPPERCASE);
    KotlinScriptHost host = host();
    host.check("hooks:upper-code.kts");
    Map<String, Object> row = row("ab-1");
    run(host, "hooks:upper-code.kts", row);
    assertThat(row).containsEntry("code", "AB-1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"hooks:../upper-code.kts", "hooks:sub/upper-code.kts", "hooks:/etc/upper-code.kts",
      "hooks:Upper-Code.kts", "hooks:upper-code.kt", "hooks:.upper-code.kts", "hooks:", "hooks: upper-code.kts"})
  void a_name_that_is_not_a_bare_kts_file_name_is_refused(String reference) throws Exception {
    Files.writeString(dir.resolve("upper-code.kts"), UPPERCASE);
    assertThatThrownBy(() -> host().check(reference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a file name of the hooks directory");
  }

  @Test
  void without_a_hooks_directory_the_scheme_says_the_operator_has_not_set_one() {
    KotlinScriptHost host = new KotlinScriptHost();
    assertThatThrownBy(() -> host.check("hooks:upper-code.kts"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no hooks directory")
        .hasMessageContaining("LUMNIK_HOOKS_DIR");
  }

  @Test
  void an_absent_script_is_refused_by_its_name_without_the_directory_path() {
    assertThatThrownBy(() -> host().check("hooks:ean-check.kts"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hooks:ean-check.kts")
        .hasMessageNotContaining(dir.toString());
  }

  @Test
  void a_script_that_does_not_compile_is_refused_with_its_line_and_column() throws Exception {
    Files.writeString(dir.resolve("broken.kts"), "val a = 1\nval b: Int = \"text\"\n");
    assertThatThrownBy(() -> host().check("hooks:broken.kts"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("broken.kts")
        .hasMessageContaining("2:14");
  }

  @Test
  void an_edited_script_is_read_again_at_the_next_check() throws Exception {
    Path file = dir.resolve("upper-code.kts");
    Files.writeString(file, UPPERCASE);
    KotlinScriptHost host = host();
    host.check("hooks:upper-code.kts");
    Files.writeString(file, UPPERCASE.replace("uppercase()", "lowercase()"));

    Map<String, Object> before = row("Ab");
    run(host, "hooks:upper-code.kts", before);
    assertThat(before).as("a row never reads the disk").containsEntry("code", "AB");

    host.check("hooks:upper-code.kts");
    Map<String, Object> after = row("Ab");
    run(host, "hooks:upper-code.kts", after);
    assertThat(after).containsEntry("code", "ab");
  }

  /**
   * Security review of #1402: compiled at `lm validate`, any classpath resource became a fast oracle —
   * the compiler's error quotes what it read. Only a .kts script is ever read.
   */
  @ParameterizedTest
  @ValueSource(strings = {"classpath:application.properties", "classpath:META-INF/MANIFEST.MF",
      "classpath:hooks/../application.properties", "classpath:../x.kts", "classpath:/etc/x.kts"})
  void a_classpath_resource_that_is_not_a_kts_script_is_refused_unread(String reference) {
    assertThatThrownBy(() -> host().check(reference))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("names a .kts script built into the hub");
  }

  @Test
  void another_scheme_is_refused_with_the_two_it_knows() {
    assertThatThrownBy(() -> host().check("file:/hooks/upper-code.kts"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("classpath:")
        .hasMessageContaining("hooks:");
  }

  @Test
  void the_directory_lists_the_scripts_a_manifest_can_name_with_their_fingerprint() throws Exception {
    Files.writeString(dir.resolve("upper-code.kts"), UPPERCASE);
    Files.writeString(dir.resolve("ean-check.kts"), "val x = 1\n");
    Files.writeString(dir.resolve("README.md"), "not a script");
    Files.writeString(dir.resolve("Upper.kts"), "a name no manifest can write");
    Files.createDirectory(dir.resolve("nested.kts"));

    assertThat(host().hooksScripts()).hasValueSatisfying(scripts -> assertThat(scripts).containsExactly(
        new KotlinScriptHost.HookScript("ean-check.kts", 10, AuditRecorder.sha256("val x = 1\n")),
        new KotlinScriptHost.HookScript("upper-code.kts", UPPERCASE.length(), AuditRecorder.sha256(UPPERCASE))));
  }

  @Test
  void without_a_hooks_directory_there_is_no_list() {
    assertThat(new KotlinScriptHost().hooksScripts()).isEmpty();
  }
}
