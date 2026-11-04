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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A key that means something only under one kind says so, and is checked only where it is read. */
class ConfigDefGatesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ValidationError> run(ConfigDef def, String json) throws Exception {
        return ConfigDefRunner.run(def, mapper.readTree(json), "spec.auth");
    }

    private static ConfigDef auth() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Method", "bearer", "oauth2", "none")
            .optional("scheme", Type.STRING, Importance.LOW, "Word before the token", "Bearer")
                .ignoredUnless("kind", "bearer")
                .check(v -> v.asText().contains(" ") ? Optional.of("must be one word") : Optional.empty())
                .hint("Bearer, or the word the API expects")
            .optional("client_auth", Type.STRING, Importance.LOW, "How the client authenticates", "basic")
                .ignoredUnless("kind", "oauth2", "bearer")
                .oneOf("basic", "post");
    }

    // -- ignoredUnless ----------------------------------------------------------------------------

    @Test void aKeyUnderTheKindThatReadsIt_isCheckedLikeAnyOther() throws Exception {
        var errs = run(auth(), "{\"kind\":\"bearer\",\"scheme\":\"two words\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.auth.scheme");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.message()).isEqualTo("must be one word");
            assertThat(e.recommendations()).containsExactly("Bearer, or the word the API expects");
        });
        assertThat(run(auth(), "{\"kind\":\"bearer\",\"scheme\":\"Token\"}")).isEmpty();
    }

    @Test void aKeyUnderAnotherKind_isNamedAsIgnored_andNotChecked() throws Exception {
        var errs = run(auth(), "{\"kind\":\"none\",\"scheme\":\"two words\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.auth.scheme");
            assertThat(e.severity()).isEqualTo(Severity.WARNING);
            assertThat(e.message()).isEqualTo("scheme is ignored unless kind = bearer — the runtime reads it only then");
            assertThat(e.recommendations()).containsExactly("Remove it, or set kind to bearer");
        });
    }

    @Test void aKeyWithNoValue_isNeitherIgnoredNorChecked() throws Exception {
        assertThat(run(auth(), "{\"kind\":\"none\"}")).isEmpty();
        assertThat(run(auth(), "{\"kind\":\"none\",\"scheme\":null}")).isEmpty();
    }

    @Test void aKeyOfTheWrongTypeIsStillMistyped_whateverTheKind() throws Exception {
        var errs = run(auth(), "{\"kind\":\"none\",\"scheme\":7}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.message()).startsWith("type mismatch");
        });
    }

    @Test void aKeyReadUnderSeveralKinds_namesThemAll() throws Exception {
        assertThat(run(auth(), "{\"kind\":\"none\",\"client_auth\":\"post\"}")).singleElement().satisfies(e -> {
            assertThat(e.message()).isEqualTo("client_auth is ignored unless kind is oauth2 or bearer — the runtime reads it only then");
            assertThat(e.recommendations()).containsExactly("Remove it, or set kind to oauth2 or bearer");
        });
        assertThat(run(auth(), "{\"kind\":\"oauth2\",\"client_auth\":\"post\"}")).isEmpty();
        assertThat(run(auth(), "{\"kind\":\"bearer\",\"client_auth\":\"post\"}")).isEmpty();
    }

    @Test void whenTheKindIsMissingItself_theKeyIsIgnoredAsUnderAnyOtherKind() throws Exception {
        var errs = run(auth(), "{\"scheme\":\"Token\"}");

        assertThat(errs).extracting(ValidationError::path).containsExactlyInAnyOrder("spec.auth.kind", "spec.auth.scheme");
    }

    // -- oneOf ------------------------------------------------------------------------------------

    @ParameterizedTest(name = "client_auth: {0}")
    @ValueSource(strings = {"jwt", "BASIC", ""})
    void oneOfRefusesWhatIsNotListed_andNamesWhatIs(String value) throws Exception {
        var errs = run(auth(), "{\"kind\":\"oauth2\",\"client_auth\":\"" + value + "\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.message()).isEqualTo("must be one of: basic, post");
            assertThat(e.recommendations()).containsExactly("basic", "post");
        });
    }

    @Test void oneOfAcceptsEachListedValue() throws Exception {
        assertThat(run(auth(), "{\"kind\":\"oauth2\",\"client_auth\":\"basic\"}")).isEmpty();
        assertThat(run(auth(), "{\"kind\":\"oauth2\",\"client_auth\":\"post\"}")).isEmpty();
    }

    /**
     * The values a field accepts and the sentences a refusal of it teaches are two things; the hub publishes them
     * apart, so no reader has to tell them by their look. A hint written after {@code oneOf} no longer hides the values.
     */
    @Test void theAllowedValuesAreNotTheHints_andBothArePublished() {
        ConfigDef def = new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Method", "bearer", "none")
            .optional("client_auth", Type.STRING, Importance.LOW, "How the client authenticates", "basic")
                .oneOf("basic", "post")
                .hint("basic (the default) sends the secret as HTTP Basic");
        JsonNode json = ConfigDefSerializer.toJson(def);

        JsonNode kind = json.get("fields").get(0);
        assertThat(kind.get("allowed").findValuesAsText("")).isEmpty();
        assertThat(kind.get("allowed")).extracting(JsonNode::asText).containsExactly("bearer", "none");
        assertThat(kind.has("recommendations")).as("an enum with no hint of its own has no hints").isFalse();

        JsonNode clientAuth = json.get("fields").get(1);
        assertThat(clientAuth.get("allowed")).extracting(JsonNode::asText).containsExactly("basic", "post");
        assertThat(clientAuth.get("recommendations")).extracting(JsonNode::asText)
            .containsExactly("basic (the default) sends the secret as HTTP Basic");
    }

    // -- warnWhenGiven ----------------------------------------------------------------------------

    private static ConfigDef token() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Method", "bearer", "none")
            .optional("token_value", Type.STRING, Importance.LOW, "Literal token", null)
                .warnWhenGiven("kind", "bearer", "literal token in the manifest", "Name the secret instead", "Or keep it out of git");
    }

    @Test void aSecretGivenWhereItIsRead_isWarnedAbout() throws Exception {
        var errs = run(token(), "{\"kind\":\"bearer\",\"token_value\":\"s3cret\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.auth.token_value");
            assertThat(e.severity()).isEqualTo(Severity.WARNING);
            assertThat(e.message()).isEqualTo("literal token in the manifest");
            assertThat(e.recommendations()).containsExactly("Name the secret instead", "Or keep it out of git");
        });
    }

    /** The advice speaks of a value that is valid: one the field refused gets the refusal and nothing beside it. */
    @Test void aValueTheFieldRefused_isNotAlsoAdvisedAbout() throws Exception {
        var def = new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Method", "bearer", "none")
            .optional("token_value", Type.STRING, Importance.LOW, "Literal token", null)
                .check(v -> v.asText().startsWith("tk_") ? Optional.empty() : Optional.of("must start with tk_"))
                .warnWhenGiven("kind", "bearer", "literal token in the manifest", "Name the secret instead");

        var refused = run(def, "{\"kind\":\"bearer\",\"token_value\":\"nope\"}");
        assertThat(refused).singleElement().satisfies(e -> {
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
            assertThat(e.message()).isEqualTo("must start with tk_");
        });

        var accepted = run(def, "{\"kind\":\"bearer\",\"token_value\":\"tk_1\"}");
        assertThat(accepted).singleElement().satisfies(e -> assertThat(e.severity()).isEqualTo(Severity.WARNING));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"kind\":\"bearer\"}", "{\"kind\":\"bearer\",\"token_value\":null}",
        "{\"kind\":\"bearer\",\"token_value\":\"  \"}", "{\"kind\":\"none\",\"token_value\":\"s3cret\"}"})
    void noSecretOrNotReadHere_noWarning(String block) throws Exception {
        assertThat(run(token(), block)).isEmpty();
    }

    // -- what the hub publishes -------------------------------------------------------------------

    @Test void theHubPublishesWhenAKeyIsRead() {
        JsonNode json = ConfigDefSerializer.toJson(auth());

        JsonNode scheme = json.get("fields").get(1);
        assertThat(scheme.get("name").asText()).isEqualTo("scheme");
        assertThat(scheme.get("ignored_unless").get("when").asText()).isEqualTo("kind = bearer");
        assertThat(json.get("fields").get(0).has("ignored_unless")).isFalse();
    }
}
