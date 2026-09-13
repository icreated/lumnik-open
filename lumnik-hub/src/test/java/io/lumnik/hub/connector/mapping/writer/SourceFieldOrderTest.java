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
 * <p><b>The schema card is NOT fixed by this, and measuring stopped short of saying so.</b> The
 * card's road is the registry: {@code schema_inferred} is {@code jsonb} (V11), and jsonb
 * canonicalises object keys by length then bytes — so {@code RegistrySchemaBuilder} reads back
 * {@code city, code, name, segment, credit_limit}, the very order this test's first case
 * attributes to {@code HashMap}. Order that must survive a round trip has to travel in an ARRAY,
 * not in object keys; that is a second change, and it is filed rather than implied here.
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
     * The one hook whose output order is NOT the row's: {@code map-columns} renames by
     * {@code remove} then {@code put}, so a renamed column moves to the end of the row.
     *
     * <p>Pinned rather than fixed, and pinned as RESIDUE, not as intent. Renaming in place means
     * rebuilding the row in source order, and the naive one-pass form flips a collision rule this
     * hook states deliberately: when a rename's target name is already in the row, the renamed
     * value wins and the collision is counted; rebuilt in one pass, a target appearing later in
     * the row would win instead. Two passes — collect the renames present, then rebuild with
     * rename precedence — keep both properties, in about eight lines. That is a change to a hook's
     * output for a defect about column order in {@code \d}, so it is filed, not smuggled in here.
     */
    @Test
    void mapColumns_sendsARenamedColumnToTheEnd() throws Exception {
        JsonNode p = M.readTree("{\"rename\":{\"code\":\"customer_code\"}}");
        Map<String, Object> out = new MapColumnsHook().apply(row(HEADER), p, new MappingState()).get(0);

        assertThat(out.keySet())
            .containsExactly("name", "city", "segment", "credit_limit", "customer_code");
    }

    /** The payload columns of a CREATE TABLE, in the order the DDL declares them. */
    private static List<String> payloadColumnsOf(String ddl) {
        return DynamicTableSystemColumnsTest.columnsOf(ddl).stream()
            .filter(name -> !DynamicTableWriter.SYSTEM_COLUMNS.contains(name))
            .toList();
    }
}
