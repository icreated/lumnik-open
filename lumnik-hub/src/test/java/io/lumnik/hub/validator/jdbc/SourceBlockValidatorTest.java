package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceBlockValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void perTable_indexes_isHonoredByRuntime_mustStayDeclared() throws Exception {
        // JdbcConnectorType.parseIndexes reads per-table indexes from the merged table node —
        // a supported manifest key (wave-finale inventory refutation); it must not warn.
        List<ValidationError> errs = ConfigDefRunner.run(
            SourceBlockValidator.perTableDef(),
            mapper.readTree("{\"name\":\"orders\",\"mode\":\"full\","
                + "\"indexes\":[{\"columns\":[\"email\"],\"unique\":true}]}"),
            "spec.source.tables[0]");
        assertTrue(errs.isEmpty(), () -> "indexes is honored — no warning allowed, got: " + errs);
    }

    @Test void perTable_unknownKey_warnsIgnored() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            SourceBlockValidator.perTableDef(),
            mapper.readTree("{\"name\":\"orders\",\"mode\":\"full\",\"indeces\":[]}"),
            "spec.source.tables[0]");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.WARNING
                && e.path().equals("spec.source.tables[0].indeces")),
            () -> "expected the unknown-key warning for indeces, got: " + errs);
    }
}
