package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.RowHook;
import io.lumnik.hub.connector.mapping.hooks.row.CoalesceHook;
import io.lumnik.hub.connector.mapping.hooks.row.DefaultsHook;
import io.lumnik.hub.connector.mapping.hooks.row.MapColumnsHook;
import io.lumnik.hub.connector.mapping.hooks.row.SplitRowHook;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A created table's columns follow the SOURCE's field order.
 *
 * <p>Both readers hand the mapping path an ordered row — {@code CsvFileReader} builds a
 * {@code LinkedHashMap} keyed by the header, {@code JdbcChunkReader} one keyed by the select list,
 * and the kafka reader parses into one. The order was then thrown away twice over: by
 * {@link SchemaInferrer}, whose returned map was a {@code HashMap}, and by every row hook that
 * rebuilt its row with {@code new HashMap<>(row)}. A header {@code code,name,city,segment,
 * credit_limit} reached DDL as {@code city, code, name, segment, credit_limit} (#496).
 *
 * <p>What this buys is the PHYSICAL order: {@code \d}, {@code SELECT *}, and every
 * {@code kind:View} that declares no explicit columns — all of which read the table Postgres
 * created from {@code inferred}, in memory, before anything is persisted.
 *
 * <p>The schema card is a separate path — {@code RegistrySchemaBuilder} reads {@code
 * schema_inferred} back through {@code jsonb}, which canonicalises object keys by length then
 * bytes, so the type map's own iteration order is not the source's (#521). Its card now reorders
 * against the physical table's {@code information_schema.columns.ordinal_position} instead — the
 * physical truth this test's DDL cases already establish, needing no new storage. See {@code
 * RegistrySchemaBuilderIT#cardColumnsFollowThePhysicalTableOrderNotJsonbsCanonicalOrder}.
 */
class SourceFieldOrderTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The bench's own CSV header (#496), kept verbatim so the test speaks about the measurement. */
    private static final List<String> HEADER =
        List.of("code", "name", "city", "segment", "credit_limit");

    private static Map<String, Object> row(List<String> fields) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (String f : fields) r.put(f, f + "-value");
        return r;
    }

    @Test
    void theInferredSchemaKeepsTheOrderTheRowPresentedItsFieldsIn() {
        assertThat(new SchemaInferrer().infer(List.of(row(HEADER))).keySet())
            .containsExactlyElementsOf(HEADER);
    }

    @Test
    void theCreateTableDdlListsThePayloadColumnsInThatSameOrder() {
        Map<String, String> schema = new SchemaInferrer().infer(List.of(row(HEADER)));
        String ddl = new DynamicTableWriter().createTableDdl(
            new MappingTarget.Table("connector", "t_customer", true, MappingTarget.OnConflict.SKIP, List.of()),
            schema);

        assertThat(payloadColumnsOf(ddl)).containsExactlyElementsOf(HEADER);
    }

    /**
     * The same, with the columns actually TYPED.
     *
     * <p>Every other case here feeds string values, so every column comes out {@code TEXT} — which
     * is how a defect in the DDL parse stayed invisible: a type carrying a comma,
     * {@code NUMERIC(18,4)}, was split into two and invented a column called {@code 4)}. A test
     * about column ORDER that never exercises a typed column proves less than its name says, and
     * whole-number typing (#497) is what put that type within reach of these very tests.
     */
    @Test
    void theOrderHoldsWhenTheTypesThemselvesCarryCommas() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "C1");                 // TEXT
        row.put("qty", 3);                     // BIGINT
        row.put("credit_limit", 748.5d);       // NUMERIC(18,4) — the comma that broke the parse
        row.put("city", "Lyon");               // TEXT

        Map<String, String> schema = new SchemaInferrer().infer(List.of(row));
        assertThat(schema.get("credit_limit")).as("the case must really produce the comma")
            .isEqualTo("NUMERIC(18,4)");

        String ddl = new DynamicTableWriter().createTableDdl(
            new MappingTarget.Table("connector", "t_typed", true, MappingTarget.OnConflict.SKIP, List.of()),
            schema);

        assertThat(payloadColumnsOf(ddl)).containsExactly("code", "qty", "credit_limit", "city");
    }

    /**
     * A source field name is whatever the source called it, quotes included.
     *
     * <p>The kafka bench alone ships {@code Partner Name}, {@code c_bpartner.id} and
     * {@code MONTANT_€} — nothing validates a field name on that connector, and there is no
     * mapping hook to rename one. {@code quoteIdent} doubles an embedded quote, so {@code a"b}
     * reaches the DDL as {@code "a""b"}: the order this test is about is only readable if the
     * name is read back as the source spelled it.
     */
    @Test
    void aFieldNameCarryingAQuoteIsReadBackAsTheSourceSpelledIt() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "C1");
        row.put("a\"b", "x");
        row.put("city", "Lyon");

        String ddl = new DynamicTableWriter().createTableDdl(
            new MappingTarget.Table("connector", "t_quoted", true, MappingTarget.OnConflict.SKIP, List.of()),
            new SchemaInferrer().infer(List.of(row)));

        assertThat(ddl).as("the DDL really carries the doubled quote").contains("\"a\"\"b\"");
        assertThat(payloadColumnsOf(ddl)).containsExactly("code", "a\"b", "city");
    }

    /**
     * A field no earlier row carried lands after the ones that came before it — the only order a
     * per-chunk inferrer can honour, since it never sees a source's full field list at once.
     */
    @Test
    void aFieldFirstSeenInALaterRowLandsAfterTheEarlierOnes() {
        Map<String, Object> first = row(List.of("code", "name"));
        Map<String, Object> second = row(List.of("code", "name", "vat_number"));

        assertThat(new SchemaInferrer().infer(List.of(first, second)).keySet())
            .containsExactly("code", "name", "vat_number");
    }

    // ── the row hooks that rebuild a row ──────────────────────────────────────

    static Stream<Arguments> rowHooksThatRebuildTheirRow() {
        return Stream.of(
            Arguments.of("coalesce", new CoalesceHook(), "{\"column\":\"city\",\"from\":[\"name\"]}"),
            Arguments.of("defaults", new DefaultsHook(), "{\"segment\":\"retail\"}"),
            Arguments.of("map-columns (drop only)", new MapColumnsHook(), "{\"drop\":[\"segment\"]}"),
            Arguments.of("split-row", new SplitRowHook(), "{\"by\":\"segment\",\"sep\":\";\"}"));
    }

    /**
     * Every hook here copies its input row before editing it, and each copy used to be a
     * {@code HashMap} — so a mapping with one hook in it scrambled the table's columns even
     * though the reader had ordered them. The expected order is the INPUT's, minus whatever the
     * hook's own params remove.
     */
    @ParameterizedTest(name = "the {0} hook keeps the field order it was given")
    @MethodSource("rowHooksThatRebuildTheirRow")
    void aRowHookKeepsTheFieldOrderItWasGiven(String axis, RowHook hook, String params) throws Exception {
        JsonNode p = M.readTree(params);
        Map<String, Object> out = hook.apply(row(HEADER), p, new MappingState()).get(0);

        assertThat(out.keySet()).as("%s", axis)
            .containsExactlyElementsOf(HEADER.stream().filter(out::containsKey).toList());
    }

    /**
     * {@code map-columns} keeps a renamed column in its source slot (#526 fix) instead of moving
     * it to the end. See {@code MapColumnsHook} for the slot-based shape that keeps this AND the
     * sequential collision rule {@code MapColumnsRenameTest} pins.
     */
    @Test
    void mapColumns_keepsARenamedColumnInItsSourcePosition() throws Exception {
        JsonNode p = M.readTree("{\"rename\":{\"code\":\"customer_code\"}}");
        Map<String, Object> out = new MapColumnsHook().apply(row(HEADER), p, new MappingState()).get(0);

        assertThat(out.keySet())
            .containsExactly("customer_code", "name", "city", "segment", "credit_limit");
    }

    /**
     * A rename CHAIN ({@code a: b} then {@code b: c}, neither target present in the row) still
     * lands at the first step's slot, with the untouched columns around it keeping their own
     * relative order — the case the slot rewrite's {@code O(columns + renames)} shape has to get
     * right without a rebuild per step.
     */
    @Test
    void mapColumns_aChainLandsAtItsFirstStepsSourcePositionAmongUntouchedColumns() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "a-value");
        row.put("keep1", "keep1-value");
        row.put("keep2", "keep2-value");
        JsonNode p = M.readTree("{\"rename\":{\"a\":\"b\",\"b\":\"c\"}}");

        Map<String, Object> out = new MapColumnsHook().apply(row, p, new MappingState()).get(0);

        assertThat(out.keySet()).containsExactly("c", "keep1", "keep2");
        assertThat(out.get("c")).isEqualTo("a-value");
    }

    /** The payload columns of a CREATE TABLE, in the order the DDL declares them. */
    private static List<String> payloadColumnsOf(String ddl) {
        return DynamicTableSystemColumnsTest.columnsOf(ddl).stream()
            .filter(name -> !DynamicTableWriter.SYSTEM_COLUMNS.contains(name))
            .toList();
    }
}
