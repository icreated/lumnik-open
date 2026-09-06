package io.lumnik.hub.hook;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the 2026-09-06 OOM: a cell hook evaluates its script once per ROW, so evaluating a
 * compiled script must not generate a class per row. The class-loading counter is the
 * deterministic witness of that mechanism — heap numbers are not. Runs the shipped sample, so
 * it also proves that sample compiles and works on the host.
 */
class KotlinScriptHostPerRowCostTest {

  static final String SAMPLE = "hooks/samples/normalize-date.kts";
  static final int ROWS = 300;

  @ParameterizedTest(name = "hot-reload={0}")
  @ValueSource(booleans = {false, true})
  void evaluating_a_compiled_script_per_row_does_not_load_a_class_per_row(boolean hotReload) throws Exception {
    KotlinScriptHost host = new KotlinScriptHost();
    host.hotReload = hotReload;   // hot-reload re-reads the file per call; it must still not recompile per row
    evaluate(host, 1);                                   // compile + first load, outside the window
    long before = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount();
    evaluate(host, ROWS);
    long loaded = ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount() - before;
    assertThat(loaded)
        .as("classes loaded while evaluating one compiled script %d times (JSR-223 REPL: one per row)", ROWS)
        .isLessThan(ROWS / 10);
  }

  private static void evaluate(KotlinScriptHost host, int rows) throws Exception {
    for (int i = 0; i < rows; i++) {
      Map<String, Object> row = new HashMap<>(Map.of("signup_date", "31/12/2019"));
      host.execute(SAMPLE, new HashMap<>(Map.of("cells", row, "context", Map.of())));   // wrapped, not copied
      assertThat(row).containsEntry("signup_date", "2019-12-31");
    }
  }
}
