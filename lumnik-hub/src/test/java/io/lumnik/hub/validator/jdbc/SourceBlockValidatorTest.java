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
            mapper.readTree("{\"name\":\"orders\",\"mode\":\"full\",\"schema\":\"public\","
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

    @Test void perTable_aNameThatIsNotAPlainIdentifier_isRefusedAtTheDoor_notMidRun() throws Exception {
        // Before this rule a manifest naming a table `9orders` passed `lm validate`, passed apply,
        // PERSISTED, and only died once a run had started — why, in full, at perTableDef().
        List<ValidationError> errs = ConfigDefRunner.run(
            SourceBlockValidator.perTableDef(),
            mapper.readTree("{\"name\":\"9orders\",\"mode\":\"full\"}"),
            "spec.source.tables[0]");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.ERROR
                && e.path().equals("spec.source.tables[0].name")),
            () -> "expected the identifier refusal for name 9orders, got: " + errs);
    }

    @Test void perTable_aPlainName_isNotRefused() throws Exception {
        // The other direction: the rule must accept every name the runtime accepts, or it refuses
        // working manifests. Digits and underscores are legal anywhere but the first character.
        // Pinned on THIS key, not on an empty list — a future required key elsewhere in the def is
        // not this rule's defect, and a guard that can go red for a non-defect is worth nothing.
        List<ValidationError> errs = ConfigDefRunner.run(
            SourceBlockValidator.perTableDef(),
            mapper.readTree("{\"name\":\"_order_items_2\",\"mode\":\"full\"}"),
            "spec.source.tables[0]");
        assertTrue(errs.stream().noneMatch(e -> e.path().equals("spec.source.tables[0].name")),
            () -> "a plain identifier must pass, got: " + errs);
    }

    @Test void perTable_withoutSchema_isRefusedAtTheDoor_notOnEveryRun() throws Exception {
        // The run reads `schema` unconditionally (JdbcConnectorType -> "Missing config key: schema");
        // an optional key here let such a manifest validate, apply, and then fail every run (#1243).
        // The node is the defaults-merged table, so a schema set once in `defaults` still satisfies it.
        List<ValidationError> errs = ConfigDefRunner.run(
            SourceBlockValidator.perTableDef(),
            mapper.readTree("{\"name\":\"orders\",\"mode\":\"full\"}"),
            "spec.source.tables[0]");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.ERROR
                && e.path().equals("spec.source.tables[0].schema")),
            () -> "expected the missing-schema refusal, got: " + errs);
    }
}
