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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>The contract this now polices: {@code validateParams} refuses, or {@code apply} handles —
 * one of the two, never a crash.</b> {@code MappingEngine} calls {@code validateParams} once per
 * spec before any row, so each application below is driven the way the engine drives it —
 * {@code validateParams(params)} first, then {@code apply}/{@code flush} — and a param-shape
 * refusal is expected to come from {@code validateParams}, a bad VALUE handled by {@code apply}.
 * A hook that moves a param check to {@code validateParams} must not then let {@code apply} crash
 * on that same param: the two-phase drive here is what catches that.
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
        // #1338: parse-number's `integer` is the first boolean flag a hook refuses when it is not
        // a boolean; the shape is here so the next flag is swept for it too.
        Map.entry("a boolean flag written as a string",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"integer\":\"yes\"}"),
        Map.entry("an empty formats list",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"formats\":[]}"),
        Map.entry("a scalar where an object is expected",
            "{\"column\":\"c\",\"columns\":[\"c\"],\"groups\":\"nope\",\"table\":\"nope\"}"),
        // A NEGATIVE key in a numeric-keyed param map. regex-extract fed "-1" to
        // Matcher.group(-1), whose IndexOutOfBoundsException was not one of the two its catch
        // named — so it escaped the hook and ChunkExecutor lost the whole chunk. The list already
        // had a negative LENGTH; it had no negative KEY, and a key is a different shape. The next
        // hook to take a numeric-keyed map is covered without editing this file.
        Map.entry("a negative key in a numeric-keyed map",
            // The pattern matters: without one, regex-extract returns before it ever reads
            // `groups`, and this entry would sit in the list proving nothing. `.*` matches every
            // hostile value above, including the empty string, so the key is always reached.
            "{\"column\":\"c\",\"columns\":[\"c\"],\"pattern\":\".*\","
                + "\"groups\":{\"-1\":\"d\"},\"table\":{\"-1\":\"d\"}}"));
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
          // The engine's own order: validateParams gates apply. A param-shape refusal must come
          // from validateParams; a bad value must be handled by apply. Neither may crash.
          run(crashed, "cell", name, params, value,
              () -> { hook.validateParams(node); hook.apply(rowWith(value), node, new MappingState()); });
        }
        for (String name : rows) {
          RowHook hook = catalog.row(name);
          run(crashed, "row", name, params, value,
              () -> { hook.validateParams(node); hook.apply(rowWith(value), node, new MappingState()); });
          // flush() runs at the end of every chunk for buffering hooks — same blast radius, same gate.
          run(crashed, "row.flush", name, params, value,
              () -> { hook.validateParams(node); hook.flush(node, new MappingState()); });
        }
        for (String name : sets) {
          DatasetHook hook = catalog.dataset(name);
          run(crashed, "dataset", name, params, value,
              () -> { hook.validateParams(node); hook.apply(new ArrayList<>(List.of(rowWith(value))), node, new MappingState()); });
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

  /**
   * The second axis: <b>a cell hook transforms the column(s) it is pointed at — and nothing else.</b>
   * Changing its target's TYPE is fair game (mask/truncate/regex-replace stringify a non-text value
   * on purpose — that is the declared contract for a string transform); what it must never do is
   * reach over and mutate a column it was not configured to touch.
   *
   * <p>The params below reference {@code c} (and its declared outputs {@code d}/{@code g}) but NEVER
   * the {@code bystander} column, which carries a sentinel. After apply, the bystander must be
   * byte-for-byte what it was. Anchored on {@link HookCatalog}, and coverage is ENFORCED, not hoped:
   * every non-exempt cell hook must actually run at least once (a hook silently skipped because the
   * generic params do not fit it would hide from this axis). {@code kotlin} is the one exemption —
   * it needs a real classpath script to execute, which generic params cannot supply.
   *
   * <p>(Cell tier only: row hooks fan out and dataset hooks drop/reorder whole rows, a different
   * invariant than "left a bystander cell alone".)
   */
  @Test
  void a_cell_hook_touches_only_the_column_it_is_pointed_at() throws Exception {
    final String bystander = "bystander";
    final String sentinel = "do-not-touch-42";
    // Well-formed knobs across the common cell hooks — column c, its declared outputs d/g, and the
    // per-hook options each needs to actually run. None of these names the bystander column.
    JsonNode node = JSON.readTree("{\"column\":\"c\",\"columns\":[\"c\"],\"length\":8,\"to\":\"d\","
        + "\"pattern\":\"(.*)\",\"groups\":{\"1\":\"g\"},\"replacement\":\"x\",\"table\":{\"k\":\"v\"},"
        + "\"formats\":[\"yyyy-MM-dd\"],\"mask-char\":\"*\",\"on-invalid\":\"keep\"}");

    // kotlin runs an arbitrary classpath script; generic params cannot make it execute, so it
    // cannot be swept for a mutation invariant here. Named, not silently skipped.
    Set<String> exempt = Set.of("kotlin");
    List<String> cells = catalog.allCellHooks().stream().map(HookDescriptor::name).sorted().toList();
    assertThat(cells).as("the sweep must find the catalogue it polices").isNotEmpty();

    List<String> touched = new ArrayList<>();
    Set<String> exercised = new HashSet<>();
    for (String name : cells) {
      if (exempt.contains(name)) continue;
      CellHook hook = catalog.cell(name);
      // validateParams ONCE per hook, the way the engine calls it — params are invariant across
      // rows. If it refuses, this hook stays out of `exercised` and the coverage assertion flags it
      // (the params do not fit — enrich them or exempt it), rather than a per-value refusal being
      // swallowed silently.
      try {
        hook.validateParams(node);
      } catch (Throwable paramsDoNotFit) {
        continue;
      }
      for (Map.Entry<String, Object> value : hostileValues()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", value.getValue());
        row.put(bystander, sentinel);
        boolean returned = false;
        try {
          hook.apply(row, node, new MappingState());
          returned = true;
        } catch (Throwable ignored) {
          // this value did not fit; another may — coverage is asserted below
        }
        // Check the bystander REGARDLESS of whether apply returned or threw: a hook that mutates it
        // and THEN throws must still be caught. Exercised only counts a clean return.
        if (!sentinel.equals(row.get(bystander))) {
          touched.add("%s + %s -> bystander became: %s".formatted(name, value.getKey(), row.get(bystander)));
        }
        if (returned) exercised.add(name);
      }
    }

    List<String> shouldRun = cells.stream().filter(n -> !exempt.contains(n)).toList();
    assertThat(exercised)
        .as("every non-exempt cell hook must actually run at least once — a hook the generic params "
            + "cannot exercise hides from this axis (add its knob above, or exempt it by name)")
        .containsExactlyInAnyOrderElementsOf(shouldRun);
    assertThat(touched)
        .as("a cell hook pointed at 'c' must not mutate the 'bystander' column it was never given")
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
