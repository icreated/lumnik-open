package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A hook fails in the house's own typed way, or it does not fail.</b>
 *
 * <p>{@link MappingValidationException} is this codebase's signal for "the manifest asks for
 * something I cannot honour" — {@code MappingLoader} and {@code MappingEngine} raise it throughout.
 * Any OTHER exception is a crash, and a crash tells the integrator nothing:
 * {@code StringIndexOutOfBoundsException: Range [0, -1)} does not name the knob that is wrong.
 *
 * <p>Both kinds cost the same at runtime, which is what makes the distinction worth enforcing. A
 * hook that throws escapes {@code MappingEngine.applyCellHooks} into {@code ChunkExecutor}, where
 * every {@code Throwable} becomes a {@code ChunkResult.Failure}: the cursor does not advance,
 * nothing reaches the DLQ, and the retry replays the same throw against the same bytes. A bad
 * parameter is a permanent poison pill either way — but only the typed refusal names the poison.
 *
 * <p><b>This test does not make the pill go away.</b> Hook parameters reach the runtime unvalidated:
 * {@code MappingLoader.parseHooks} hands them through untouched and no connector validator inspects
 * them, so every value below is something an integrator can type today and discover only on a live
 * run. Validating them at apply time — the way {@code default_rate_limit} now is — is the change
 * that would make these fail EARLY. This one makes them fail LEGIBLY.
 *
 * <p>Anchored on {@link HookCatalog}, the registry production resolves through, so a hook added
 * tomorrow is covered without editing this file. That is the point: each hook's own test proves its
 * own happy path, and none of them could say which hooks survive a {@code java.sql.Timestamp} —
 * which is exactly what a {@code jdbc-generic} reader hands over.
 */
@QuarkusTest
class HookRefusalInventoryTest {

  @Inject HookCatalog catalog;

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Values an ERP actually produces. A cell hook is handed whatever the reader read: JDBC returns
   * boxed numbers and {@code java.sql.Timestamp}, CSV returns blanks, and any column may be null.
   */
  private static List<Map.Entry<String, Object>> hostileValues() {
    List<Map.Entry<String, Object>> out = new ArrayList<>();
    out.add(Map.entry("a plain string", (Object) "Bénédicte"));
    out.add(Map.entry("an empty string", (Object) ""));
    out.add(Map.entry("a blank string", (Object) "   "));
    out.add(Map.entry("a multi-line string", (Object) "12 rue de la Paix\nBP 40\nLyon"));
    out.add(Map.entry("a boxed integer, as JDBC returns", (Object) 42));
    out.add(Map.entry("a java.sql.Timestamp, as JDBC returns",
        (Object) Timestamp.valueOf("2026-05-23 14:30:00")));
    out.add(Map.entry("a boolean", (Object) Boolean.TRUE));
    return out;
  }

  /**
   * Parameter blocks an integrator can write. Nothing validates these, so each reaches the hook
   * exactly as typed.
   */
  private static List<Map.Entry<String, String>> hostileParams() {
    return List.of(
        Map.entry("the documented shape",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"length\":8,\"to\":\"d\"}"),
        Map.entry("no parameters at all", "{}"),
        Map.entry("a plural key written singular",
            "{\"column\":\"c\",\"columns\":\"c\",\"length\":8}"),
        Map.entry("a column that is not in the row",
            "{\"column\":\"absent\",\"columns\":[\"absent\"]}"),
        Map.entry("a blank column name", "{\"column\":\"\",\"columns\":[\"\"]}"),
        Map.entry("a null column name", "{\"column\":null,\"columns\":[null]}"),
        Map.entry("a negative length",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"length\":-1}"),
        Map.entry("an empty string where a char is expected",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"mask-char\":\"\",\"separator\":\"\"}"),
        Map.entry("an unparsable regex",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"pattern\":\"[\"}"),
        Map.entry("a valid regex with a dangling group reference",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"pattern\":\"e\",\"replacement\":\"$\"}"),
        Map.entry("an empty formats list",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"formats\":[]}"),
        Map.entry("a scalar where an object is expected",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"groups\":\"nope\",\"table\":\"nope\"}"));
  }

  @Test
  void everyHookRefusesInTheHousesTypedWayOrNotAtAll() throws Exception {
    List<String> cells = catalog.allCellHooks().stream().map(HookDescriptor::name).sorted().toList();
    List<String> rows = catalog.allRowHooks().stream().map(HookDescriptor::name).sorted().toList();
    List<String> sets = catalog.allDatasetHooks().stream().map(HookDescriptor::name).sorted().toList();

    assertThat(cells).as("the sweep must find the catalogue it is meant to police").isNotEmpty();
    assertThat(rows).isNotEmpty();
    assertThat(sets).isNotEmpty();

    List<String> crashed = new ArrayList<>();

    for (Map.Entry<String, String> params : hostileParams()) {
      JsonNode node = JSON.readTree(params.getValue());
      for (Map.Entry<String, Object> value : hostileValues()) {

        for (String name : cells) {
          CellHook hook = catalog.cell(name);
          run(crashed, "cell", name, params, value,
              () -> hook.apply(rowWith(value), node, new MappingState()));
        }
        for (String name : rows) {
          RowHook hook = catalog.row(name);
          run(crashed, "row", name, params, value,
              () -> hook.apply(rowWith(value), node, new MappingState()));
          // flush() runs at the end of every chunk for buffering hooks — same blast radius.
          run(crashed, "row.flush", name, params, value,
              () -> hook.flush(node, new MappingState()));
        }
        for (String name : sets) {
          DatasetHook hook = catalog.dataset(name);
          run(crashed, "dataset", name, params, value,
              () -> hook.apply(new ArrayList<>(List.of(rowWith(value))), node, new MappingState()));
        }
      }
    }

    assertThat(crashed)
        .as("""
            These hooks crashed instead of refusing. Both poison the chunk — ChunkExecutor turns \
            any Throwable into ChunkResult.Failure, so the cursor does not advance and nothing \
            reaches the DLQ — but a JDK exception leaves the integrator holding a stack trace \
            instead of the name of the knob they got wrong. Throw MappingValidationException \
            naming the hook and the parameter, or handle the value.""")
        .isEmpty();
  }

  private static Map<String, Object> rowWith(Map.Entry<String, Object> value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("c", value.getValue());
    return row;
  }

  /**
   * One application. A typed refusal is the contract and must name itself; ANY other throwable is
   * a crash
   * and gets recorded with the exact tier, hook, parameter block and value that produced it, so a
   * failure reads as a reproduction rather than a stack trace.
   */
  private static void run(List<String> crashed, String tier, String name,
                          Map.Entry<String, String> params, Map.Entry<String, Object> value,
                          Runnable application) {
    try {
      application.run();
    } catch (MappingValidationException refusal) {
      assertThat(refusal.getMessage())
          .as("%s hook '%s' refused '%s' without saying what is wrong", tier, name, params.getKey())
          .isNotBlank();
    } catch (Throwable crash) {
      // Throwable, not Exception: ChunkExecutor catches Throwable, so an Error poisons the chunk
      // exactly as an Exception does. A net narrower than the thing it claims to catch is the
      // defect this whole sweep exists to find, and it was in the sweep.
      crashed.add("%s/%s: %s + %s -> %s: %s".formatted(
          tier, name, params.getKey(), value.getKey(),
          crash.getClass().getSimpleName(), crash.getMessage()));
    }
  }
}
