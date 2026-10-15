package io.lumnik.hub.connector.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each {@code ?} receives the key value its dialect's bind plan names — including repeated ones. */
class JdbcChunkReaderBindPlanTest {

  /** A PreparedStatement that records "index=value (method)" for every setter the reader calls. */
  private static PreparedStatement recorder(List<String> calls) {
    return (PreparedStatement) Proxy.newProxyInstance(
        JdbcChunkReaderBindPlanTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
        (proxy, method, args) -> {
          if (method.getName().startsWith("set")) {
            calls.add(args[0] + "=" + args[1] + " (" + method.getName() + ")");
          }
          return null;
        });
  }

  @Test
  void a_repeating_plan_binds_the_same_key_value_twice() throws Exception {
    List<String> calls = new ArrayList<>();
    int next = JdbcChunkReader.bind(recorder(calls), List.of(0, 0, 1), List.of("A", "B"), 1);
    assertEquals(List.of("1=A (setObject)", "2=A (setObject)", "3=B (setObject)"), calls);
    assertEquals(4, next);
  }

  @Test
  void a_plan_starts_at_the_index_the_previous_clause_left() throws Exception {
    List<String> calls = new ArrayList<>();
    int next = JdbcChunkReader.bind(recorder(calls), List.of(0, 1), List.of("A", "B"), 3);
    assertEquals(List.of("3=A (setObject)", "4=B (setObject)"), calls);
    assertEquals(5, next);
  }

  @Test
  void an_empty_plan_binds_nothing_and_leaves_the_index_alone() throws Exception {
    List<String> calls = new ArrayList<>();
    assertEquals(7, JdbcChunkReader.bind(recorder(calls), List.of(), List.of("A"), 7));
    assertEquals(List.of(), calls);
  }

  @Test
  void a_timestamp_goes_through_setTimestamp_so_postgresql_can_compare_it_without_a_cast() throws Exception {
    List<String> calls = new ArrayList<>();
    Timestamp ts = Timestamp.valueOf("2026-01-01 10:00:00");
    JdbcChunkReader.bind(recorder(calls), List.of(0, 1), List.of(ts, 5L), 1);
    assertEquals(List.of("1=" + ts + " (setTimestamp)", "2=5 (setObject)"), calls);
  }
}
