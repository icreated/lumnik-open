package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A field declares the conditions it carries: when it is required, what it refuses, which of several it needs. */
class ConfigDefConditionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ValidationError> run(ConfigDef def, String json) throws Exception {
        return ConfigDefRunner.run(def, mapper.readTree(json), "spec");
    }

    private static ConfigDef archive() {
        return new ConfigDef()
            .requireEnum("after_process", Importance.MEDIUM, "After a file", "move", "none")
            .optional("archive_path", Type.STRING, Importance.LOW, "Where moved files go", null)
                .requiredWhen("after_process", "move")
                .hint("Set archive_path: /path/to/archive/");
    }

    // -- requiredWhen ------------------------------------------------------------------------------

    @Test void aFieldRequiredWhenAnotherHasAValue_isRefusedWithItsHint() throws Exception {
        var errs = run(archive(), "{\"after_process\":\"move\"}");

        assertThat(errs).hasSize(1);
        assertThat(errs.get(0).path()).isEqualTo("spec.archive_path");
        assertThat(errs.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(errs.get(0).message()).contains("required when after_process = move");
        assertThat(errs.get(0).recommendations()).containsExactly("Set archive_path: /path/to/archive/");
    }

    @Test void aFieldRequiredWhenAnotherHasAnotherValue_isLeftAlone() throws Exception {
        assertThat(run(archive(), "{\"after_process\":\"none\"}")).isEmpty();
    }

    /** YAML spells "nothing" three ways: the key missing, `key:` with nothing after, and a blank string. */
    @ParameterizedTest(name = "archive_path: {0}")
    @ValueSource(strings = {"", ",\"archive_path\":null", ",\"archive_path\":\"   \"", ",\"archive_path\":\"\""})
    void everySpellingOfNothingCountsAsMissing(String archiveLine) throws Exception {
        assertThat(run(archive(), "{\"after_process\":\"move\"" + archiveLine + "}")).hasSize(1);
    }

    @Test void aValueThatIsThere_satisfiesTheCondition() throws Exception {
        assertThat(run(archive(), "{\"after_process\":\"move\",\"archive_path\":\"/done/\"}")).isEmpty();
    }

    @Test void aListWrittenEmpty_countsAsMissingToo() throws Exception {
        var def = new ConfigDef()
            .optional("headerless", Type.BOOLEAN, Importance.LOW, "No header row", false)
            .optional("columns", Type.LIST, Importance.MEDIUM, "Column names", null)
                .requiredWhen("headerless", "true");

        assertThat(run(def, "{\"headerless\":true,\"columns\":[]}")).hasSize(1);
        assertThat(run(def, "{\"headerless\":true,\"columns\":[\"a\"]}")).isEmpty();
    }

    @Test void aConditionOverSeveralValues_namesThemAll() throws Exception {
        var def = new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Transport", "local", "sftp", "s3")
            .optional("path", Type.STRING, Importance.HIGH, "File path", null)
                .requiredWhen("kind", "local", "sftp");

        assertThat(run(def, "{\"kind\":\"sftp\"}")).singleElement()
            .satisfies(e -> assertThat(e.message()).contains("kind is local or sftp"));
        assertThat(run(def, "{\"kind\":\"local\"}")).hasSize(1);
        assertThat(run(def, "{\"kind\":\"s3\"}")).isEmpty();
    }

    @Test void aConditionTheSiblingsCannotExpress_isAPredicateOverTheBlockWithItsOwnWords() throws Exception {
        var def = new ConfigDef()
            .optional("strict", Type.BOOLEAN, Importance.LOW, "Verify the key", true)
            .optional("fingerprint", Type.STRING, Importance.MEDIUM, "Host key", null)
                .requiredWhen("strict is on (the default)", block -> block.path("strict").asBoolean(true));

        var errs = run(def, "{}");
        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.fingerprint");
            assertThat(e.message()).contains("required when strict is on (the default)");
        });
        assertThat(run(def, "{\"strict\":false}")).isEmpty();
    }

    @Test void aFieldWithoutAConditionKeepsAcceptingABlankValue() throws Exception {
        var def = new ConfigDef().optional("prefix", Type.STRING, Importance.LOW, "Key prefix", "");
        assertThat(run(def, "{\"prefix\":\"\"}")).isEmpty();
    }

    // -- atLeastOneOf ------------------------------------------------------------------------------

    private static ConfigDef credentials() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Transport", "local", "sftp")
            .optional("key_env", Type.STRING, Importance.MEDIUM, "SSH key variable", null)
            .optional("password_env", Type.STRING, Importance.MEDIUM, "SSH password variable", null)
            .atLeastOneOf("kind is sftp", block -> "sftp".equals(block.path("kind").asText()),
                "Use `lm secret set <NAME> ...` then reference it via key_env: <NAME>", "key_env", "password_env");
    }

    @Test void whenNoneOfTheNamedFieldsIsGiven_theBlockIsRefusedOnce() throws Exception {
        var errs = run(credentials(), "{\"kind\":\"sftp\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.message()).contains("key_env").contains("password_env").contains("kind is sftp");
            assertThat(e.recommendations()).hasSize(1);
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"key_env\":\"K\"", "\"password_env\":\"P\"", "\"key_env\":\"K\",\"password_env\":\"P\""})
    void oneOfThemIsEnough(String given) throws Exception {
        assertThat(run(credentials(), "{\"kind\":\"sftp\"," + given + "}")).isEmpty();
    }

    /** These name a SECRET: `key_env:` written empty, or blank, names none. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"key_env\":null", "\"key_env\":\"  \"", "\"key_env\":null,\"password_env\":\"\""})
    void anEmptyOrBlankNameIsNotOne(String given) throws Exception {
        assertThat(run(credentials(), "{\"kind\":\"sftp\"," + given + "}")).hasSize(1);
    }

    @Test void whenTheConditionDoesNotHold_nothingIsAsked() throws Exception {
        assertThat(run(credentials(), "{\"kind\":\"local\"}")).isEmpty();
    }

    // -- refused -----------------------------------------------------------------------------------

    private static ConfigDef oneShotOnly() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Transport", "local", "sftp")
            .refused("pre_process", "pre_process is not read by connector runs", "Drop it from the connector manifest")
            .closed();
    }

    @Test void aRefusedKeyThatIsGiven_isRefusedWithItsWordsAndNothingElse() throws Exception {
        var errs = run(oneShotOnly(), "{\"kind\":\"local\",\"pre_process\":\"x.sh\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.pre_process");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.message()).isEqualTo("pre_process is not read by connector runs");
            assertThat(e.recommendations()).containsExactly("Drop it from the connector manifest");
        });
    }

    /** A key written with nothing after it declares nothing, so there is nothing to teach about. */
    @ParameterizedTest(name = "pre_process: {0}")
    @ValueSource(strings = {"", ",\"pre_process\":null"})
    void aRefusedKeyThatIsAbsent_isLeftAlone(String line) throws Exception {
        assertThat(run(oneShotOnly(), "{\"kind\":\"local\"" + line + "}")).isEmpty();
    }

    // -- what the hub publishes --------------------------------------------------------------------

    @Test void theHubPublishesTheConditionsAndKeepsRefusedKeysOutOfTheSettableOnes() {
        JsonNode json = ConfigDefSerializer.toJson(new ConfigDef()
            .optional("archive_path", Type.STRING, Importance.LOW, "Where moved files go", null)
                .requiredWhen("after_process", "move")
            .optional("password_env", Type.STRING, Importance.MEDIUM, "SSH password variable", null)
            .atLeastOneOf("kind is sftp", block -> true, "hint", "key_env", "password_env")
            .refused("pre_process", "not read by connector runs", "Drop it"));

        List<String> settable = json.get("fields").findValuesAsText("name");
        assertThat(settable).containsExactly("archive_path", "password_env");

        JsonNode when = json.get("fields").get(0).get("required_when");
        assertThat(when.get("field").asText()).isEqualTo("after_process");
        assertThat(when.get("equals").asText()).isEqualTo("move");
        assertThat(when.get("when").asText()).isEqualTo("after_process = move");

        assertThat(json.get("at_least_one_of").get(0).get("fields").findValuesAsText("")).isEmpty();
        assertThat(json.get("at_least_one_of").get(0).get("fields")).hasSize(2);
        assertThat(json.get("at_least_one_of").get(0).get("fields").get(0).asText()).isEqualTo("key_env");
        assertThat(json.get("at_least_one_of").get(0).get("when").asText()).isEqualTo("kind is sftp");

        assertThat(json.get("refused").get(0).get("name").asText()).isEqualTo("pre_process");
        assertThat(json.get("refused").get(0).get("message").asText()).isEqualTo("not read by connector runs");
    }
}
