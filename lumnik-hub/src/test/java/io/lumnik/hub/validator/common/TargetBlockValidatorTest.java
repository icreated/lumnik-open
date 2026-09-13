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

    /**
     * The shape refusal MOVED, so the test follows it. It used to live here, in the per-entry
     * walker, and the declaration refused the same node one tier up — one mistake, two errors, on
     * one path. The walker now bails on a non-array and the declaration owns the shape, hint
     * included; the two tests below assert both halves of that division, so neither can quietly
     * take the other's job back.
     */
    @Test void validateIndexes_notAnArray_isNoLongerThisWalkersRefusal() throws Exception {
        var errs = TargetBlockValidator.validateIndexes(mapper.readTree("\"nope\""), "spec.target.indexes");
        assertTrue(errs.isEmpty(), () -> "the declaration refuses the shape, not this walker: " + errs);
    }

    @Test void indexesThatIsNotAList_isRefusedByTheDeclaration_withItsHint() throws Exception {
        List<ValidationError> errs = ConfigDefRunner.run(
            TargetBlockValidator.configDef(), mapper.readTree("{\"indexes\":\"nope\"}"), "spec.target");
        assertEquals(1, errs.size(), () -> "exactly one refusal: " + errs);
        assertEquals("spec.target.indexes", errs.get(0).path());
        assertSame(ValidationError.Severity.ERROR, errs.get(0).severity());
        assertTrue(errs.get(0).recommendations().stream().anyMatch(h -> h.contains("columns")),
            () -> "the refusal must still teach the shape: " + errs.get(0));
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
