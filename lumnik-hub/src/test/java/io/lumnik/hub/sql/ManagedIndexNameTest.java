package io.lumnik.hub.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.hub.sql.ManagedIndex.Spec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedIndexNameTest {

    private static Spec spec(String table, String... exprs) {
        return new Spec("connector", table, List.of(exprs));
    }

    @Test
    void theNameIsStableAndKeepsTheTable() {
        Spec s = spec("t_orders", "tenant_id", "code");
        assertThat(s.name()).isEqualTo(spec("t_orders", "tenant_id", "code").name()).startsWith("t_orders_mx_");
    }

    @Test
    void anotherExpressionOnTheSameTableIsAnotherIndex() {
        assertThat(spec("t_orders", "tenant_id", "code").name())
            .isNotEqualTo(spec("t_orders", "tenant_id", "trim_scale(code::numeric)").name());
    }

    @Test
    void aLongTableFitsPostgresAndStaysDistinct() {
        String table = "t_" + "x".repeat(70);
        Spec a = spec(table, "tenant_id", "a");
        assertThat(a.name().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(63);
        assertThat(a.name()).isNotEqualTo(spec(table, "tenant_id", "b").name());
    }
}
