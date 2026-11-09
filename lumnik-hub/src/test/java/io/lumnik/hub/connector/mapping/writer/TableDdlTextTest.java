package io.lumnik.hub.connector.mapping.writer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #1814: the two {@code CREATE TABLE}s are written from their column declarations. The inventory tests see the column
 * names only; these pin the whole text — order, types, constraints — because this DDL runs on the BYPASSRLS admin
 * datasource and an existing table is never re-created to fix it.
 */
class TableDdlTextTest {

    @Test
    void theHybridTable_isTheReservedColumnsAroundThePromotedOnes() {
        List<PromotedColumn> promote = List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty()));

        assertThat(HybridJsonbWriter.createTableSql("ext.t", promote)).isEqualTo(
            "CREATE TABLE IF NOT EXISTS ext.t (id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "external_id TEXT NOT NULL, \"amount\" BIGINT, row_hash TEXT, data JSONB NOT NULL, "
                + "indexed_at TIMESTAMPTZ DEFAULT now(), connector_id UUID NOT NULL, endpoint_id UUID NOT NULL, "
                + "UNIQUE (tenant_id, external_id))");
    }

    @Test
    void theHybridTable_withNothingPromoted() {
        assertThat(HybridJsonbWriter.createTableSql("ext.t", List.of())).isEqualTo(
            "CREATE TABLE IF NOT EXISTS ext.t (id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "external_id TEXT NOT NULL, row_hash TEXT, data JSONB NOT NULL, "
                + "indexed_at TIMESTAMPTZ DEFAULT now(), connector_id UUID NOT NULL, endpoint_id UUID NOT NULL, "
                + "UNIQUE (tenant_id, external_id))");
    }

    @Test
    void theDynamicTable_isTheSystemPreludeThenThePayload() {
        MappingTarget.Table spec = new MappingTarget.Table("connector", "t_x", true, MappingTarget.OnConflict.SKIP, List.of());

        assertThat(new DynamicTableWriter().createTableDdl(spec, Map.of("amount", "NUMERIC"))).isEqualTo(
            "CREATE TABLE IF NOT EXISTS \"connector\".\"t_x\" (id BIGSERIAL PRIMARY KEY, _row_hash TEXT NOT NULL, "
                + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), _source TEXT, _run_id UUID, "
                + "_mapping_version INT, tenant_id BIGINT NOT NULL, \"amount\" NUMERIC, "
                + "UNIQUE (tenant_id, _row_hash))");
    }

    @Test
    void aTableThatMarksSeenRows_carriesSeenRunBetweenTheMappingVersionAndTheTenant() {
        MappingTarget.Table spec = new MappingTarget.Table("connector", "t_x", true, MappingTarget.OnConflict.SKIP, List.of(), true);

        assertThat(new DynamicTableWriter().createTableDdl(spec, Map.of("amount", "NUMERIC"))).isEqualTo(
            "CREATE TABLE IF NOT EXISTS \"connector\".\"t_x\" (id BIGSERIAL PRIMARY KEY, _row_hash TEXT NOT NULL, "
                + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), _source TEXT, _run_id UUID, "
                + "_mapping_version INT, _seen_run UUID, tenant_id BIGINT NOT NULL, \"amount\" NUMERIC, "
                + "UNIQUE (tenant_id, _row_hash))");
    }
}
