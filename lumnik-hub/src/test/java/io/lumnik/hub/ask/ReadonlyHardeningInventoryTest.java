package io.lumnik.hub.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lumnik.extraction.spi.ask.SqlGuard;
import io.lumnik.hub.connector.rls.RlsEnforcedPgResource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The check and what it checks are written in two places: the probe here, the REVOKEs in the shipped SQL. A revoke
 * added to the file but not to the probe would let an old volume open Ask without it; the other way round keeps Ask
 * closed for good. Both files are read as shipped, and compared both ways.
 */
class ReadonlyHardeningInventoryTest {

    static final Path V92 = Path.of("src/main/resources/db/migration/connector/V92__readonly_platform_tables_revoked.sql");

    @Test
    void the_probe_reads_the_same_functions_the_file_revokes() throws IOException {
        assertThat(oneLine(Files.readString(RlsEnforcedPgResource.EXTENSIONS_SQL)))
            .contains("AND (" + ReadonlyHardening.REVOKED_FUNCTIONS + ")");
    }

    /** The other way: every REVOKE the file makes is one the probe reads back — the function loop and pg_stat_activity. */
    @Test
    void the_file_revokes_nothing_the_probe_does_not_read() throws IOException {
        var revokes = Pattern.compile("REVOKE [^;']+").matcher(oneLine(Files.readString(RlsEnforcedPgResource.EXTENSIONS_SQL)))
            .results().map(m -> m.group().strip()).toList();

        assertThat(revokes).containsExactly(
            "REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC, lumnik_readonly",
            "REVOKE SELECT ON pg_catalog.pg_stat_activity FROM PUBLIC, lumnik_readonly");
        assertThat(oneLine(Files.readString(RlsEnforcedPgResource.EXTENSIONS_SQL)).split("FOR f IN", -1))
            .as("one loop, whose predicate is the probe's").hasSize(2);
        assertThat(ReadonlyHardening.REVOKED_RELATIONS).contains("pg_catalog.pg_stat_activity");
    }

    @Test
    void the_probe_reads_exactly_the_platform_tables_v92_revokes() throws IOException {
        var revoked = Pattern.compile("connector\\.\\w+").matcher(oneLine(Files.readString(V92))).results()
            .map(m -> m.group()).toList();

        assertThat(ReadonlyHardening.REVOKED_RELATIONS)
            .filteredOn(r -> r.startsWith("connector."))
            .containsExactlyInAnyOrderElementsOf(revoked);
    }

    /**
     * The guard is the layer above the database: every name the file revokes is refused there too. The IN-list names
     * are read from the probe's predicate; each pattern by one name it matches.
     */
    @Test
    void the_guard_refuses_every_function_the_file_revokes() {
        var named = Pattern.compile("'([a-z_]+)'").matcher(
                ReadonlyHardening.REVOKED_FUNCTIONS.substring(0, ReadonlyHardening.REVOKED_FUNCTIONS.indexOf(')')))
            .results().map(m -> m.group(1)).toList();
        var byPattern = List.of("query_to_xml", "database_to_xmlschema", "pg_advisory_lock", "pg_try_advisory_xact_lock",
            "pg_stat_get_activity", "pg_stat_get_backend_activity", "pg_sleep_for", "lo_get", "lo_open");
        SqlGuard guard = new SqlGuard(500);

        assertThat(named).contains("set_config", "ts_rewrite");
        assertThat(Stream.concat(named.stream(), byPattern.stream())).allSatisfy(fn ->
            assertThatThrownBy(() -> guard.check("SELECT " + fn + "(1)")).as(fn)
                .isInstanceOf(SqlGuard.SqlRejectedException.class));
    }

    private static String oneLine(String sql) {
        return sql.replaceAll("--[^\n]*", " ").replaceAll("\\s+", " ");
    }
}
