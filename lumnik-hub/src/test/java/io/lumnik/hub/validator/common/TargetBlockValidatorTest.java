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

    @Test void noIndexes_orIndexesWrittenEmpty_isClean() throws Exception {
        for (String json : List.of("{}", "{\"indexes\":null}", "{\"indexes\":[]}")) {
            assertTrue(run(json).isEmpty(), () -> json);
        }
    }

    /** One refusal for what is no list, at the list — not one more per element, which would be the same mistake twice. */
    @Test void indexesThatIsNotAList_isRefusedByTheDeclaration_withItsHint() throws Exception {
        List<ValidationError> errs = run("{\"indexes\":\"nope\"}");
        assertEquals(1, errs.size(), () -> "exactly one refusal: " + errs);
        assertEquals("spec.target.indexes", errs.get(0).path());
        assertSame(ValidationError.Severity.ERROR, errs.get(0).severity());
        assertTrue(errs.get(0).recommendations().stream().anyMatch(h -> h.contains("columns")),
            () -> "the refusal must still teach the shape: " + errs.get(0));
    }

    /** The runtime drops a malformed entry in silence — the user would get a "valid" config with missing indexes. */
    @Test void malformedEntries_eachFailsOnceAtItsOwnPath() throws Exception {
        // 1=not-object, "x"=not-object, {}=no columns, {columns:[]}=empty,
        // {columns:[123]}=non-string col, {columns:[a],unique:"yes"}=non-boolean unique
        var errs = run("{\"indexes\":[1,\"x\",{},{\"columns\":[]},{\"columns\":[123]},{\"columns\":[\"a\"],\"unique\":\"yes\"}]}");

        assertEquals(List.of("spec.target.indexes[0]", "spec.target.indexes[1]", "spec.target.indexes[2].columns",
                "spec.target.indexes[3].columns", "spec.target.indexes[4].columns", "spec.target.indexes[5].unique"),
            errs.stream().map(ValidationError::path).toList(), () -> errs.toString());
        assertTrue(errs.stream().allMatch(e -> e.severity() == ValidationError.Severity.ERROR));
    }

    @Test void anEmptyColumnsTeachesWhatToWrite() throws Exception {
        var errs = run("{\"indexes\":[{\"columns\":[]}]}");
        assertTrue(errs.get(0).recommendations().stream().anyMatch(h -> h.contains("columns: [email]")), () -> errs.toString());
    }

    private List<ValidationError> run(String json) throws Exception {
        return ConfigDefRunner.run(TargetBlockValidator.configDef(), mapper.readTree(json), "spec.target");
    }
}
