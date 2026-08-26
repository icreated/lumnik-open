package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TargetBlockValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void emptyObject_acceptsAll_noErrors() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            TargetBlockValidator.configDef(), mapper.readTree("{}"), "spec.target");
        assertTrue(errs.isEmpty(), () -> "expected no errors, got: " + errs);
    }

    @Test void fullObject_declaredKeysClean_undeclaredWarns() throws Exception {
        // The block is CLOSED (C3): table_prefix left the ConfigDef with B2 — it now earns
        // the unknown-key WARNING instead of passing silently; tags stays clean.
        List<ValidationError> errs = ConfigDefRunner.run(
            TargetBlockValidator.configDef(),
            mapper.readTree("{\"table_prefix\":\"ext_\",\"tags\":[\"crm\"]}"),
            "spec.target");
        assertTrue(errs.stream().allMatch(e -> e.severity() == ValidationError.Severity.WARNING),
            () -> "only warnings expected, got: " + errs);
        assertTrue(errs.stream().anyMatch(e -> e.path().equals("spec.target.table_prefix")),
            () -> "expected the unknown-key warning for table_prefix, got: " + errs);
    }

    @Test void indexesList_acceptsAndValidatesClean() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            TargetBlockValidator.configDef(),
            mapper.readTree("{\"indexes\":[{\"columns\":[\"email\"]},{\"columns\":[\"email\",\"signup_date\"],\"unique\":true}]}"),
            "spec.target");
        assertTrue(errs.isEmpty(), () -> "expected no errors for indexes list, got: " + errs);
    }

    @Test void validateIndexes_validList_noErrors() throws Exception {
        var node = mapper.readTree("[{\"columns\":[\"email\"]},{\"columns\":[\"a\",\"b\"],\"unique\":true}]");
        assertTrue(TargetBlockValidator.validateIndexes(node, "spec.target.indexes").isEmpty());
    }

    @Test void validateIndexes_missingOrNull_noErrors() throws Exception {
        assertTrue(TargetBlockValidator.validateIndexes(mapper.readTree("{}").path("indexes"), "p").isEmpty());
        assertTrue(TargetBlockValidator.validateIndexes(mapper.readTree("null"), "p").isEmpty());
    }

    @Test void validateIndexes_notAnArray_fails() throws Exception {
        var errs = TargetBlockValidator.validateIndexes(mapper.readTree("\"nope\""), "spec.target.indexes");
        assertEquals(1, errs.size());
        assertSame(ValidationError.Severity.ERROR, errs.get(0).severity());
    }

    @Test void validateIndexes_malformedEntries_eachFailsWithError() throws Exception {
        // 1=not-object, "x"=not-object, {}=no columns, {columns:[]}=empty,
        // {columns:[123]}=non-string col, {columns:[a],unique:"yes"}=non-boolean unique
        var node = mapper.readTree(
            "[1,\"x\",{},{\"columns\":[]},{\"columns\":[123]},{\"columns\":[\"a\"],\"unique\":\"yes\"}]");
        var errs = TargetBlockValidator.validateIndexes(node, "spec.target.indexes");
        assertEquals(6, errs.size(), () -> "one error per malformed entry; got: " + errs);
        assertTrue(errs.stream().allMatch(e -> e.severity() == ValidationError.Severity.ERROR));
    }
}
