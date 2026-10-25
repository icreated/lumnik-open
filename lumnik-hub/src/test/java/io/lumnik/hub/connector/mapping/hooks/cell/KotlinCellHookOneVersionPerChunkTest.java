package io.lumnik.hub.connector.mapping.hooks.cell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.hook.KotlinScriptHosts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1402, from the review of PR #1404: the host is shared, so another run's check() can replace a hooks: script
 * between two rows of a chunk already started. A chunk runs ONE version — the one its first row took.
 * The interleaving is written out, so no thread or latch is needed to make it happen.
 */
class KotlinCellHookOneVersionPerChunkTest {

  static final String SCRIPT = """
      @Suppress("UNCHECKED_CAST")
      val cells = bindings["cells"] as MutableMap<String, Any?>
      cells["code"] = "VERSION"
      """;

  @TempDir Path dir;

  @Test
  void a_check_by_another_run_does_not_change_the_script_of_a_chunk_already_started() throws Exception {
    Path file = dir.resolve("version.kts");
    Files.writeString(file, SCRIPT.replace("VERSION", "v1"));
    KotlinCellHook hook = new KotlinCellHook();
    hook.scriptHost = KotlinScriptHosts.over(dir);
    JsonNode params = new ObjectMapper().createObjectNode().put("script", "hooks:version.kts");

    MappingState chunkA = new MappingState();
    hook.validateParams(params);                        // run A, before its chunk
    assertThat(row(hook, params, chunkA)).isEqualTo("v1");

    Files.writeString(file, SCRIPT.replace("VERSION", "v2"));
    hook.validateParams(params);                        // run B, before its own chunk

    assertThat(row(hook, params, chunkA)).as("chunk A keeps the version its first row took").isEqualTo("v1");
    assertThat(row(hook, params, new MappingState())).as("run B's chunk").isEqualTo("v2");
  }

  /**
   * #1430: the operator replaced the script on 1 January and nothing said which version produced which row. A chunk
   * records the name and SHA-256 of the version it ran — the fingerprint `lm hook scripts` shows.
   */
  @Test
  void a_chunk_records_the_version_of_the_script_it_ran() throws Exception {
    Path file = dir.resolve("version.kts");
    String v1 = SCRIPT.replace("VERSION", "v1");
    String v2 = SCRIPT.replace("VERSION", "v2");
    Files.writeString(file, v1);
    KotlinCellHook hook = new KotlinCellHook();
    hook.scriptHost = KotlinScriptHosts.over(dir);
    JsonNode params = new ObjectMapper().createObjectNode().put("script", "hooks:version.kts");

    MappingState december = new MappingState();
    hook.validateParams(params);
    row(hook, params, december);
    row(hook, params, december);
    Files.writeString(file, v2);
    hook.validateParams(params);
    MappingState january = new MappingState();
    row(hook, params, january);

    assertThat(december.scripts()).containsExactly(new ScriptVersion("hooks:version.kts", AuditRecorder.sha256(v1)));
    assertThat(january.scripts()).containsExactly(new ScriptVersion("hooks:version.kts", AuditRecorder.sha256(v2)));
  }

  private static Object row(KotlinCellHook hook, JsonNode params, MappingState chunk) {
    Map<String, Object> row = new HashMap<>(Map.of("code", "-"));
    hook.apply(row, params, chunk);
    return row.get("code");
  }
}
