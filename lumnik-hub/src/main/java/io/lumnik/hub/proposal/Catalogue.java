package io.lumnik.hub.proposal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The proposal catalogue as the hub reads it (spec 2026-10-03, section 3); production code since
 * slice 3a (#802). Jackson fails on unknown properties by default, so a misspelled key in
 * catalogue.json is a load failure, not a silently absent field.
 */
record Catalogue(String catalogue, List<Question> questions, List<Rule> rules) {

    static final String RESOURCE = "proposal/catalogue.json";

    /** Strict JSON, shared by every reader of the package. Jackson's defaults already refuse comments,
     *  trailing commas and unquoted tokens; two refusals need asking for: duplicate names (RFC 8259 only
     *  says SHOULD be unique — spec section 1 binds every reader, the catalogue's included) and content
     *  after the value (Jackson stops reading at the first value by default). */
    static final JsonMapper STRICT_JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    record Question(String id, String kind, List<Option> options, Map<String, String> asked) {}

    record Option(String id, Map<String, String> label) {}

    record Rule(String id, int version, When when, Map<String, Meaning> meaning, Proposes proposes,
                Grounds grounds, List<Condition> conditions) {}

    record When(String answer, String is) {}

    record Meaning(String title, String says, @JsonProperty("does-not-say") List<String> doesNotSay) {}

    record Proposes(String kind, List<String> uses) {}

    record Grounds(Doc doc, String behaviour) {}

    record Doc(String page, String anchor) {}

    record Condition(String needs, @JsonProperty("asked-by") String askedBy, Map<String, String> label,
                     String verifier, String target, String property, Map<String, String> note,
                     @JsonProperty("confirmed-when") String confirmedWhen,
                     @JsonProperty("refuted-when") String refutedWhen) {}

    static Catalogue load() {
        try (InputStream in = Catalogue.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is not on the classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The typed model from a tree already read, so a digest and its model come from the same parse. */
    static Catalogue from(JsonNode tree) {
        try {
            return STRICT_JSON.treeToValue(tree, Catalogue.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Catalogue parse(String json) {
        try {
            return STRICT_JSON.readValue(json, Catalogue.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
