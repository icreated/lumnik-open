package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The hub's own answer to "what can a user do with this column" — pure, no DB. */
class ColumnKindTest {

    @Test
    void aDeclaredDomainWinsOverTheType() {
        assertThat(ColumnKind.of("text", List.of("GOLD"))).isEqualTo(ColumnKind.CHOICE);
        assertThat(ColumnKind.of("integer", List.of("1", "2"))).isEqualTo(ColumnKind.CHOICE);
    }

    @Test
    void informationSchemaTypeNamesMapToTheKinds() {
        for (String t : List.of("smallint", "integer", "bigint", "numeric", "numeric(12,2)", "decimal", "int4", "int8"))
            assertThat(ColumnKind.of(t, List.of())).as(t).isEqualTo(ColumnKind.NUMBER);
        for (String t : List.of("real", "double precision", "float4", "float8", "DOUBLE PRECISION")) {
            assertThat(ColumnKind.of(t, List.of())).as(t).isEqualTo(ColumnKind.FLOAT);
            assertThat(ColumnKind.of(t, List.of()).wire()).as("clients see a number").isEqualTo("number");
        }
        assertThat(ColumnKind.of("date", List.of())).isEqualTo(ColumnKind.DATE);
        for (String t : List.of("timestamp without time zone", "timestamp with time zone", "timestamp", "timestamptz", "TIMESTAMPTZ", "timestamp(3) with time zone"))
            assertThat(ColumnKind.of(t, List.of())).as(t).isEqualTo(ColumnKind.DATETIME);
        for (String t : List.of("text", "character varying", "varchar(40)", "character", "bpchar"))
            assertThat(ColumnKind.of(t, List.of())).as(t).isEqualTo(ColumnKind.TEXT);
    }

    @Test
    void everythingTheEngineCannotCompareIsOpaque_announcedAsText_neverNumberOrDate() {
        for (String t : List.of("money", "time without time zone", "time with time zone", "timetz", "interval",
                "boolean", "uuid", "jsonb", "bytea", "ARRAY", "inet")) {
            assertThat(ColumnKind.of(t, List.of())).as(t).isEqualTo(ColumnKind.OPAQUE);
            assertThat(ColumnKind.of(t, List.of()).wire()).as(t).isEqualTo("text");
        }
        assertThat(ColumnKind.of(null, null)).isEqualTo(ColumnKind.TEXT);
    }

    @Test
    void announcedOperatorsFollowTheKind() {
        assertThat(ColumnKind.CHOICE.operators()).containsExactly("=");
        assertThat(ColumnKind.TEXT.operators()).containsExactly("~", "=");
        for (ColumnKind k : List.of(ColumnKind.NUMBER, ColumnKind.DATE, ColumnKind.DATETIME))
            assertThat(k.operators()).containsExactly("=", ">=", "<=", ">", "<");
    }

    @Test
    void orderingIsRefusedOnlyWhereTheSqlWouldSilentlyMatchNothing() {
        // text columns keep ordering: a CSV-landed "credit_limit" is text holding numbers, shape-checked per row
        for (String t : List.of("text", "character varying", "integer", "numeric", "date",
                "timestamp with time zone", "double precision"))
            assertThat(ColumnKind.orders(t)).as(t).isTrue();
        assertThat(ColumnKind.orders(null)).as("unknown type: fail safe, as the SQL does").isTrue();
        for (String t : List.of("money", "time without time zone", "interval", "boolean", "uuid", "jsonb"))
            assertThat(ColumnKind.orders(t)).as(t).isFalse();
    }

    @Test
    void whatIsAnnouncedAndWhatIsEnforcedComeFromTheSameKind() {
        for (String t : List.of("text", "integer", "money", "date", "interval", "timestamptz", "boolean")) {
            ColumnKind k = ColumnKind.of(t, List.of());
            assertThat(ColumnKind.orders(t)).as(t).isEqualTo(k.orders());
            // a kind that announces an ordering operator must be one the gate lets through
            if (k.operators().contains(">")) assertThat(k.orders()).as(t).isTrue();
        }
    }

    @Test
    void everyKindEitherBuildsItsOrderingSql_orRefusesToOrder() {
        // the inventory guard: a new kind must decide here, or this fails
        for (ColumnKind k : ColumnKind.values()) {
            for (var operand : ViewFilter.OperandType.values()) {
                if (k.orders()) {
                    String sql = k.orderingSql("\"c\"", ">=", operand);
                    assertThat(sql).as(k + "/" + operand).contains("\"c\"").contains(">=").contains("?");
                } else {
                    assertThatThrownBy(() -> k.orderingSql("\"c\"", ">=", operand))
                        .as(k + "/" + operand).isInstanceOf(IllegalStateException.class);
                }
            }
        }
        assertThat(ColumnKind.values()).as("kinds that refuse to order").filteredOn(k -> !k.orders())
            .containsExactly(ColumnKind.OPAQUE);
    }

    @Test
    void orderingSql_shapeGuardedForTextAndExactNumbers_nativeForFloats() {
        assertThat(ColumnKind.NUMBER.orderingSql("\"c\"", ">", ViewFilter.OperandType.NUMERIC))
            .contains("CASE WHEN \"c\"::text ~").contains("::numeric").contains("ELSE false END");
        assertThat(ColumnKind.TEXT.orderingSql("\"c\"", "<", ViewFilter.OperandType.NUMERIC))
            .as("a CSV-landed number is text").contains("CASE WHEN").contains("::numeric");
        assertThat(ColumnKind.DATE.orderingSql("\"c\"", "<", ViewFilter.OperandType.TEMPORAL))
            .contains("::timestamptz");
        // a float is compared as a float: no text round trip, no shape guard, 1e-05 is found
        assertThat(ColumnKind.FLOAT.orderingSql("\"c\"", ">=", ViewFilter.OperandType.NUMERIC))
            .isEqualTo("\"c\" >= ?::float8");
        // ...but a temporal operand on a float stays on the guarded text path
        assertThat(ColumnKind.FLOAT.orderingSql("\"c\"", ">=", ViewFilter.OperandType.TEMPORAL)).contains("CASE WHEN");
    }

    @Test
    void textualTypesNeedNoCast_parametersIgnored() {
        assertThat(ColumnKind.isTextual("varchar(40)")).isTrue();
        assertThat(ColumnKind.isTextual("character varying")).isTrue();
        assertThat(ColumnKind.isTextual("numeric")).isFalse();
        assertThat(ColumnKind.isTextual(null)).isFalse();
    }

    @Test
    void anOperandFamilyMustMatchTheKind() {
        var num = ViewFilter.OperandType.NUMERIC;
        var tmp = ViewFilter.OperandType.TEMPORAL;
        for (ColumnKind k : List.of(ColumnKind.NUMBER, ColumnKind.FLOAT)) {
            assertThat(k.accepts(num)).as(k.name()).isTrue();
            assertThat(k.accepts(tmp)).as(k.name()).isFalse();
        }
        for (ColumnKind k : List.of(ColumnKind.DATE, ColumnKind.DATETIME)) {
            assertThat(k.accepts(tmp)).as(k.name()).isTrue();
            assertThat(k.accepts(num)).as(k.name()).isFalse();
        }
        assertThat(ColumnKind.TEXT.accepts(num)).isTrue();   // a CSV-landed number or date is text
        assertThat(ColumnKind.TEXT.accepts(tmp)).isTrue();
        assertThat(ColumnKind.OPAQUE.accepts(num)).isFalse();
        assertThat(ColumnKind.OPAQUE.accepts(tmp)).isFalse();
    }
}
