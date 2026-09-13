package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A column can be typed by two different roads, and they must arrive at the same place.
 *
 * <p>{@code JdbcChunkReader} declares a hint from the source column's {@code java.sql.Types} code;
 * the inferrer reads the Java type of the sampled values. The hint wins only for a column that was
 * all-NULL in its batch — so a sparse column is created from the SOURCE's opinion and later rows
 * are bound from the VALUE's. {@code pgTypeForSqlType}'s javadoc states they stay consistent. That
 * sentence was the only thing holding the invariant: nothing compared the two roads.
 *
 * <p>They do agree today — this test found no defect, and says so. What it adds is that the
 * agreement is now enforced rather than asserted: a new {@code case} in the switch, or a new
 * {@link SchemaInferrer} candidate, breaks the build unless both roads still meet.
 *
 * <p>Anchored on the production source both ways: the SQL types come from reflection over
 * {@link Types}, and which ones are in scope comes from {@code pgTypeForSqlType}'s own answer, not
 * from a list kept here. Only a code the switch CLAIMS (answers with something other than TEXT)
 * needs a representative — TEXT is the documented fallback for everything else.
 */
class SchemaTypingInventoryTest {

  private final SchemaInferrer inferrer = new SchemaInferrer();

  /**
   * What pgJDBC's {@code getObject} actually hands back for each SQL type. This is the knowledge
   * the invariant rests on, so it is written down rather than assumed — and the first test below
   * fails if the switch ever claims a code this map does not answer for.
   */
  private static final Map<Integer, Object> AS_JDBC_RETURNS_IT = new LinkedHashMap<>();
  static {
    AS_JDBC_RETURNS_IT.put(Types.NUMERIC, new BigDecimal("1.5"));
    AS_JDBC_RETURNS_IT.put(Types.DECIMAL, new BigDecimal("1.5"));
    AS_JDBC_RETURNS_IT.put(Types.TINYINT, (byte) 1);
    AS_JDBC_RETURNS_IT.put(Types.SMALLINT, (short) 1);
    AS_JDBC_RETURNS_IT.put(Types.INTEGER, 1);
    AS_JDBC_RETURNS_IT.put(Types.BIGINT, 1L);
    AS_JDBC_RETURNS_IT.put(Types.REAL, 1.0f);
    AS_JDBC_RETURNS_IT.put(Types.FLOAT, 1.0d);
    AS_JDBC_RETURNS_IT.put(Types.DOUBLE, 1.0d);
    AS_JDBC_RETURNS_IT.put(Types.TIMESTAMP, Timestamp.valueOf("2026-05-23 14:30:00"));
    AS_JDBC_RETURNS_IT.put(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.parse("2026-05-23T14:30:00Z"));
    AS_JDBC_RETURNS_IT.put(Types.DATE, Date.valueOf("2026-05-23"));
    AS_JDBC_RETURNS_IT.put(Types.BOOLEAN, Boolean.TRUE);
    AS_JDBC_RETURNS_IT.put(Types.BIT, Boolean.TRUE);
  }

  @Test
  void theSourcesOpinionAndTheValuesAgreeForEverySqlTypeTheSwitchClaims() throws Exception {
    List<String> unrepresented = new ArrayList<>();
    List<String> disagreements = new ArrayList<>();
    int claimed = 0;

    for (Field f : Types.class.getFields()) {
      if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
      int code = f.getInt(null);
      String fromSource = SchemaInferrer.pgTypeForSqlType(code);
      if ("TEXT".equals(fromSource)) continue;   // the documented fallback, nothing to reconcile
      claimed++;

      Object value = AS_JDBC_RETURNS_IT.get(code);
      if (value == null) {
        unrepresented.add("%s -> %s".formatted(f.getName(), fromSource));
        continue;
      }
      String fromValue = infer(value);
      if (!fromSource.equals(fromValue)) {
        disagreements.add("%s: source says %s, a %s value says %s"
            .formatted(f.getName(), fromSource, value.getClass().getSimpleName(), fromValue));
      }
    }

    assertThat(claimed).as("the sweep must find the switch it is meant to police").isPositive();
    assertThat(unrepresented)
        .as("""
            pgTypeForSqlType now claims a SQL type this test cannot check, because nothing says \
            which Java value JDBC returns for it. Add it to AS_JDBC_RETURNS_IT — an unchecked \
            claim is how the two roads drift apart.""")
        .isEmpty();
    assertThat(disagreements)
        .as("""
            The source's declared type and the value's inferred type disagree. A sparse column is \
            created from the source's opinion and later rows are bound from the value's, so the \
            two must meet or the INSERT will not.""")
        .isEmpty();
  }

  @Test
  void noSourceTypeHintCanEverProduceJsonb() throws Exception {
    // A nested value has no SQL type. `split` with into-array replaces the cell IN PLACE
    // (SplitHook: row.put(col, arr)), so on jdbc-generic that column does carry a hint — the
    // source's own type for a delimited string, which lands in the default branch as TEXT. The
    // hint road therefore cannot rescue a null-only chunk into jsonb on ANY connector, and the
    // transformers table says so. This is what makes that sentence true.
    List<String> jsonbClaims = new ArrayList<>();
    int swept = 0;

    for (Field f : Types.class.getFields()) {
      if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
      swept++;
      if ("JSONB".equals(SchemaInferrer.pgTypeForSqlType(f.getInt(null)))) {
        jsonbClaims.add(f.getName());
      }
    }

    assertThat(swept).as("the sweep must actually see java.sql.Types").isPositive();
    assertThat(jsonbClaims)
        .as("""
            A source type now maps to JSONB. That is not wrong in itself, but the transformers \
            doc tells integrators a null-only creating chunk freezes an into-array column to \
            text on every connector — rewrite that sentence before letting this pass.""")
        .isEmpty();
  }

  /**
   * Every candidate the inferrer can emit must be some row's EXPECTED answer.
   *
   * <p>The obvious way to write this is to collect {@code infer(value)} over the table — and it is
   * a test that cannot fail, because adding a candidate that changes a row's result changes the
   * collected set with it. The code would be compared to itself. The expectations are the only
   * independent statement of what the table covers, so they are what is collected.
   */
  @Test
  void everyCandidateTypeTheInferrerCanEmitIsExercisedSomewhere() {
    Set<String> expectedByTheTable = new LinkedHashSet<>();
    for (Arguments a : valueShapes().toList()) expectedByTheTable.add((String) a.get()[2]);

    assertThat(expectedByTheTable)
        .as("a candidate type no row EXPECTS is a branch nobody has ever seen run")
        .containsAll(declaredCandidateDdls());
  }

  /** Every candidate's DDL, read from the enum itself so a new one is in scope the day it lands. */
  private static Set<String> declaredCandidateDdls() {
    Set<String> ddls = new LinkedHashSet<>();
    try {
      Class<?> candidate = Class.forName(SchemaInferrer.class.getName() + "$Candidate");
      Field ddl = candidate.getDeclaredField("ddl");
      ddl.setAccessible(true);
      for (Object c : candidate.getEnumConstants()) ddls.add((String) ddl.get(c));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("the Candidate enum moved — this sweep polices nothing now", e);
    }
    return ddls;
  }

  static Stream<Arguments> valueShapes() {
    return Stream.of(
        Arguments.of("a string", "Bénédicte", "TEXT"),
        Arguments.of("a BigDecimal, as JDBC returns for numerics", new BigDecimal("1.5"), "NUMERIC(18,4)"),

        // A whole number is a whole number all the way to the screen. These four used to be
        // NUMERIC(18,4), so a JSON `partner_id: 1000` printed 1000.0000 in psql, in every view and
        // in the answers the ask door writes (#497). A BigDecimal is NOT among them, on purpose:
        // it is what JDBC returns for a source column DECLARED numeric, scale and all, and the
        // source's own opinion is the one the hint road carries.
        Arguments.of("a boxed integer", 42, "BIGINT"),
        Arguments.of("a long", 42L, "BIGINT"),
        Arguments.of("a short", (short) 42, "BIGINT"),
        Arguments.of("a BigInteger", BigInteger.valueOf(42), "BIGINT"),
        Arguments.of("a double that happens to be whole", 42.0d, "NUMERIC(18,4)"),
        Arguments.of("a java.sql.Timestamp", Timestamp.valueOf("2026-05-23 14:30:00"), "TIMESTAMPTZ"),
        Arguments.of("a java.sql.Date", Date.valueOf("2026-05-23"), "DATE"),
        Arguments.of("a boolean", Boolean.TRUE, "BOOLEAN"),
        Arguments.of("a list", List.of(1, 2, 3), "JSONB"),
        Arguments.of("an empty list", List.of(), "JSONB"),

        // Both nested shapes, one type. This row read "a nested map — NOT jsonb, unlike a list"
        // and expected TEXT; pinning the asymmetry is what made changing it a decision recorded
        // here rather than a silent drift. Neither shape could actually be WRITTEN before, so the
        // asymmetry never reached a table — it reached a poisoned chunk.
        Arguments.of("a nested map, typed like a list", Map.of("k", "v"), "JSONB"),

        // The TEXT fallbacks that surprise. Pinned so each is a decision someone made rather than
        // a branch nobody looked at.
        Arguments.of("a byte array", new byte[] {1, 2, 3}, "TEXT"),
        Arguments.of("a UUID", UUID.fromString("11111111-2222-3333-4444-555555555555"), "TEXT"),
        Arguments.of("a java.time.LocalTime", LocalTime.NOON, "TEXT"),
        Arguments.of("a java.sql.Time", Time.valueOf("12:00:00"), "TEXT"));
  }

  @ParameterizedTest(name = "{0} is typed {2}")
  @MethodSource("valueShapes")
  void aValueIsTypedByItsJavaTypeNotItsContent(String axis, Object value, String expected) {
    assertThat(infer(value)).as("%s", axis).isEqualTo(expected);
  }

  /**
   * One fractional value anywhere in the sample types the WHOLE column {@code NUMERIC(18,4)} —
   * the ordinary all-or-nothing rule, stated here because it is what makes {@code BIGINT} safe to
   * choose at all. A column whose sampled values are whole is narrowed; the moment one is not, it
   * is not.
   *
   * <p>What the sample cannot see is a later chunk. That case is not this class's: it is handled
   * where the schema can still change, by {@code DynamicTableWriter.widenings}.
   */
  @Test
  void aColumnMixingWholeAndFractionalNumbersIsNumericNotBigint() {
    Map<String, Object> whole = new LinkedHashMap<>();
    whole.put("credit_limit", 1000);
    Map<String, Object> fractional = new LinkedHashMap<>();
    fractional.put("credit_limit", 748.5d);

    assertThat(inferrer.infer(List.of(whole, fractional)).get("credit_limit")).isEqualTo("NUMERIC(18,4)");
    assertThat(inferrer.infer(List.of(whole)).get("credit_limit")).isEqualTo("BIGINT");
  }

  @Test
  void aNullOnlyColumnTakesTheHintWhenThereIsOne_andTextWhenThereIsNot() {
    // The class javadoc used to say "Null-only fields → TEXT" flatly, which the hint feature made
    // false without the sentence noticing. Both halves are pinned here so the doc cannot drift
    // ahead of the code again.
    Map<String, Object> allNull = new LinkedHashMap<>();
    allNull.put("sparse", null);

    assertThat(inferrer.infer(List.of(allNull), Map.of("sparse", "NUMERIC(18,4)")).get("sparse"))
        .isEqualTo("NUMERIC(18,4)");
    assertThat(inferrer.infer(List.of(allNull), Map.of()).get("sparse")).isEqualTo("TEXT");
  }

  private String infer(Object value) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("c", value);
    return inferrer.infer(List.of(row)).get("c");
  }
}
