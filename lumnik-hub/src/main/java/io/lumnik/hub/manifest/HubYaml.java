package io.lumnik.hub.manifest;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

/**
 * The hub's one way to build a YAML mapper (#1489). Every mapper it builds reads booleans as YAML 1.2's core
 * schema does: only {@code true}/{@code True}/{@code TRUE} and {@code false}/{@code False}/{@code FALSE}.
 *
 * <p>Jackson's default is YAML 1.1's: an unquoted {@code yes}, {@code no}, {@code on} or {@code off}, in any case,
 * is a boolean. Every text knob accepts a boolean and keeps its text, so a column named {@code no}, a
 * {@code filter-where} {@code in: [yes, no]} or a workflow state {@code OFF} became {@code "false"} in silence — and
 * {@code lm}'s own YAML (yaml.v3) already reads those words as text, so the same manifest meant two things depending
 * on the door. Here they are text, and a boolean knob written {@code yes} is refused as text by its own check.
 *
 * <p>{@code HubYamlTest} holds the rule: no other hub source builds a YAML mapper or factory.
 */
public final class HubYaml {

    private HubYaml() {}

    /** A builder that already reads YAML 1.2 booleans, for a mapper that needs more configuration. */
    public static YAMLMapper.Builder builder() {
        return YAMLMapper.builder().enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS);
    }

    public static YAMLMapper mapper() {
        return builder().build();
    }
}
